package com.msilb.trading.live.volbooster

import java.text.DecimalFormat
import java.time.temporal.ChronoUnit._
import java.time.{ZoneId, ZonedDateTime}

import akka.actor.{ActorRef, FSM, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.msilb.scalanda.common.model.Side.{Buy, Sell}
import com.msilb.scalanda.common.model.Transaction.OrderFilled
import com.msilb.scalanda.common.model.{Side, Transaction}
import com.msilb.scalanda.restapi.Request._
import com.msilb.scalanda.restapi.Response
import com.msilb.scalanda.restapi.Response._
import com.msilb.scalanda.restapi.model.Candle.BidAskBasedCandle
import com.msilb.scalanda.restapi.model.CandleFormat.BidAsk
import com.msilb.scalanda.restapi.model.Granularity.{D, H1}
import com.msilb.scalanda.restapi.model.OrderType.Market
import com.msilb.scalanda.restapi.model.{Granularity, OrderType}
import com.msilb.trading.live.Trader.Initialize
import com.msilb.trading.live.util.Utils._
import com.msilb.trading.live.volbooster.VolBooster.Data.CurrentData
import com.msilb.trading.live.volbooster.VolBooster.State.{CheckingSpreads, Active, Idle, Uninitialized}
import com.msilb.trading.live.volbooster.VolBooster._

import scala.concurrent.Await
import scala.concurrent.duration._

object VolBooster {

  private val FetchCandlesTimerName = "candleTimer"
  private val SafeGuardTimerName = "safeGuardTimer"

  def props(connector: ActorRef, cfg: Config) = Props(new VolBooster(connector, cfg))

  sealed trait State

  sealed trait Data

  case class Config(instrument: String,
                    size: Long,
                    granularity: Granularity,
                    startHour: Int,
                    startMin: Int,
                    endHour: Int,
                    endMin: Int,
                    oneOff: Boolean = false,
                    untilFirstProfit: Boolean = false,
                    maxSpread: Double = 0.0002)

  case object Start

  case object Stop

  case object FetchCandles

  case object CheckSize

  object State {

    case object Uninitialized extends State

    case object Idle extends State

    case object CheckingSpreads extends State

    case object Active extends State

  }

  object Data {

    case class CurrentData(previousCandle: Option[BidAskBasedCandle] = None, longOrderId: Option[Long] = None, shortOrderId: Option[Long] = None, tradeId: Option[Long] = None) extends Data

  }

}

class VolBooster(connector: ActorRef, cfg: Config) extends FSM[State, Data] {

  implicit val timeout = Timeout(5.seconds)

  import context.dispatcher

  private def fetchCandles() = connector ! GetCandlesRequest(instrument = cfg.instrument, count = Some(2), granularity = Some(cfg.granularity), candleFormat = Some(BidAsk))

  private def createOrder(size: Long, side: Side, price: Double) = connector ! CreateOrderRequest(instrument = cfg.instrument, units = size, side = side, typ = OrderType.Stop, expiry = Some(ZonedDateTime.now(ZoneId.of("Z")).plusWeeks(2)), price = Some(price))

  private def modifyOrder(id: Long, price: Option[Double] = None, units: Option[Long] = None) = connector ! ModifyOrderRequest(id = id, price = price, units = units)

  val formatter = new DecimalFormat("#####.#######")

  startWith(Uninitialized, CurrentData())

  when(Uninitialized) {
    case Event(Initialize, _) =>
      val now = ZonedDateTime.now(ZoneId.of("Z"))
      val start = now.withHour(cfg.startHour).withMinute(cfg.startMin).withSecond(5).withNano(0)
      val stop = now.withHour(cfg.endHour).withMinute(cfg.endMin).withSecond(55).withNano(0)
      val startDelay = MILLIS.between(now, if (now.isBefore(start)) start else start.plusDays(1))
      val stopDelay = MILLIS.between(now, if (now.isBefore(stop)) stop else stop.plusDays(1))
      if (cfg.oneOff) {
        context.system.scheduler.scheduleOnce(startDelay.millis, self, Start)
        context.system.scheduler.scheduleOnce(stopDelay.millis, self, Stop)
      } else {
        context.system.scheduler.schedule(startDelay.millis, 1.day, self, Start)
        context.system.scheduler.schedule(stopDelay.millis, 1.day, self, Stop)
      }

      val tradeOpt = Await
        .result(connector ? GetOpenTradesRequest(instrument = Some(cfg.instrument), count = Some(1)), timeout.duration)
        .asInstanceOf[GetOpenTradesResponse]
        .trades
        .headOption
      val orders = Await
        .result(connector ? GetOrdersRequest(instrument = Some(cfg.instrument), count = Some(2)), timeout.duration)
        .asInstanceOf[GetOrdersResponse]
        .orders
      val initialCandles = Await
        .result(connector ? GetCandlesRequest(cfg.instrument, count = Some(2), granularity = Some(cfg.granularity), candleFormat = Some(BidAsk)), timeout.duration)
        .asInstanceOf[CandleResponse[BidAskBasedCandle]]
        .candles

      val initialState = if (tradeOpt.isDefined || orders.nonEmpty) Active else Idle
      val initialData = CurrentData(if (initialState == Active) Some(getPrevious(initialCandles.head, initialCandles(1))) else None, orders.find(_.side == Buy).map(_.id), orders.find(_.side == Sell).map(_.id), tradeOpt.map(_.id))

      log.info("Initialized VolBooster with instrument/size {}/{} in state/data {}/{}", cfg.instrument, cfg.size, initialState, initialData)

      goto(initialState) using initialData
  }

  when(Idle) {
    case Event(Start, _) =>
      fetchCandles()
      setTimer(FetchCandlesTimerName, FetchCandles, if (cfg.granularity == H1 || cfg.granularity == D) 5.seconds else 2.seconds, repeat = true)
      setTimer(SafeGuardTimerName, CheckSize, 1.minute, repeat = true)
      goto(CheckingSpreads)
  }

  when(CheckingSpreads) {
    case Event(CandleResponse(_, _, candles: Seq[BidAskBasedCandle]), _) =>
      val currentCandle = candles.head
      val currentSpread = currentCandle.closeAsk - currentCandle.closeBid
      log.info(s"Current spread: ${formatter.format(currentSpread)}")
      if (currentSpread < cfg.maxSpread) {
        goto(Active)
      } else {
        stay()
      }
  }

  when(Active) {
    case Event(Stop, CurrentData(_, longOrderOpt, shortOrderOpt, tradeIdOpt)) =>
      cancelTimer(FetchCandlesTimerName)
      cancelTimer(SafeGuardTimerName)
      longOrderOpt.foreach(connector ! CloseOrderRequest(_))
      shortOrderOpt.foreach(connector ! CloseOrderRequest(_))
      tradeIdOpt.foreach(connector ! CloseTradeRequest(_))
      goto(Idle) using CurrentData()
    case Event(CandleResponse(_, _, candles: Seq[BidAskBasedCandle]), data@CurrentData(previousCandleOpt, longOrderIdOpt, shortOrderIdOpt, tradeIdOpt)) if !previousCandleOpt.contains(getPrevious(candles.head, candles(1))) =>
      val previous = getPrevious(candles.head, candles(1))
      longOrderIdOpt match {
        case Some(id) => modifyOrder(id, price = Some(previous.highAsk))
        case None => if (tradeIdOpt.isEmpty) createOrder(cfg.size, Buy, previous.highAsk)
      }
      shortOrderIdOpt match {
        case Some(id) => modifyOrder(id, price = Some(previous.lowBid))
        case None => if (tradeIdOpt.isEmpty) createOrder(cfg.size, Sell, previous.lowBid)
      }
      stay() using data.copy(previousCandle = Some(previous))
    case Event(createOrderResponse: CreateOrderResponse, data: CurrentData) =>
      val orderOpened = createOrderResponse.orderOpened.get
      if (orderOpened.side == Buy)
        stay() using data.copy(longOrderId = Some(orderOpened.id))
      else
        stay() using data.copy(shortOrderId = Some(orderOpened.id))
    case Event(modifyOrderResponse: OrderResponse, data: CurrentData) =>
      if (modifyOrderResponse.side == Buy)
        stay() using data.copy(longOrderId = Some(modifyOrderResponse.id))
      else
        stay() using data.copy(shortOrderId = Some(modifyOrderResponse.id))
    case Event(t: OrderFilled, data@CurrentData(previous, longOrderIdOpt, shortOrderIdOpt, _)) if isOrderFilledForInstrument(t, cfg.instrument) =>
      val doubleSize = cfg.size * 2
      val newData = data.copy(tradeId = Some(t.tradeOpened.map(_.id).get))
      if (t.side == Buy) {
        if (t.pl > 0 && cfg.untilFirstProfit) {
          self ! Stop
        } else {
          shortOrderIdOpt match {
            case Some(id) => modifyOrder(id, units = Some(doubleSize))
            case None => createOrder(doubleSize, Sell, previous.get.lowBid)
          }
        }
        stay() using newData.copy(longOrderId = None)
      } else {
        if (t.pl > 0 && cfg.untilFirstProfit) {
          self ! Stop
        } else {
          longOrderIdOpt match {
            case Some(id) => modifyOrder(id, units = Some(doubleSize))
            case None => createOrder(doubleSize, Buy, previous.get.highAsk)
          }
        }
        stay() using newData.copy(shortOrderId = None)
      }
  }

  whenUnhandled {
    case Event(CheckSize, _) =>
      log.debug("Checking size on volbooster")
      val trade = Await.result(connector ? GetOpenTradesRequest(instrument = Some(cfg.instrument), count = Some(1)), timeout.duration).asInstanceOf[GetOpenTradesResponse].trades.headOption
      trade.filter(_.units > cfg.size).foreach { t =>
        log.warning("Inconsistency found! Expected trade size for {}: {}, currently open trade: {}", cfg.instrument, cfg.size, t)
        connector ! CreateOrderRequest(cfg.instrument, t.units - cfg.size, if (t.side == Buy) Sell else Buy, Market)
      }
      stay()
    case Event(FetchCandles, _) =>
      fetchCandles()
      stay()
    case Event(CandleResponse(_, _, _), _) =>
      stay()
    case Event(t: Transaction, _) =>
      log.debug("Received transaction: {}", t)
      stay()
    case Event(resp: Response, _) =>
      log.debug("Received response: {}", resp)
      stay()
  }

  initialize()
}
