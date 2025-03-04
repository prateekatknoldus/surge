// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package surge.internal.persistence

import akka.actor.{ NoSerializationVerificationNeeded, Props, ReceiveTimeout, Stash, Status }
import akka.cluster.sharding.ShardRegion.Passivate
import akka.pattern.pipe
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.typesafe.config.{ Config, ConfigFactory }
import io.opentelemetry.api.trace.Tracer
import org.slf4j.{ Logger, LoggerFactory }
import surge.akka.cluster.JacksonSerializable
import surge.core._
import surge.health.HealthSignalBusTrait
import surge.internal.SurgeModel
import surge.internal.akka.ActorWithTracing
import surge.internal.config.{ RetryConfig, TimeoutConfig }
import surge.internal.domain.HandledMessageResult
import surge.internal.kafka.HeadersHelper
import surge.internal.tracing.RoutableMessage
import surge.internal.utils.DiagnosticContextFuturePropagation
import surge.kafka.streams.AggregateStateStoreKafkaStreams
import surge.metrics.{ MetricInfo, Metrics, Timer }
import surge.akka.cluster.{ Passivate => SurgePassivate }

import java.time.Instant
import java.util.concurrent.Executors
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

object PersistentActor {

  sealed trait ActorMessage

  sealed trait RoutableActorMessage extends ActorMessage with RoutableMessage

  case class ProcessMessage[M](
      aggregateId: String,
      @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "msgType", visible = true) message: M)
      extends RoutableActorMessage

  case class GetState(aggregateId: String) extends RoutableActorMessage

  case class ApplyEvents[E](
      aggregateId: String,
      @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "eventType", visible = true) events: Seq[E])
      extends RoutableActorMessage

  case class StateResponse[S](
      @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "aggregateType", visible = true) aggregateState: Option[S])
      extends JacksonSerializable

  sealed trait ACK extends ActorMessage with JacksonSerializable

  case class ACKSuccess[S](
      @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "aggregateType", visible = true) aggregateState: Option[S])
      extends ACK

  case class ACKError(
      @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "exceptionType", visible = true) exception: Throwable)
      extends ACK

  case class ACKRejection[R](
      @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "rejectionType", visible = true) rejection: R)
      extends ACK

  case object Stop extends ActorMessage with JacksonSerializable

  case class MetricsQuiver(
      stateInitializationTimer: Timer,
      aggregateDeserializationTimer: Timer,
      commandHandlingTimer: Timer,
      messageHandlingTimer: Timer,
      eventHandlingTimer: Timer,
      serializeStateTimer: Timer,
      serializeEventTimer: Timer,
      eventPublishTimer: Timer)
      extends KTableInitializationMetrics
      with KTablePersistenceMetrics

  private[internal] def createMetrics(metrics: Metrics, aggregateName: String): MetricsQuiver = {
    MetricsQuiver(
      stateInitializationTimer = metrics.timer(
        MetricInfo(
          name = s"surge.${aggregateName.toLowerCase()}.actor-state-initialization-timer",
          description = "Average time in milliseconds taken to load aggregate state from the KTable",
          tags = Map("aggregate" -> aggregateName))),
      aggregateDeserializationTimer = metrics.timer(
        MetricInfo(
          name = s"surge.${aggregateName.toLowerCase()}.aggregate-state-deserialization-timer",
          description = "Average time taken in milliseconds to deserialize aggregate state after the bytes are read from the KTable",
          tags = Map("aggregate" -> aggregateName))),
      commandHandlingTimer = metrics.timer(
        MetricInfo(
          name = s"surge.${aggregateName.toLowerCase()}.command-handling-timer",
          description = "Average time taken in milliseconds for the business logic 'processCommand' function to process a command",
          tags = Map("aggregate" -> aggregateName))),
      messageHandlingTimer = metrics.timer(
        MetricInfo(
          name = s"surge.${aggregateName.toLowerCase()}.command-handling-timer",
          description = "Average time taken in milliseconds for the business logic 'processCommand' function to process a message",
          tags = Map("aggregate" -> aggregateName))),
      eventHandlingTimer = metrics.timer(
        MetricInfo(
          name = s"surge.${aggregateName.toLowerCase()}.event-handling-timer",
          description = "Average time taken in milliseconds for the business logic 'handleEvent' function to handle an event",
          tags = Map("aggregate" -> aggregateName))),
      serializeStateTimer = metrics.timer(
        MetricInfo(
          name = s"surge.${aggregateName.toLowerCase()}.aggregate-state-serialization-timer",
          description = "Average time taken in milliseconds to serialize a new aggregate state to bytes before persisting to Kafka",
          tags = Map("aggregate" -> aggregateName))),
      serializeEventTimer = metrics.timer(
        MetricInfo(
          name = s"surge.${aggregateName.toLowerCase()}.event-serialization-timer",
          description = "Average time taken in milliseconds to serialize an individual event to bytes before persisting to Kafka",
          tags = Map("aggregate" -> aggregateName))),
      eventPublishTimer = metrics.timer(
        MetricInfo(
          name = s"surge.${aggregateName.toLowerCase()}.event-publish-timer",
          description = "Average time taken in milliseconds to persist all generated events plus an updated state to Kafka",
          tags = Map("aggregate" -> aggregateName))))

  }

  def props[S, M, R, E](
      businessLogic: SurgeModel[S, M, R, E],
      signalBus: HealthSignalBusTrait,
      regionSharedResources: PersistentEntitySharedResources,
      config: Config,
      aggregateIdOpt: Option[String] = None): Props = {
    Props(new PersistentActor(businessLogic, regionSharedResources, signalBus, config, aggregateIdOpt))
  }

  val serializationThreadPoolSize: Int = ConfigFactory.load().getInt("surge.serialization.thread-pool-size")
  val serializationExecutionContext: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(serializationThreadPoolSize))
}

// scalastyle:off number.of.methods
class PersistentActor[S, M, R, E](
    val businessLogic: SurgeModel[S, M, R, E],
    val regionSharedResources: PersistentEntitySharedResources,
    val signalBus: HealthSignalBusTrait,
    config: Config,
    val aggregateIdOpt: Option[String])
    extends ActorWithTracing
    with Stash
    with KTablePersistenceSupport[S, E]
    with KTableInitializationSupport[S] {

  import PersistentActor._
  import context.dispatcher

  def aggregateId: String = aggregateIdOpt.getOrElse(self.path.name)

  private val metrics = regionSharedResources.metrics

  private val isAkkaClusterEnabled: Boolean = config.getBoolean("surge.feature-flags.experimental.enable-akka-cluster")

  private sealed trait Internal extends NoSerializationVerificationNeeded

  private case class InitializeWithState(stateOpt: Option[S]) extends Internal

  private case class PersistenceSuccess(newState: InternalActorState, startTime: Instant) extends Internal

  private case class PersistenceFailure(
      newState: InternalActorState,
      reason: Throwable,
      numberOfFailures: Int,
      serializedEvents: Seq[KafkaProducerActor.MessageToPublish],
      serializedState: KafkaProducerActor.MessageToPublish,
      startTime: Instant)
      extends Internal

  private case class EventPublishTimedOut(reason: Throwable, startTime: Instant) extends Internal

  protected case class InternalActorState(stateOpt: Option[S])

  protected case class HandleEventResult(result: Option[S]) extends NoSerializationVerificationNeeded

  override type ActorState = InternalActorState

  override val initializationMetrics: KTableInitializationMetrics = regionSharedResources.metrics

  override val ktablePersistenceMetrics: KTablePersistenceMetrics = regionSharedResources.metrics

  override val kafkaProducerActor: KafkaProducerActor = regionSharedResources.aggregateIdToKafkaProducer(aggregateId)

  override val kafkaStreamsCommand: AggregateStateStoreKafkaStreams[_] = regionSharedResources.stateStore

  override def deserializeState(bytes: Array[Byte]): Option[S] = businessLogic.aggregateReadFormatting.readState(bytes)

  override def retryConfig: RetryConfig = new RetryConfig(config)

  override val tracer: Tracer = businessLogic.tracer

  override val aggregateName: String = businessLogic.aggregateName

  protected val receiveTimeout: FiniteDuration = TimeoutConfig.AggregateActor.idleTimeout

  override protected val maxProducerFailureRetries: Int = config.getInt("surge.aggregate-actor.publish-failure-max-retries")

  protected val log: Logger = LoggerFactory.getLogger(getClass)

  private val publishStateOnly: Boolean = businessLogic.kafka.eventsTopicOpt.isEmpty

  assert(publishStateOnly || businessLogic.eventWriteFormattingOpt.nonEmpty, "businessLogic.eventWriteFormattingOpt may not be none when publishing events")

  override def messageNameForTracedMessages: MessageNameExtractor = { case t: ProcessMessage[_] =>
    s"ProcessMessage[${t.message.getClass.getSimpleName}]"
  }

  override def preStart(): Unit = {
    initializeState(initializationAttempts = 0, None)
    super.preStart()
  }

  private def surgeContext() = Context(businessLogic.executionContext, this)

  override def receive: Receive = uninitialized

  private def freeToProcess(state: InternalActorState): Receive = {
    case pm: ProcessMessage[M] =>
      handle(state, pm)
    case ae: ApplyEvents[E] =>
      val evts: Seq[E] = ae.events
      val result: Future[HandleEventResult] = callEventHandler(state, evts)
      pipe(result).to(self, sender())
      context.become(waitForHandleEventResult(state))
    case GetState(_)    => sender() ! StateResponse(state.stateOpt)
    case ReceiveTimeout => handlePassivate()
    case Stop           => handleStop()
  }

  private def handle(initializeWithState: InitializeWithState): Unit = {
    log.debug(s"Actor state for aggregate $aggregateId successfully initialized")
    unstashAll()

    val internalActorState = InternalActorState(stateOpt = initializeWithState.stateOpt)

    context.setReceiveTimeout(receiveTimeout)
    context.become(freeToProcess(internalActorState))
  }

  private def handle(state: InternalActorState, msg: ProcessMessage[M]): Unit = {
    context.setReceiveTimeout(Duration.Inf)
    context.become(persistingEvents(state))

    processMessage(state, msg)
      .flatMap {
        case Left(r) => Future.successful(ACKRejection(r))
        case Right(handled) =>
          val serializingFut = if (publishStateOnly) {
            Future.successful(Seq.empty)
          } else {
            serializeEvents(handled.eventsToLog)
          }
          for {
            serializedState <- serializeState(handled.resultingState)
            serializedEvents <- serializingFut
            publishResult <- doPublish(
              state.copy(stateOpt = handled.resultingState),
              serializedEvents,
              serializedState,
              startTime = Instant.now,
              didStateChange = state.stateOpt != handled.resultingState)
          } yield {
            publishResult
          }
      }
      .recover { case e =>
        ACKError(e)
      }
      .pipeTo(self)(sender())
  }

  private def processMessage(state: InternalActorState, ProcessMessage: ProcessMessage[M]): Future[Either[R, HandledMessageResult[S, E]]] = {
    metrics.messageHandlingTimer.timeFuture { businessLogic.model.handle(surgeContext(), state.stateOpt, ProcessMessage.message) }
  }

  private def callEventHandler(state: InternalActorState, evts: Seq[E]): Future[HandleEventResult] = {
    metrics.eventHandlingTimer.time(businessLogic.model.applyAsync(surgeContext(), state.stateOpt, evts)).map { maybeS: Option[S] =>
      HandleEventResult(result = maybeS)
    }
  }

  private def waitForHandleEventResult(state: InternalActorState): Receive = {
    case HandleEventResult(newState) =>
      log.debug("Received result from async handle event call!")
      context.setReceiveTimeout(Duration.Inf)
      context.become(persistingEvents(state))
      unstashAll()
      val futureStatePersisted = serializeState(newState).flatMap { serializedState =>
        doPublish(
          state.copy(stateOpt = newState),
          serializedEvents = Seq.empty,
          serializedState = serializedState,
          startTime = Instant.now,
          didStateChange = state.stateOpt != newState)
      }
      futureStatePersisted.pipeTo(self)(sender())
    case failedFuture: akka.actor.Status.Failure =>
      log.error("Received failure from async handle event call!", failedFuture.cause)
      sender() ! ACKError(failedFuture.cause)
      context.become(freeToProcess(state))
      unstashAll()
    case otherMsg =>
      stash()
  }

  private def uninitialized: Receive = {
    case msg: InitializeWithState => handle(msg)
    case ReceiveTimeout           =>
      // Ignore and drop ReceiveTimeout messages from this state
      log.warn(
        s"Aggregate actor for $aggregateId received a ReceiveTimeout message in uninitialized state. " +
          "This should not happen and is likely a logic error. Dropping the ReceiveTimeout message.")
    case other =>
      log.debug(s"PersistentActor actor for $aggregateId stashing a message with class [{}] from the 'uninitialized' state", other.getClass)
      activeSpan.addEvent("stashed")
      stash()
  }

  private def handlePassivate(): Unit = {
    log.trace(s"PersistentActor for aggregate ${businessLogic.aggregateName} $aggregateId is passivating gracefully")

    //FIXME: temporary fix to support switch between akka and existing shard allocation strategy
    if (isAkkaClusterEnabled) {
      context.parent ! Passivate(Stop)
    } else {
      context.parent ! SurgePassivate(Stop)
    }
  }

  private def handleStop(): Unit = {
    log.trace(s"PersistentActor for aggregate ${businessLogic.aggregateName} $aggregateId is stopping gracefully")
    context.stop(self)
  }

  private def handleCommandError(state: InternalActorState, error: ACKError): Unit = {
    log.debug(s"The command for ${businessLogic.aggregateName} $aggregateId resulted in an error", error.exception)
    context.setReceiveTimeout(receiveTimeout)
    context.become(freeToProcess(state))

    sender() ! error
  }

  override def receiveWhilePersistingEvents(state: InternalActorState): Receive = {
    case msg: ACKError  => handleCommandError(state, msg)
    case ReceiveTimeout => // Ignore and drop ReceiveTimeout messages from this state
    case Status.Failure(e) =>
      log.error(s"Aggregate actor for $aggregateId saw an unexpected exception from the 'persistingEvents' state", e)
      self.forward(ACKError(e))
    case other =>
      log.info(s"Aggregate actor for $aggregateId stashing a message with class [{}] from the 'persistingEvents' state", other.getClass)
      stash()
  }

  private def initializationFailed(error: ACKError): Receive = {
    case _: ProcessMessage[M] => sender() ! error
    case _: ApplyEvents[E]    => sender() ! error
    case Stop                 => handleStop()
  }

  def onInitializationFailed(cause: Throwable): Unit = {
    log.error(s"Could not initialize actor for $aggregateId after ${retryConfig.AggregateActor.maxInitializationAttempts} attempts. Stopping actor", cause)
    context.become(initializationFailed(ACKError(cause)))
    unstashAll() // Handle any pending messages before stopping so we can reply with an explicit error instead of timing out
    self ! Stop
  }

  override def onInitializationSuccess(model: Option[S]): Unit = {
    self ! InitializeWithState(model)
  }

  override def onPersistenceSuccess(newState: InternalActorState): Unit = {
    activeSpan.log("Successfully persisted events + state")
    context.setReceiveTimeout(receiveTimeout)
    context.become(freeToProcess(newState))

    val cmdSuccess = ACKSuccess(newState.stateOpt)
    sender() ! cmdSuccess
    unstashAll()
  }

  override def onPersistenceFailure(state: InternalActorState, cause: Throwable): Unit = {
    log.error(s"Error while trying to publish to Kafka, crashing actor for $aggregateName $aggregateId", cause)
    activeSpan.log("Failed to persist events + state")
    activeSpan.error(cause)
    sender() ! ACKError(cause)
    context.stop(self)
  }

  private def serializeEvents(events: Seq[E]): Future[Seq[KafkaProducerActor.MessageToPublish]] = Future {
    val eventWriteFormatting = businessLogic.eventWriteFormattingOpt.getOrElse {
      throw new IllegalStateException("businessLogic.eventWriteFormattingOpt must not be None")
    }
    events.map { event =>
      val serializedMessage = metrics.serializeEventTimer.time(eventWriteFormatting.writeEvent(event))
      log.trace(s"Publishing event for {} {}", Seq(businessLogic.aggregateName, serializedMessage.key): _*)
      KafkaProducerActor.MessageToPublish(
        key = serializedMessage.key,
        value = serializedMessage.value,
        headers = HeadersHelper.createHeaders(serializedMessage.headers))
    }

  }(serializationExecutionContext)

  private def serializeState(stateValueOpt: Option[S]): Future[KafkaProducerActor.MessageToPublish] = Future {
    val serializedStateOpt = stateValueOpt.map { value =>
      metrics.serializeStateTimer.time(businessLogic.aggregateWriteFormatting.writeState(value))
    }
    val stateValue = serializedStateOpt.map(_.value).orNull
    val stateHeaders = serializedStateOpt.map(ser => HeadersHelper.createHeaders(ser.headers)).orNull
    KafkaProducerActor.MessageToPublish(aggregateId, stateValue, stateHeaders)
  }(serializationExecutionContext)
}
