// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package surge.scaladsl.command

import surge.core.command.AggregateCommandModelCoreTrait
import surge.internal.domain.CommandHandler
import surge.internal.persistence
import surge.scaladsl.common.Context

import scala.concurrent.Future
import scala.util.Try

trait AggregateCommandModel[Agg, Cmd, Evt, Response] extends AggregateCommandModelCoreTrait[Agg, Cmd, Nothing, Evt, Response] {
  def processCommand(aggregate: Option[Agg], command: Cmd): Try[Seq[Evt]]
  def handleEvent(aggregate: Option[Agg], event: Evt): Option[Agg]
  def responseFromState(state: Option[Agg]): Option[Response]

  final def toCore: CommandHandler[Agg, Cmd, Nothing, Evt, Response] =
    new CommandHandler[Agg, Cmd, Nothing, Evt, Response] {
      override def processCommand(ctx: persistence.Context, state: Option[Agg], cmd: Cmd): Future[CommandResult] =
        Future.fromTry(AggregateCommandModel.this.processCommand(state, cmd).map(v => Right(v)))
      override def apply(ctx: persistence.Context, state: Option[Agg], event: Evt): Option[Agg] = handleEvent(state, event)
      override def extractResponse(state: Option[Agg]): Option[Response] = responseFromState(state)
    }
}

trait ContextAwareAggregateCommandModel[Agg, Cmd, Evt, Response] extends AggregateCommandModelCoreTrait[Agg, Cmd, Nothing, Evt, Response] {
  def processCommand(ctx: Context, aggregate: Option[Agg], command: Cmd): Future[Seq[Evt]]
  def handleEvent(ctx: Context, aggregate: Option[Agg], event: Evt): Option[Agg]
  def responseFromState(state: Option[Agg]): Option[Response]

  final def toCore: CommandHandler[Agg, Cmd, Nothing, Evt, Response] =
    new CommandHandler[Agg, Cmd, Nothing, Evt, Response] {
      override def processCommand(ctx: persistence.Context, state: Option[Agg], cmd: Cmd): Future[CommandResult] =
        ContextAwareAggregateCommandModel.this.processCommand(Context(ctx), state, cmd).map(v => Right(v))(ctx.executionContext)
      override def apply(ctx: persistence.Context, state: Option[Agg], event: Evt): Option[Agg] = handleEvent(Context(ctx), state, event)
      override def extractResponse(state: Option[Agg]): Option[Response] = responseFromState(state)
    }
}

/**
 * Trait for implementing a rejectable command model. Unlike `AggregateCommandModel` this type of command model may choose to accept or reject commands.
 * @tparam Agg
 *   state type
 * @tparam Cmd
 *   command type
 * @tparam Rej
 *   rejection type
 * @tparam Evt
 *   event type
 */
trait RejectableAggregateCommandModel[Agg, Cmd, Rej, Evt, Response] extends AggregateCommandModelCoreTrait[Agg, Cmd, Rej, Evt, Response] {

  /**
   * Process a command
   * @param ctx
   *   the surge context
   * @param aggregate
   *   the current aggregate state
   * @param command
   *   the command to process
   * @return
   *   a Future of Either a rejection or a sequence or events generated by processing the command
   */
  def processCommand(ctx: Context, aggregate: Option[Agg], command: Cmd): Future[Either[Rej, Seq[Evt]]]

  /**
   * Handle an event
   * @param ctx
   *   the surge context
   * @param aggregate
   *   the current aggregate state
   * @param event
   *   the event to apply
   * @return
   *   the some resulting aggregate state or `None` to remove the state.
   */
  def handleEvent(ctx: Context, aggregate: Option[Agg], event: Evt): Option[Agg]

  /**
   * Extracts a response from the resulting state.
   *
   * @param maybeAgg
   *   The state of the aggregate after a comannd is successfully handled and any generated events are applied to the previous state
   * @return
   *   A response to send back to the original sender of the command
   */
  def responseFromState(maybeAgg: Option[Agg]): Option[Response]

  final def toCore: CommandHandler[Agg, Cmd, Rej, Evt, Response] =
    new CommandHandler[Agg, Cmd, Rej, Evt, Response] {
      override def processCommand(ctx: persistence.Context, state: Option[Agg], cmd: Cmd): Future[CommandResult] =
        RejectableAggregateCommandModel.this.processCommand(Context(ctx), state, cmd)
      override def apply(ctx: persistence.Context, state: Option[Agg], event: Evt): Option[Agg] = handleEvent(Context(ctx), state, event)
      override def extractResponse(state: Option[Agg]): Option[Response] = responseFromState(state)
    }
}
