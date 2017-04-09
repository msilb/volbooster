package com.msilb.trading.live

object Main extends App {
  akka.Main.main(Array(classOf[TradeInitializer].getName))
}
