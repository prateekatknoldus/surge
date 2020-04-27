// Copyright © 2017-2019 Ultimate Software Group. <https://www.ultimatesoftware.com>

package com.ultimatesoftware.kafka.streams.core

import akka.actor._
import com.ultimatesoftware.akka.cluster.{ EntityPropsProvider, PerShardLogicProvider, Shard }
import com.ultimatesoftware.kafka.streams.{ AggregateStateStoreKafkaStreams, GlobalKTableMetadataHandler, HealthCheck, HealthyComponent }
import com.ultimatesoftware.kafka.{ KafkaPartitionShardRouterActor, TopicPartitionRegionCreator }
import com.ultimatesoftware.scala.core.monitoring.metrics.MetricsProvider
import org.apache.kafka.common.TopicPartition
import play.api.libs.json.JsValue

import scala.concurrent.{ ExecutionContext, Future }

private[streams] final class GenericAggregateActorRouter[AggId, Agg, Command, Event, CmdMeta, EvtMeta](
    system: ActorSystem,
    clusterStateTrackingActor: ActorRef,
    businessLogic: KafkaStreamsCommandBusinessLogic[AggId, Agg, Command, Event, CmdMeta, EvtMeta],
    metricsProvider: MetricsProvider,
    stateMetaHandler: GlobalKTableMetadataHandler,
    kafkaStreamsCommand: AggregateStateStoreKafkaStreams[JsValue]) extends HealthyComponent {

  val actorRegion: ActorRef = {
    val shardRegionCreator = new TopicPartitionRegionCreator {
      override def propsFromTopicPartition(topicPartition: TopicPartition): Props = {
        val provider = new GenericAggregateActorRegionProvider(topicPartition, businessLogic,
          stateMetaHandler, kafkaStreamsCommand, metricsProvider)

        Shard.props(topicPartition.toString, provider, GenericAggregateActor.RoutableMessage.extractEntityId)
      }
    }

    val shardRouterProps = KafkaPartitionShardRouterActor.props(clusterStateTrackingActor, businessLogic.partitioner, businessLogic.kafka.stateTopic,
      shardRegionCreator, GenericAggregateActor.RoutableMessage.extractEntityId)
    system.actorOf(shardRouterProps, name = "RouterActor")
  }

  override def healthCheck(): Future[HealthCheck] = Future {
    HealthCheck(
      name = "GenericAggregateActorRouter",
      isHealthy = true,
      responseTime = Some(1),
      components = Seq(),
      message = None)
  }(ExecutionContext.global)
}

class GenericAggregateActorRegionProvider[AggId, Agg, Command, Event, CmdMeta, EvtMeta](
    assignedPartition: TopicPartition,
    businessLogic: KafkaStreamsCommandBusinessLogic[AggId, Agg, Command, Event, CmdMeta, EvtMeta],
    stateMetaHandler: GlobalKTableMetadataHandler,
    aggregateKafkaStreamsImpl: AggregateStateStoreKafkaStreams[JsValue],
    metricsProvider: MetricsProvider) extends PerShardLogicProvider[AggId] {

  override def actorProvider(context: ActorContext): EntityPropsProvider[AggId] = {
    val kafkaProducerActor = new KafkaProducerActor[AggId, Agg, Event, EvtMeta](
      actorSystem = context.system,
      assignedPartition = assignedPartition,
      metricsProvider = metricsProvider,
      stateMetaHandler = stateMetaHandler,
      aggregateCommandKafkaStreams = businessLogic)

    val aggregateMetrics = GenericAggregateActor.createMetrics(metricsProvider, businessLogic.aggregateName)

    actorId: AggId ⇒ GenericAggregateActor.props(aggregateId = actorId, businessLogic = businessLogic,
      kafkaProducerActor = kafkaProducerActor, metrics = aggregateMetrics, kafkaStreamsCommand = aggregateKafkaStreamsImpl)
  }
}
