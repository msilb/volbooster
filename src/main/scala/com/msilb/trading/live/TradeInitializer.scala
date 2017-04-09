package com.msilb.trading.live

import akka.actor.FSM.{SubscribeTransitionCallBack, Transition, UnsubscribeTransitionCallBack}
import akka.actor.{Actor, ActorLogging, Props}
import com.msilb.trading.live.Trader.Start
import com.msilb.trading.live.Trader.State.Trading

class TradeInitializer extends Actor with ActorLogging {

  private val trader = context.actorOf(Props[Trader], "trader")
  trader ! SubscribeTransitionCallBack(self)
  trader ! Start

  def receive = {
    case Transition(fsm, _, Trading) =>
      fsm ! UnsubscribeTransitionCallBack(self)
      log.info("Connected to Oanda REST API, start trading now...")
  }
}
