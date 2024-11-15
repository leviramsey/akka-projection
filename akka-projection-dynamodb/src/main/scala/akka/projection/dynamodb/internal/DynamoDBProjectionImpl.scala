/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.dynamodb.internal

import java.util.concurrent.atomic.AtomicLong

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import akka.Done
import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.persistence.query.DeletedDurableState
import akka.persistence.query.DurableStateChange
import akka.persistence.query.UpdatedDurableState
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.query.typed.scaladsl.LoadEventQuery
import akka.persistence.state.scaladsl.DurableStateStore
import akka.persistence.state.scaladsl.GetObjectResult
import akka.projection.BySlicesSourceProvider
import akka.projection.HandlerRecoveryStrategy
import akka.projection.HandlerRecoveryStrategy.Internal.RetryAndSkip
import akka.projection.HandlerRecoveryStrategy.Internal.Skip
import akka.projection.ProjectionContext
import akka.projection.ProjectionId
import akka.projection.RunningProjection
import akka.projection.RunningProjection.AbortProjectionException
import akka.projection.RunningProjectionManagement
import akka.projection.StatusObserver
import akka.projection.dynamodb.DynamoDBProjectionSettings
import akka.projection.dynamodb.internal.DynamoDBOffsetStore.RejectedEnvelope
import akka.projection.dynamodb.scaladsl.DynamoDBTransactHandler
import akka.projection.internal.ActorHandlerInit
import akka.projection.internal.AtLeastOnce
import akka.projection.internal.AtMostOnce
import akka.projection.internal.CanTriggerReplay
import akka.projection.internal.ExactlyOnce
import akka.projection.internal.GroupedHandlerStrategy
import akka.projection.internal.HandlerStrategy
import akka.projection.internal.InternalProjection
import akka.projection.internal.InternalProjectionState
import akka.projection.internal.ManagementState
import akka.projection.internal.OffsetStoredByHandler
import akka.projection.internal.OffsetStrategy
import akka.projection.internal.ProjectionContextImpl
import akka.projection.internal.ProjectionSettings
import akka.projection.internal.SettingsImpl
import akka.projection.javadsl
import akka.projection.scaladsl
import akka.projection.scaladsl.Handler
import akka.projection.scaladsl.SourceProvider
import akka.stream.RestartSettings
import akka.stream.scaladsl.FlowWithContext
import akka.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

/**
 * INTERNAL API
 */
@InternalApi
private[projection] object DynamoDBProjectionImpl {
  import akka.persistence.dynamodb.internal.EnvelopeOrigin.fromBacktracking
  import akka.persistence.dynamodb.internal.EnvelopeOrigin.isFilteredEvent

  val log: Logger = LoggerFactory.getLogger(classOf[DynamoDBProjectionImpl[_, _]])

  private val FutureDone: Future[Done] = Future.successful(Done)
  private val FutureFalse: Future[Boolean] = Future.successful(false)

  private[projection] def createOffsetStore(
      projectionId: ProjectionId,
      sourceProvider: Option[BySlicesSourceProvider],
      settings: DynamoDBProjectionSettings,
      client: DynamoDbAsyncClient)(implicit system: ActorSystem[_]) = {
    new DynamoDBOffsetStore(projectionId, sourceProvider, system, settings, client)
  }

  private val loadEnvelopeCounter = new AtomicLong

  def loadEnvelope[Envelope](env: Envelope, sourceProvider: SourceProvider[_, Envelope])(
      implicit
      ec: ExecutionContext): Future[Envelope] = {
    env match {
      case eventEnvelope: EventEnvelope[_]
          if fromBacktracking(eventEnvelope) && eventEnvelope.eventOption.isEmpty && !eventEnvelope.filtered =>
        val pid = eventEnvelope.persistenceId
        val seqNr = eventEnvelope.sequenceNr
        (sourceProvider match {
          case loadEventQuery: LoadEventQuery =>
            loadEventQuery.loadEnvelope[Any](pid, seqNr)
          case loadEventQuery: akka.persistence.query.typed.javadsl.LoadEventQuery =>
            import scala.jdk.FutureConverters._
            loadEventQuery.loadEnvelope[Any](pid, seqNr).asScala
          case _ =>
            throw new IllegalArgumentException(
              s"Expected sourceProvider [${sourceProvider.getClass.getName}] " +
              "to implement LoadEventQuery when used with eventsBySlices.")
        }).map { loadedEnv =>
          val count = loadEnvelopeCounter.incrementAndGet()
          if (count % 1000 == 0)
            log.info("Loaded event lazily, persistenceId [{}], seqNr [{}]. Load count [{}]", pid, seqNr, count)
          else
            log.debug("Loaded event lazily, persistenceId [{}], seqNr [{}]. Load count [{}]", pid, seqNr, count)
          loadedEnv.asInstanceOf[Envelope]
        }

      case upd: UpdatedDurableState[_] if upd.value == null =>
        val pid = upd.persistenceId
        (sourceProvider match {
          case store: DurableStateStore[_] =>
            store.getObject(pid)
          case store: akka.persistence.state.javadsl.DurableStateStore[_] =>
            import scala.jdk.FutureConverters._
            store.getObject(pid).asScala.map(_.toScala)
          case unknown =>
            throw new IllegalArgumentException(s"Unsupported source provider type '${unknown.getClass}'")
        }).map {
          case GetObjectResult(Some(loadedValue), loadedRevision) =>
            val count = loadEnvelopeCounter.incrementAndGet()
            if (count % 1000 == 0)
              log.info(
                "Loaded durable state lazily, persistenceId [{}], revision [{}]. Load count [{}]",
                pid,
                loadedRevision,
                count)
            else
              log.debug(
                "Loaded durable state lazily, persistenceId [{}], revision [{}]. Load count [{}]",
                pid,
                loadedRevision,
                count)
            new UpdatedDurableState(pid, loadedRevision, loadedValue, upd.offset, upd.timestamp)
              .asInstanceOf[Envelope]
          case GetObjectResult(None, loadedRevision) =>
            new DeletedDurableState(pid, loadedRevision, upd.offset, upd.timestamp)
              .asInstanceOf[Envelope]
        }

      case _ =>
        Future.successful(env)
    }
  }

  private def extractOffsetPidSeqNr[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      envelope: Envelope): OffsetPidSeqNr =
    extractOffsetPidSeqNr(sourceProvider.extractOffset(envelope), envelope)

  private def extractOffsetPidSeqNr[Offset, Envelope](offset: Offset, envelope: Envelope): OffsetPidSeqNr = {
    // we could define a new trait for the SourceProvider to implement this in case other (custom) envelope types are needed
    envelope match {
      case env: EventEnvelope[_]       => OffsetPidSeqNr(offset, env.persistenceId, env.sequenceNr)
      case chg: UpdatedDurableState[_] => OffsetPidSeqNr(offset, chg.persistenceId, chg.revision)
      case del: DeletedDurableState[_] => OffsetPidSeqNr(offset, del.persistenceId, del.revision)
      case other => // avoid unreachable error on sealed DurableStateChange
        other match {
          case change: DurableStateChange[_] =>
            // in case additional types are added
            throw new IllegalArgumentException(
              s"DurableStateChange [${change.getClass.getName}] not implemented yet. Please report bug at https://github.com/akka/akka-projection/issues")
          case _ => OffsetPidSeqNr(offset)
        }
    }
  }

  private[projection] def adaptedHandlerForAtLeastOnce[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      handlerFactory: () => Handler[Envelope],
      offsetStore: DynamoDBOffsetStore)(
      implicit
      ec: ExecutionContext,
      system: ActorSystem[_]): () => Handler[Envelope] = { () =>
    new AdaptedHandler(handlerFactory()) {
      override def process(envelope: Envelope): Future[Done] = {
        import DynamoDBOffsetStore.Validation._
        offsetStore
          .validate(envelope)
          .flatMap {
            case Accepted =>
              if (isFilteredEvent(envelope)) {
                offsetStore.addInflight(envelope)
                FutureDone
              } else {
                loadEnvelope(envelope, sourceProvider).flatMap { loadedEnvelope =>
                  delegate
                    .process(loadedEnvelope)
                    .map { _ =>
                      offsetStore.addInflight(loadedEnvelope)
                      Done
                    }
                }
              }
            case Duplicate =>
              FutureDone
            case RejectedSeqNr =>
              triggerReplayIfPossible(sourceProvider, offsetStore, envelope).map(_ => Done)(ExecutionContext.parasitic)
            case RejectedBacktrackingSeqNr =>
              triggerReplayIfPossible(sourceProvider, offsetStore, envelope).map {
                case true  => Done
                case false => throwRejectedEnvelope(sourceProvider, envelope)
              }
          }
      }
    }
  }

  private[projection] def adaptedHandlerForExactlyOnce[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      handlerFactory: () => DynamoDBTransactHandler[Envelope],
      offsetStore: DynamoDBOffsetStore)(
      implicit
      ec: ExecutionContext,
      system: ActorSystem[_]): () => Handler[Envelope] = { () =>

    new AdaptedDynamoDBTransactHandler(handlerFactory()) {
      override def process(envelope: Envelope): Future[Done] = {
        import DynamoDBOffsetStore.Validation._
        offsetStore
          .validate(envelope)
          .flatMap {
            case Accepted =>
              if (isFilteredEvent(envelope)) {
                val offset = extractOffsetPidSeqNr(sourceProvider, envelope)
                offsetStore.saveOffset(offset)
              } else {
                loadEnvelope(envelope, sourceProvider).flatMap { loadedEnvelope =>
                  val offset = extractOffsetPidSeqNr(sourceProvider, loadedEnvelope)
                  delegate.process(loadedEnvelope).flatMap { writeItems =>
                    offsetStore.transactSaveOffset(writeItems, offset)
                  }
                }
              }
            case Duplicate =>
              FutureDone
            case RejectedSeqNr =>
              triggerReplayIfPossible(sourceProvider, offsetStore, envelope).map(_ => Done)(ExecutionContext.parasitic)
            case RejectedBacktrackingSeqNr =>
              triggerReplayIfPossible(sourceProvider, offsetStore, envelope).map {
                case true  => Done
                case false => throwRejectedEnvelope(sourceProvider, envelope)
              }
          }
      }
    }
  }

  private[projection] def adaptedHandlerForExactlyOnceGrouped[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      handlerFactory: () => DynamoDBTransactHandler[Seq[Envelope]],
      offsetStore: DynamoDBOffsetStore)(
      implicit
      ec: ExecutionContext,
      system: ActorSystem[_]): () => Handler[Seq[Envelope]] = { () =>

    new AdaptedDynamoDBTransactHandler(handlerFactory()) {
      override def process(envelopes: Seq[Envelope]): Future[Done] = {
        import DynamoDBOffsetStore.Validation._
        offsetStore.validateAll(envelopes).flatMap { isAcceptedEnvelopes =>
          val replayDone =
            Future.sequence(isAcceptedEnvelopes.map {
              case (env, RejectedSeqNr) =>
                triggerReplayIfPossible(sourceProvider, offsetStore, env).map(_ => Done)(ExecutionContext.parasitic)
              case (env, RejectedBacktrackingSeqNr) =>
                triggerReplayIfPossible(sourceProvider, offsetStore, env).map {
                  case true  => Done
                  case false => throwRejectedEnvelope(sourceProvider, env)
                }
              case _ =>
                FutureDone
            })

          replayDone.flatMap { _ =>
            val acceptedEnvelopes = isAcceptedEnvelopes.collect {
              case (env, Accepted) =>
                env
            }

            if (acceptedEnvelopes.isEmpty) {
              FutureDone
            } else {
              Future.sequence(acceptedEnvelopes.map(env => loadEnvelope(env, sourceProvider))).flatMap {
                loadedEnvelopes =>
                  val offsets = loadedEnvelopes.iterator.map(extractOffsetPidSeqNr(sourceProvider, _)).toVector
                  val filteredEnvelopes = loadedEnvelopes.filterNot(isFilteredEvent)
                  if (filteredEnvelopes.isEmpty) {
                    offsetStore.saveOffsets(offsets)
                  } else {
                    delegate.process(filteredEnvelopes).flatMap { writeItems =>
                      offsetStore.transactSaveOffsets(writeItems, offsets)
                    }
                  }
              }
            }
          }
        }
      }
    }
  }

  private[projection] def adaptedHandlerForAtLeastOnceGrouped[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      handlerFactory: () => Handler[Seq[Envelope]],
      offsetStore: DynamoDBOffsetStore)(
      implicit
      ec: ExecutionContext,
      system: ActorSystem[_]): () => Handler[Seq[Envelope]] = { () =>

    new AdaptedHandler(handlerFactory()) {
      override def process(envelopes: Seq[Envelope]): Future[Done] = {
        import DynamoDBOffsetStore.Validation._
        offsetStore.validateAll(envelopes).flatMap { isAcceptedEnvelopes =>
          val replayDone =
            Future.sequence(isAcceptedEnvelopes.map {
              case (env, RejectedSeqNr) =>
                triggerReplayIfPossible(sourceProvider, offsetStore, env).map(_ => Done)(ExecutionContext.parasitic)
              case (env, RejectedBacktrackingSeqNr) =>
                triggerReplayIfPossible(sourceProvider, offsetStore, env).map {
                  case true  => Done
                  case false => throwRejectedEnvelope(sourceProvider, env)
                }
              case _ =>
                FutureDone
            })

          replayDone.flatMap { _ =>
            val acceptedEnvelopes = isAcceptedEnvelopes.collect {
              case (env, Accepted) =>
                env
            }

            if (acceptedEnvelopes.isEmpty) {
              FutureDone
            } else {
              Future.sequence(acceptedEnvelopes.map(env => loadEnvelope(env, sourceProvider))).flatMap {
                loadedEnvelopes =>
                  val offsets = loadedEnvelopes.iterator.map(extractOffsetPidSeqNr(sourceProvider, _)).toVector
                  val filteredEnvelopes = loadedEnvelopes.filterNot(isFilteredEvent)
                  if (filteredEnvelopes.isEmpty) {
                    offsetStore.saveOffsets(offsets)
                  } else {
                    delegate.process(filteredEnvelopes).flatMap { _ =>
                      offsetStore.saveOffsets(offsets)
                    }
                  }
              }
            }
          }
        }
      }
    }
  }

  private[projection] def adaptedHandlerForFlow[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      handler: FlowWithContext[Envelope, ProjectionContext, Done, ProjectionContext, _],
      offsetStore: DynamoDBOffsetStore,
      settings: DynamoDBProjectionSettings)(
      implicit
      system: ActorSystem[_]): FlowWithContext[Envelope, ProjectionContext, Done, ProjectionContext, _] = {
    import DynamoDBOffsetStore.Validation._
    implicit val ec: ExecutionContext = system.executionContext
    FlowWithContext[Envelope, ProjectionContext]
      .mapAsync(1) { env =>
        offsetStore
          .validate(env)
          .flatMap {
            case Accepted =>
              if (isFilteredEvent(env) && settings.warnAboutFilteredEventsInFlow) {
                log.info("atLeastOnceFlow doesn't support skipping envelopes. Envelope [{}] still emitted.", env)
              }
              loadEnvelope(env, sourceProvider).map { loadedEnvelope =>
                offsetStore.addInflight(loadedEnvelope)
                Some(loadedEnvelope)
              }
            case Duplicate =>
              Future.successful(None)
            case RejectedSeqNr =>
              triggerReplayIfPossible(sourceProvider, offsetStore, env).map(_ => None)(ExecutionContext.parasitic)
            case RejectedBacktrackingSeqNr =>
              triggerReplayIfPossible(sourceProvider, offsetStore, env).map {
                case true  => None
                case false => throwRejectedEnvelope(sourceProvider, env)
              }
          }
      }
      .collect {
        case Some(env) =>
          env
      }
      .via(handler)
  }

  private def triggerReplayIfPossible[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      offsetStore: DynamoDBOffsetStore,
      envelope: Envelope)(implicit ec: ExecutionContext): Future[Boolean] = {
    envelope match {
      case env: EventEnvelope[Any @unchecked] if env.sequenceNr > 1 =>
        sourceProvider match {
          case provider: CanTriggerReplay =>
            offsetStore.storedSeqNr(env.persistenceId).map { storedSeqNr =>
              val fromSeqNr = storedSeqNr + 1
              provider.triggerReplay(env.persistenceId, fromSeqNr, env.sequenceNr)
              true
            }
          case _ =>
            FutureFalse // no replay support for other source providers
        }
      case _ =>
        FutureFalse // no replay support for non typed envelopes
    }
  }

  private def throwRejectedEnvelope[Offset, Envelope](
      sourceProvider: SourceProvider[Offset, Envelope],
      envelope: Envelope): Nothing = {
    extractOffsetPidSeqNr(sourceProvider, envelope) match {
      case OffsetPidSeqNr(_, Some((pid, seqNr))) =>
        throw new RejectedEnvelope(
          s"Rejected envelope from backtracking, persistenceId [$pid], seqNr [$seqNr] due to unexpected sequence number.")
      case OffsetPidSeqNr(_, None) =>
        throw new RejectedEnvelope(s"Rejected envelope from backtracking.")
    }
  }

  @nowarn("msg=never used")
  abstract class AdaptedHandler[E](val delegate: Handler[E])(implicit ec: ExecutionContext, system: ActorSystem[_])
      extends Handler[E] {

    override def start(): Future[Done] =
      delegate.start()

    override def stop(): Future[Done] =
      delegate.stop()
  }

  @nowarn("msg=never used")
  abstract class AdaptedDynamoDBTransactHandler[E](val delegate: DynamoDBTransactHandler[E])(
      implicit
      ec: ExecutionContext,
      system: ActorSystem[_])
      extends Handler[E] {

    override def start(): Future[Done] =
      delegate.start()

    override def stop(): Future[Done] =
      delegate.stop()
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[projection] class DynamoDBProjectionImpl[Offset, Envelope](
    val projectionId: ProjectionId,
    dynamodbSettings: DynamoDBProjectionSettings,
    settingsOpt: Option[ProjectionSettings],
    sourceProvider: SourceProvider[Offset, Envelope],
    restartBackoffOpt: Option[RestartSettings],
    val offsetStrategy: OffsetStrategy,
    handlerStrategy: HandlerStrategy,
    override val statusObserver: StatusObserver[Envelope],
    offsetStore: DynamoDBOffsetStore)
    extends scaladsl.AtLeastOnceProjection[Offset, Envelope]
    with javadsl.AtLeastOnceProjection[Offset, Envelope]
    with scaladsl.ExactlyOnceProjection[Offset, Envelope]
    with javadsl.ExactlyOnceProjection[Offset, Envelope]
    with scaladsl.GroupedProjection[Offset, Envelope]
    with javadsl.GroupedProjection[Offset, Envelope]
    with scaladsl.AtLeastOnceFlowProjection[Offset, Envelope]
    with javadsl.AtLeastOnceFlowProjection[Offset, Envelope]
    with SettingsImpl[DynamoDBProjectionImpl[Offset, Envelope]]
    with InternalProjection {
  import DynamoDBProjectionImpl.extractOffsetPidSeqNr

  private def copy(
      settingsOpt: Option[ProjectionSettings] = this.settingsOpt,
      restartBackoffOpt: Option[RestartSettings] = this.restartBackoffOpt,
      offsetStrategy: OffsetStrategy = this.offsetStrategy,
      handlerStrategy: HandlerStrategy = this.handlerStrategy,
      statusObserver: StatusObserver[Envelope] = this.statusObserver): DynamoDBProjectionImpl[Offset, Envelope] =
    new DynamoDBProjectionImpl(
      projectionId,
      dynamodbSettings,
      settingsOpt,
      sourceProvider,
      restartBackoffOpt,
      offsetStrategy,
      handlerStrategy,
      statusObserver,
      offsetStore)

  type ReadOffset = () => Future[Option[Offset]]

  /*
   * Build the final ProjectionSettings to use, if currently set to None fallback to values in config file
   */
  private def settingsOrDefaults(implicit system: ActorSystem[_]): ProjectionSettings = {
    val settings = settingsOpt.getOrElse(ProjectionSettings(system))
    restartBackoffOpt match {
      case None    => settings
      case Some(r) => settings.copy(restartBackoff = r)
    }
  }

  override def withRestartBackoffSettings(restartBackoff: RestartSettings): DynamoDBProjectionImpl[Offset, Envelope] =
    copy(restartBackoffOpt = Some(restartBackoff))

  override def withSaveOffset(
      afterEnvelopes: Int,
      afterDuration: FiniteDuration): DynamoDBProjectionImpl[Offset, Envelope] =
    copy(offsetStrategy = offsetStrategy
      .asInstanceOf[AtLeastOnce]
      .copy(afterEnvelopes = Some(afterEnvelopes), orAfterDuration = Some(afterDuration)))

  override def withGroup(
      groupAfterEnvelopes: Int,
      groupAfterDuration: FiniteDuration): DynamoDBProjectionImpl[Offset, Envelope] =
    copy(handlerStrategy = handlerStrategy
      .asInstanceOf[GroupedHandlerStrategy[Envelope]]
      .copy(afterEnvelopes = Some(groupAfterEnvelopes), orAfterDuration = Some(groupAfterDuration)))

  override def withRecoveryStrategy(
      recoveryStrategy: HandlerRecoveryStrategy): DynamoDBProjectionImpl[Offset, Envelope] = {
    val newStrategy = offsetStrategy match {
      case s: ExactlyOnce           => s.copy(recoveryStrategy = Some(recoveryStrategy))
      case s: AtLeastOnce           => s.copy(recoveryStrategy = Some(recoveryStrategy))
      case s: OffsetStoredByHandler => s.copy(recoveryStrategy = Some(recoveryStrategy))
      //NOTE: AtMostOnce has its own withRecoveryStrategy variant
      // this method is not available for AtMostOnceProjection
      case s: AtMostOnce => s
    }
    copy(offsetStrategy = newStrategy)
  }

  override def withStatusObserver(observer: StatusObserver[Envelope]): DynamoDBProjectionImpl[Offset, Envelope] =
    copy(statusObserver = observer)

  private[projection] def actorHandlerInit[T]: Option[ActorHandlerInit[T]] =
    handlerStrategy.actorHandlerInit

  /**
   * INTERNAL API Return a RunningProjection
   */
  override private[projection] def run()(implicit system: ActorSystem[_]): RunningProjection =
    new DynamoDBInternalProjectionState(settingsOrDefaults).newRunningInstance()

  /**
   * INTERNAL API
   *
   * This method returns the projection Source mapped with user 'handler' function, but before any sink attached. This
   * is mainly intended to be used by the TestKit allowing it to attach a TestSink to it.
   */
  override private[projection] def mappedSource()(implicit system: ActorSystem[_]): Source[Done, Future[Done]] =
    new DynamoDBInternalProjectionState(settingsOrDefaults).mappedSource()

  private class DynamoDBInternalProjectionState(settings: ProjectionSettings)(implicit val system: ActorSystem[_])
      extends InternalProjectionState[Offset, Envelope](
        projectionId,
        sourceProvider,
        offsetStrategy,
        handlerStrategy,
        statusObserver,
        settings) {

    implicit val executionContext: ExecutionContext = system.executionContext
    override val logger: LoggingAdapter = Logging(system.classicSystem, classOf[DynamoDBProjectionImpl[_, _]])

    private val isExactlyOnceWithSkip: Boolean =
      offsetStrategy match {
        case ExactlyOnce(Some(Skip)) | ExactlyOnce(Some(_: RetryAndSkip)) => true
        case _                                                            => false
      }

    override def readPaused(): Future[Boolean] =
      offsetStore.readManagementState().map(_.exists(_.paused))

    override def readOffsets(): Future[Option[Offset]] =
      offsetStore.readOffset()

    // Called from InternalProjectionState.saveOffsetAndReport
    override def saveOffset(projectionId: ProjectionId, offset: Offset): Future[Done] = {
      // need the envelope to be able to call offsetStore.saveOffset
      throw new IllegalStateException(
        "Unexpected call to saveOffset. It should have called saveOffsetAndReport. Please report bug at https://github.com/akka/akka-projection/issues")
    }

    override protected def saveOffsetAndReport(
        projectionId: ProjectionId,
        projectionContext: ProjectionContextImpl[Offset, Envelope],
        batchSize: Int): Future[Done] = {
      import DynamoDBProjectionImpl.FutureDone
      val envelope = projectionContext.envelope

      if (offsetStore.isInflight(envelope) || isExactlyOnceWithSkip) {
        val offset = extractOffsetPidSeqNr(projectionContext.offset, envelope)
        offsetStore
          .saveOffset(offset)
          .map { done =>
            try {
              statusObserver.offsetProgress(projectionId, envelope)
            } catch {
              case NonFatal(_) => // ignore
            }
            getTelemetry().onOffsetStored(batchSize)
            done
          }

      } else {
        FutureDone
      }
    }

    override protected def saveOffsetsAndReport(
        projectionId: ProjectionId,
        batch: Seq[ProjectionContextImpl[Offset, Envelope]]): Future[Done] = {
      import DynamoDBProjectionImpl.FutureDone

      val acceptedContexts =
        if (isExactlyOnceWithSkip)
          batch.toVector
        else {
          batch.iterator.filter { ctx =>
            val env = ctx.envelope
            offsetStore.isInflight(env)
          }.toVector
        }

      if (acceptedContexts.isEmpty) {
        FutureDone
      } else {
        val offsets = acceptedContexts.map(ctx => extractOffsetPidSeqNr(ctx.offset, ctx.envelope))
        offsetStore
          .saveOffsets(offsets)
          .map { done =>
            val batchSize = acceptedContexts.map { _.groupSize }.sum
            val last = acceptedContexts.last
            try {
              statusObserver.offsetProgress(projectionId, last.envelope)
            } catch {
              case NonFatal(_) => // ignore
            }
            getTelemetry().onOffsetStored(batchSize)
            done
          }
      }
    }

    private[projection] def newRunningInstance(): RunningProjection =
      new DynamoDBRunningProjection(RunningProjection.withBackoff(() => this.mappedSource(), settings), this)
  }

  private class DynamoDBRunningProjection(source: Source[Done, _], projectionState: DynamoDBInternalProjectionState)(
      implicit system: ActorSystem[_])
      extends RunningProjection
      with RunningProjectionManagement[Offset] {

    private val streamDone = source.run()

    override def stop(): Future[Done] = {
      projectionState.killSwitch.shutdown()
      // if the handler is retrying it will be aborted by this,
      // otherwise the stream would not be completed by the killSwitch until after all retries
      projectionState.abort.failure(AbortProjectionException)
      streamDone
    }

    // RunningProjectionManagement
    override def getOffset(): Future[Option[Offset]] = {
      offsetStore.getOffset()
    }

    // RunningProjectionManagement
    override def setOffset(offset: Option[Offset]): Future[Done] = {
      // FIXME
      // offset match {
      //  case Some(o) => offsetStore.managementSetOffset(o)
      //  case None    => offsetStore.managementClearOffset()
      //}
      ???
    }

    // RunningProjectionManagement
    override def getManagementState(): Future[Option[ManagementState]] =
      offsetStore.readManagementState()

    // RunningProjectionManagement
    override def setPaused(paused: Boolean): Future[Done] =
      offsetStore.savePaused(paused)
  }

}
