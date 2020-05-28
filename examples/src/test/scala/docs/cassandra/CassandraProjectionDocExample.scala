/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.cassandra

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
//#daemon-imports
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.projection.ProjectionBehavior

//#daemon-imports

//#source-provider-imports
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import docs.eventsourced.ShoppingCart

//#source-provider-imports

//#projection-imports
import akka.projection.ProjectionId
import akka.projection.cassandra.scaladsl.CassandraProjection

//#projection-imports

//#projection-settings-imports
import scala.concurrent.duration._

import akka.projection.ProjectionSettings
//#projection-settings-imports

//#handler-imports
import scala.concurrent.Future

import akka.Done
import akka.projection.scaladsl.Handler
import org.slf4j.LoggerFactory

//#handler-imports

object CassandraProjectionDocExample {

  private val system = ActorSystem[Nothing](Behaviors.empty, "Example")

  //#handler
  class ShoppingCartHandler extends Handler[EventEnvelope[ShoppingCart.Event]] {
    private val logger = LoggerFactory.getLogger(getClass)

    override def process(envelope: EventEnvelope[ShoppingCart.Event]): Future[Done] = {
      envelope.event match {
        case ShoppingCart.CheckedOut(cartId, time) =>
          logger.info("Shopping cart {} was checked out at {}", cartId, time)
          Future.successful(Done)

        case otherEvent =>
          logger.debug("Shopping cart {} changed by {}", otherEvent.cartId, otherEvent)
          Future.successful(Done)
      }
    }
  }
  //#handler

  //#sourceProvider
  val sourceProvider =
    EventSourcedProvider
      .eventsByTag[ShoppingCart.Event](system, readJournalPluginId = CassandraReadJournal.Identifier, tag = "carts-1")
  //#sourceProvider

  object IllustrateAtLeastOnce {
    //#atLeastOnce
    val projection =
      CassandraProjection
        .atLeastOnce(
          projectionId = ProjectionId("shopping-carts", "carts-1"),
          sourceProvider,
          handler = new ShoppingCartHandler)
        .withSaveOffset(100, 500.millis)
    //#atLeastOnce
  }

  object IllustrateAtMostOnce {
    //#atMostOnce
    val projection =
      CassandraProjection.atMostOnce(
        projectionId = ProjectionId("shopping-carts", "carts-1"),
        sourceProvider,
        handler = new ShoppingCartHandler)
    //#atMostOnce
  }

  object IllustrateRunningWithShardedDaemon {

    //#running-source-provider
    def sourceProvider(tag: String) =
      EventSourcedProvider
        .eventsByTag[ShoppingCart.Event](
          systemProvider = system,
          readJournalPluginId = CassandraReadJournal.Identifier,
          tag = tag)
    //#running-source-provider

    //#running-projection
    def projection(tag: String) =
      CassandraProjection
        .atLeastOnce(
          projectionId = ProjectionId("shopping-carts", tag),
          sourceProvider(tag),
          handler = new ShoppingCartHandler)
        .withSaveOffset(100, 500.millis)
    //#running-projection

    //#running-with-daemon-process
    ShardedDaemonProcess(system).init[ProjectionBehavior.Command](
      name = "shopping-carts",
      numberOfInstances = ShoppingCart.tags.size,
      behaviorFactory = n => ProjectionBehavior(projection(ShoppingCart.tags(n))),
      settings = ShardedDaemonProcessSettings(system),
      stopMessage = Some(ProjectionBehavior.Stop))
    //#running-with-daemon-process
  }

  object IllustrateProjectionSettings {

    //#projection-settings
    val projection =
      CassandraProjection
        .atLeastOnce(
          projectionId = ProjectionId("shopping-carts", "carts-1"),
          sourceProvider,
          handler = new ShoppingCartHandler)
        .withSettings(ProjectionSettings(system)
          .withBackoff(minBackoff = 10.seconds, maxBackoff = 60.seconds, randomFactor = 0.5))
        .withSaveOffset(100, 500.millis)
    //#projection-settings

  }

}