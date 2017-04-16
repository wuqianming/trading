@Grapes([
        @Grab("org.oxerr:okcoin-client-rest:3.0.0"),
        @Grab("org.slf4j:slf4j-log4j12:1.7.21"),
])
import groovy.json.JsonSlurper
import org.knowm.xchange.currency.Currency
import org.knowm.xchange.dto.Order
import org.knowm.xchange.dto.trade.LimitOrder
import org.knowm.xchange.okcoin.OkCoinExchange

import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

import org.apache.log4j.PropertyConfigurator
import org.knowm.xchange.ExchangeSpecification
import org.knowm.xchange.currency.CurrencyPair
import org.knowm.xchange.okcoin.OkCoinExchange
//import org.oxerr.okcoin.rest.OKCoinExchange
//import org.oxerr.okcoin.rest.dto.OrderData
//import org.oxerr.okcoin.rest.dto.Status
//import org.oxerr.okcoin.rest.dto.Trade
//import org.oxerr.okcoin.rest.dto.Type
//import org.oxerr.okcoin.rest.service.polling.OKCoinAccountService
//import org.oxerr.okcoin.rest.service.polling.OKCoinMarketDataService
//import org.oxerr.okcoin.rest.service.polling.OKCoinTradeService
import org.slf4j.LoggerFactory

class Trading {
    def logger = LoggerFactory.getLogger(Trading.class)
    def cfg

    Trading(cfg) {
        this.cfg = cfg
    }

    def createExchange(apiKey, secKey) {
        def exchange = new OkCoinExchange()
        def exchangeSpec = new ExchangeSpecification(exchange.class)
        exchangeSpec.setApiKey(apiKey)
        exchangeSpec.setSecretKey(secKey)
        exchangeSpec.setExchangeSpecificParametersItem("Use_Intl",true)
        exchange.applySpecification(exchangeSpec)
        return exchange
    }

    def ignoreException = {Closure f -> try {f()} catch (all) {}}
    def start() {
        def accountExchange = createExchange(cfg.account.apikey, cfg.account.seckey)
//        def tradeExchange = createExchange(cfg.trade.apikey, cfg.trade.seckey)
//        def account = accountExchange.pollingAccountService
//        def market = accountExchange.pollingMarketDataService
        def trader1 = accountExchange.getTradeService()
        def trader2 = accountExchange.getTradeService()

        def threadExecutor = Executors.newCachedThreadPool() as ThreadPoolExecutor
        def trading = false

        // 更新历史交易数据，用于计算成交量
        def trades
        def lastTradeId
        def vol = 0
        def updateTrades = {
            trades = accountExchange.getMarketDataService().getTrades(CurrencyPair.BTC_USD)
            vol = 0.7 * vol + 0.3 * trades.sum(0.0) {
                it.tid > lastTradeId ? it.amount : 0
            }  // 本次tick交易量 = 上次tick交易量*0.7 + 本次tick期间实际发生的交易量*0.3，用于平滑和减少噪音
            lastTradeId = trades[-1].tid
        }
        updateTrades()

        // 更新盘口数据，用于计算价格
        def orderBook
        def prices = trades.price
        def bidPrice
        def askPrice
        orderBook = accountExchange.getMarketDataService().getOrderBook(CurrencyPair.BTC_USD)
        def updateOrderBook = {


            // 计算提单价格
            bidPrice = orderBook.bids[0].limitPrice * 0.618 + orderBook.asks[0].limitPrice * 0.382 + 0.01
            askPrice = orderBook.bids[0].limitPrice * 0.382 + orderBook.asks[0].limitPrice * 0.618 - 0.01

            // 更新时间价格序列
            //  本次tick价格 = (买1+卖1)*0.35 + (买2+卖2) * 0.10 + (买3+卖3)*0.05
            prices = prices[1 .. -1] + [(
                                                (orderBook.bids[0].limitPrice + orderBook.asks[0].limitPrice) / 2 * 0.7 +
                                                        (orderBook.bids[1].limitPrice + orderBook.asks[1].limitPrice) / 2 * 0.2 +
                                                        (orderBook.bids[2].limitPrice + orderBook.asks[2].limitPrice) / 2 * 0.1)]
        }
        updateOrderBook()

        // 更新仓位
        def userInfo
        def btc
        def cny
        def p = 0.5
        threadExecutor.execute {
            while (true) {
                if (trading) {
                    sleep 5
                    continue
                }
                def t = System.currentTimeMillis()
                ignoreException {
                    // 这里有一个仓位平衡的辅助策略
                    //  仓位平衡策略是在仓位偏离50%时，通过不断提交小单来使仓位回归50%的策略，
                    //  这个辅助策略可以有效减少趋势策略中趋势反转+大滑点带来的大幅回撤
                    def orders = (
                            p < 0.48 ? {
//                                cny -= 300.0
                                trader2.placeLimitOrder(new LimitOrder(Order.OrderType.BID,0.01,CurrencyPair.BTC_USD,"",null,orderBook.bids[0].limitPrice + 0.00))
                                trader2.placeLimitOrder(new LimitOrder(Order.OrderType.BID,0.01,CurrencyPair.BTC_USD,"",null,orderBook.bids[0].limitPrice + 0.01))
                                trader2.placeLimitOrder(new LimitOrder(Order.OrderType.BID,0.01,CurrencyPair.BTC_USD,"",null,orderBook.bids[0].limitPrice + 0.02))

                            }() :
                            p > 0.52 ? {
//                                btc -= 0.030
                                trader2.placeLimitOrder(new LimitOrder(Order.OrderType.ASK,0.01,CurrencyPair.BTC_USD,"",null,orderBook.bids[0].limitPrice - 0.00))
                                trader2.placeLimitOrder(new LimitOrder(Order.OrderType.ASK,0.01,CurrencyPair.BTC_USD,"",null,orderBook.bids[0].limitPrice - 0.01))
                                trader2.placeLimitOrder(new LimitOrder(Order.OrderType.ASK,0.01,CurrencyPair.BTC_USD,"",null,orderBook.bids[0].limitPrice - 0.02))

                            }() :
                                    null)


                    if (orders != null) {
                        sleep 400
                        trader2.cancelOrder(orders)
                    }
                }
                while (System.currentTimeMillis() - t < 500) {
                    sleep 5
                }
            }
        }

        // 定时扫描、取消失效的旧订单
        //  策略执行中难免会有不能成交、取消失败遗留下来的旧订单，
        //  定时取消掉这些订单防止占用资金
        threadExecutor.execute {
            while (true) {
                ignoreException {
                    trader2.openOrders.openOrders
                            .grep {it.timestamp.time - System.currentTimeMillis() < -10000}  // orders before 10s
                            .each {
                        trader2.cancelOrder(it.id)
                    }
                }
                sleep 60000
            }
        }
        userInfo = accountExchange.getAccountService().accountInfo
        btc = userInfo.wallet.getBalance(Currency.BTC).available
        cny = userInfo.wallet.getBalance(Currency.USD).available
        p = btc * prices[-1] / (btc * prices[-1] + cny)
        // main loop
        def ts1 = 0
        def ts0 = 0

        for (def numTick = 0; ; numTick++) {
            while (System.currentTimeMillis() - ts0 < cfg.tick.interval) {
                sleep 5
            }
            trading = false
            ts1 = ts0
            ts0 = System.currentTimeMillis()

            try {
                updateTrades()
                updateOrderBook()

//                logger.info("tick: ${ts0-ts1}, {}, net: {}, total: {}, p: {} - {}/{}, v: {}",
                    logger.info("tick: ${ts0-ts1}, {}, p: {} - {}/{}, v: {}",
                        String.format("%.2f", prices[-1]),
//                        String.format("%.2f", userInfo.info.funds.asset.net),
//                        String.format("%.2f", userInfo.info.funds.asset.total),
                        String.format("%.2f", p),
                        String.format("%.3f", btc),
                        String.format("%.2f", cny),
                        String.format("%.2f", vol))

                def burstPrice = prices[-1] * cfg.burst.threshold.pct
                def bull = false
                def bear = false
                def tradeAmount = 0

                // 趋势策略，价格出现方向上的突破时开始交易
                if (numTick > 2 && (
                        prices[-1] - prices[-6 .. -2].max() > +burstPrice ||
                                prices[-1] - prices[-6 .. -3].max() > +burstPrice && prices[-1] > prices[-2]
                )) {
                    bull = true
                    tradeAmount = cny / bidPrice * 0.99
                }
                if (numTick > 2 && (
                        prices[-1] - prices[-6 .. -2].min() < -burstPrice ||
                                prices[-1] - prices[-6 .. -3].min() < -burstPrice && prices[-1] < prices[-2]
                )) {
                    bear = true
                    tradeAmount = btc
                }

                // 下单力度计算
                //  1. 小成交量的趋势成功率比较低，减小力度
                //  2. 过度频繁交易有害，减小力度
                //  3. 短时价格波动过大，减小力度
                //  4. 盘口价差过大，减少力度
                if (vol < cfg.burst.threshold.vol) tradeAmount *= vol / cfg.burst.threshold.vol
                if (numTick < 5)  tradeAmount *= 0.80
                if (numTick < 10) tradeAmount *= 0.80
                if (bull && prices[-1] < prices[0 .. -1].max()) tradeAmount *= 0.90
                if (bear && prices[-1] > prices[0 .. -1].min()) tradeAmount *= 0.90
                if (Math.abs(prices[-1] - prices[-2]) > burstPrice * 2) tradeAmount *= 0.90
                if (Math.abs(prices[-1] - prices[-2]) > burstPrice * 3) tradeAmount *= 0.90
                if (Math.abs(prices[-1] - prices[-2]) > burstPrice * 4) tradeAmount *= 0.90
                if (orderBook.asks[0].limitPrice - orderBook.bids[0].limitPrice > burstPrice * 2) tradeAmount *= 0.90
                if (orderBook.asks[0].limitPrice - orderBook.bids[0].limitPrice > burstPrice * 3) tradeAmount *= 0.90
                if (orderBook.asks[0].limitPrice - orderBook.bids[0].limitPrice > burstPrice * 4) tradeAmount *= 0.90

                if (tradeAmount >= 0.1) {  // 最后下单量小于0.1BTC的就不操作了
                    def tradePrice = bull ? bidPrice : askPrice
                    trading = true

                    while (tradeAmount >= 0.1) {
                        def orderId = bull? trader1.placeLimitOrder(new LimitOrder(Order.OrderType.BID,tradeAmount,CurrencyPair.BTC_USD,"",null,bidPrice))
                        : trader1.placeLimitOrder(new LimitOrder(Order.OrderType.ASK,tradeAmount,CurrencyPair.BTC_USD,"",null,askPrice))

                        ignoreException {  // 等待200ms后取消挂单
                            sleep 200
                            trader1.cancelOrder(orderId)
                        }

                        // 获取订单状态
                        def order
                        while (order == null || order.status == Status.CANCEL_REQUEST_IN_PROCESS) {
                            order = trader1.getOrder("btc_cny", orderId).orders[0]
                        }
                        logger.warn("TRADING: {} price: {}, amount: {}, dealAmount: {}",
                                bull ? '++':'--',
                                String.format("%.2f", bull ? bidPrice : askPrice),
                                String.format("%.3f", tradeAmount),
                                String.format("%.3f", order.dealAmount))
                        tradeAmount -= order.dealAmount
                        tradeAmount -= 0.01
                        tradeAmount *= 0.98  // 每轮循环都少量削减力度

                        if (order.status == Status.CANCELLED) {
                            updateOrderBook()  // 更新盘口，更新后的价格高于提单价格也需要削减力度
                            while (bull && bidPrice - tradePrice > +0.1) {
                                tradeAmount *= 0.99
                                tradePrice += 0.01
                            }
                            while (bear && askPrice - tradePrice < -0.1) {
                                tradeAmount *= 0.99
                                tradePrice -= 0.01
                            }
                        }
                    }
                    numTick = 0
                }
            } catch (InterruptedException e) {
                logger.error("interrupted: ", e)
                break

            } catch (all) {
                logger.error("unhandled exception: ", all)
                continue
            }
        }
    }
}

// configure logging
_prop = new Properties()
_prop.setProperty("log4j.rootLogger", "INFO, trading")
_prop.setProperty("log4j.appender.trading", "org.apache.log4j.ConsoleAppender")
_prop.setProperty("log4j.appender.trading.Target", "System.err")
_prop.setProperty("log4j.appender.trading.layout", "org.apache.log4j.PatternLayout")
_prop.setProperty("log4j.appender.trading.layout.ConversionPattern", "[%d{yyyy-MM-dd HH:mm:ss}] %p %m %n")
PropertyConfigurator.configure(_prop)

_trading = new Trading(new ConfigSlurper().parse(new File(("C:\\Users\\wuqianming\\Desktop\\okcoinInt\\example.cfg")).text))
_trading.start()