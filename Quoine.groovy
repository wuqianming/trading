import org.knowm.xchange.Exchange
import org.knowm.xchange.currency.CurrencyPair
import org.knowm.xchange.quoine.QuoineExchange
import org.knowm.xchange.ExchangeSpecification
import org.knowm.xchange.quoine.service.QuoineAccountService
import org.knowm.xchange.quoine.service.QuoineMarketDataService



def createxchange(){
    def exchange = new QuoineExchange()
    def exchangeSpec = new ExchangeSpecification(exchange.class)

    exchangeSpec.getExchangeSpecificParameters().put(QuoineExchange.KEY_TOKEN_ID,"69817")
    exchangeSpec.getExchangeSpecificParameters().put(QuoineExchange.KEY_USER_SECRET,"63Tm0R+hMVeJ9d/luVGWOJ8nXei5mv9YrvQlsVASEehL58N+hzWrzEoosyQmpjYY2eYvZVK4jcHhxwmmUSPUCQ==")
    exchange.applySpecification(exchangeSpec)
    return exchange
}


quoine = createxchange()

//market = quoine.getAccountService()
while (1) {


    println(quoine.getMarketDataService().getTicker(CurrencyPair.BTC_JPY))
    sleep(500)

}