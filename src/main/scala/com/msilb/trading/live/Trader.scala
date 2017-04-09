package com.msilb.trading.live

import akka.actor._
import akka.pattern.{ask, pipe}
import com.msilb.scalanda.common.Environment.Practice
import com.msilb.scalanda.restapi.RestConnector
import com.msilb.scalanda.restapi.model.Granularity.M5
import com.msilb.scalanda.streamapi.StreamingConnector
import com.msilb.scalanda.streamapi.StreamingConnector.{AddListeners, Connect, ConnectionEstablished, StartEventsStreaming}
import com.msilb.trading.live.Trader.Data.{ActiveActors, Empty}
import com.msilb.trading.live.Trader.State.{Connecting, Idle, Trading}
import com.msilb.trading.live.Trader._
import com.msilb.trading.live.volbooster.VolBooster

import scala.concurrent.duration._

object Trader {

  val accountId = 1234567

  val authTokenPractice = "YOUR-AUTH-BEARER-TOKEN"

  val VolBoosterConfigs = Set(
    VolBooster.Config("EUR_USD", 185000, M5, 13, 30, 14, 30, maxSpread = 0.00035),
    VolBooster.Config("USD_JPY", 200000, M5, 13, 30, 14, 30, maxSpread = 0.035),
    VolBooster.Config("XAU_USD", 110, M5, 13, 30, 14, 30, maxSpread = 0.6)
  )

  case object Initialize

  case object Start

  case object Stop

  sealed trait State

  case object State {

    case object Idle extends State

    case object Connecting extends State

    case object Trading extends State

  }

  sealed trait Data

  case object Data {

    case object Empty extends Data

    case class ActiveActors(actors: Set[ActorRef]) extends Data

  }

}

class Trader extends FSM[State, Data] {

  import context._

  startWith(Idle, Empty)

  when(Idle) {
    case Event(Start, _) =>
      log.info("Starting...")
      val volBoosterConnector = actorOf(RestConnector.props(Practice, Some(authTokenPractice), accountId), "volBoosterConnector")
      val volBoosterActors = VolBoosterConfigs.zipWithIndex.map {
        case (cfg, i) =>
          actorOf(VolBooster.props(volBoosterConnector, cfg), s"volBooster_${cfg.instrument}_${cfg.granularity}_$i")
      }

      volBoosterActors.zipWithIndex.foreach {
        case (actor, delay) => system.scheduler.scheduleOnce(delay.seconds, actor, Initialize)
      }

      val streamingConnector = actorOf(StreamingConnector.props, "streamingConnector")
      ask(streamingConnector, Connect(Practice, Some(authTokenPractice)))(5.seconds).map(res => (res, streamingConnector, volBoosterActors)).pipeTo(self)
      goto(Connecting) using ActiveActors(volBoosterActors ++ Set(volBoosterConnector) ++ Set(streamingConnector))
  }

  when(Connecting) {
    case Event((ConnectionEstablished, streamingConnector: ActorRef, volBoosterActors: Set[ActorRef]), _) =>
      streamingConnector ! AddListeners(volBoosterActors)
      streamingConnector ! StartEventsStreaming(Some(Set(accountId)))
      goto(Trading)
  }

  when(Trading) {
    case Event(Stop, ActiveActors(actors)) =>
      log.info("Shutting down...")
      actors.foreach(_ ! PoisonPill)
      goto(Idle) using Empty
  }
}
