package com.msilb.trading.live.util

import com.msilb.scalanda.common.model.Side.{Buy, Sell}
import com.msilb.scalanda.common.model.Transaction.OrderFilled
import com.msilb.scalanda.restapi.model.Candle

object Utils {
  def getPrevious[T <: Candle](first: T, second: T) = if (second.complete) second else first

  def isOrderFilledForInstrument(t: OrderFilled, instrument: String) = t.instrument.contains(instrument) && t.tradeOpened.isDefined

  def isBuyOrderFilledForInstrument(t: OrderFilled, instrument: String) = isOrderFilledForInstrument(t, instrument) && t.side == Buy

  def isSellOrderFilledForInstrument(t: OrderFilled, instrument: String) = isOrderFilledForInstrument(t, instrument) && t.side == Sell
}
