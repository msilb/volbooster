# Long Volatility Trading Strategy :zap:VolBooster:zap:

## Overview

This is a sample long vol algorithmic trading strategy that is using [scalanda](https://github.com/msilb/scalanda) for connecting to Oanda broker. It is given as a sample for using the Oanda API. It contains all necessary config files to be deployed on [heroku](https://www.heroku.com). It is based on [Akka FSM](http://doc.akka.io/docs/akka/current/scala/fsm.html) for modeling state of the strategy. There is *NO* warranty whatsoever for any trading losses incurred while using this strategy, use at your own risk.

## Algorithm Description

The algorithm itself is quite simple and is mostly focused on trading FX and precious metals around news announcements. Basic idea is that we know that when the unexpected news hits the market, volatility rises. Usually such action is observed at the time of major events taking place, such as central bank announcements or release of [NFP](https://en.wikipedia.org/wiki/Nonfarm_payrolls) numbers. To capture this volatility we could either enter a long option contract, for which we would have to pay a premium, or we can attempt to replicate the short-term option payoff using spot trading, which is what we are focusing on here. Here are the main steps carried out by the algorithm:

1. Program timer to start trading strategy after a news announcement (e.g. ECB conference tomorrow at 2pm CET).
2. Start monitoring the spreads for the instrument(s) we want to trade (usually Oanda increases the spreads when there is a surprise announcement, and we don't want to be trading in that environment).
3. Once the spreads come down, we fetch the candlestick prices for the previous interval, i.e. if we are operating our trading strategy in 5min interval mode and current time is 2.30pm, we fetch the 5min candle spanning 2.25pm-2.30pm.
4. We place exactly 2 stop orders: buy order at the high and sell order at the low of the previous candlestick.
5. If none of the orders is hit during this interval, we update the stop orders by moving them to the new high/low of the previous candle.
6. If one of the orders gets hit, we update the opposite order by increasing the size x2, such that in case of a reversal we remain invested (just in the opposite direction).
7. Every interval (e.g. every 5min) we update the open order by moving it to the high/low of the previous candle if it's buy or sell order, respectively.
8. If the single open stop order is hit, we need to place a new one of size x2 at the high/low of the previous candle.
9. Rinse and repeat until our stop timer is triggered, which closes all open positions and orders and kills the algo!

Pretty simple, no?

Ok, here is a small example. Say, we have ECB's Draghi speaking tomorrow at 2pm CET in Frankfurt. We expect he might announce a surprise rate cut/hike or say something that might send EUR/USD, USD/JPY or XAU/USD either plummeting or skyrocketing, but we're not sure about the direction. Fret not, :zap:VolBooster:zap: to the rescue! We start off by defining our `VolBoosterConfigs` in `Trader.scala`:
```scala
val VolBoosterConfigs = Set(
    VolBooster.Config("EUR_USD", 185000, M5, 12, 40, 13, 40, maxSpread = 0.00035),
    VolBooster.Config("USD_JPY", 200000, M5, 12, 40, 13, 40, maxSpread = 0.035),
    VolBooster.Config("XAU_USD", 110, M5, 12, 40, 13, 40, maxSpread = 0.6)
  )
```
As you can see we define a set of 3 instruments with their respective sizes and spread thresholds to start trading at 12:40 and stop trading at 13:40, i.e. we trade all of them for exactly one hour. Each config entry creates a separate instance of `VolBooster` and all of them operate independently of each other.

A few words on the spread threshold or `maxSpread` we have defined above. As you can see all of them are different and probably have to be because each currency pair has a different _typical_ spread. For example, Oanda's typical spread in calm market periods is about 1-2pips (0.0001-0.0002). We say, anything less than 3.5 pips we consider _back to normal_ and can start trading.

One picture says more than 1000 words and here is a sample chart showing how the first trade might be entered and closed when running `VolBooster`:

![Sample chart displaying trading strategy `VolBooster`](images/volbooster_sample.png)

As you can see we would have made 0.15% on this trade.

Happy trading! :four_leaf_clover:

## Contributing

1. Fork it!
2. Create your feature branch: git checkout -b my-new-feature
3. Commit your changes: git commit -am 'Add some feature'
4. Push to the branch: git push origin my-new-feature
5. Submit a pull request :D

## License

[MIT License](LICENSE)
