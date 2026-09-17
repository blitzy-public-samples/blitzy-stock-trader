package tradr

import scala.concurrent.duration._
import scala.util.Random
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.io.Source
import scala.language.postfixOps

class RecordedSimulation extends Simulation {

  // You can add or remove stocks you want to trade.
  var stocksToUse = Seq[String]()
  val source  = Source.fromFile("stocks.txt")
  for(line <- source.getLines()) {
    if (!line.startsWith("#") && !line.isBlank) {
      stocksToUse = stocksToUse :+ line
    }
  }
  source.close()
  System.out.println("Stock list is of size: " + stocksToUse.size)
  System.out.println(stocksToUse)
  val maxThinkTime = 30 // seconds
  val minThinkTime = 5 // seconds

  val publicBaseUrl = System.getProperty("publicBaseEndpoint", null)

  if(publicBaseUrl == null){
    System.out.println("Please supply an endpoint via '-DpublicBaseEndpoint=https://...'")
    System.exit(-1)
  }

  private val portfolioDataFeeder = Iterator.continually{
    Map("owner" -> s"gatling-${Random.alphanumeric.take(20).mkString}",  // Randomly generated owner string
      "stock1"-> s"${stocksToUse(Random.nextInt(stocksToUse.length)).mkString}",  // Randomly pick a stock
      "amountToBuySell1" -> (Random.nextInt(10)+1),
      "stock2"-> s"${stocksToUse(Random.nextInt(stocksToUse.length)).mkString}",  // Randomly pick a second stock
      "amountToBuySell2" -> (Random.nextInt(10)+1))
  }

  private val httpProtocol = http
    .baseUrl(publicBaseUrl)  //This is the endpoint to use
    .inferHtmlResources(AllowList(), DenyList(""".*\.js""", """.*\.css""", """.*\.gif""", """.*\.jpeg""", """.*\.jpg""", """.*\.ico""", """.*\.woff""", """.*\.woff2""", """.*\.(t|o)tf""", """.*\.png""", """.*detectportal\.firefox\.com.*"""))
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
    .acceptEncodingHeader("gzip, deflate")
    .acceptLanguageHeader("en-US,en;q=0.5")
    .doNotTrackHeader("1")
    .upgradeInsecureRequestsHeader("1")
    .userAgentHeader("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:99.0) Gecko/20100101 Firefox/99.0")
  
  private val headers_0 = Map(
  		"Cache-Control" -> "max-age=0",
  		"Sec-Fetch-Dest" -> "document",
  		"Sec-Fetch-Mode" -> "navigate",
  		"Sec-Fetch-Site" -> "same-origin",
  		"Sec-Fetch-User" -> "?1",
  		"Sec-GPC" -> "1"
  )
  
  private val headers_1 = Map(
  		"Origin" -> "https://a701b640dea4c47b086879a44557d944-cf5b77cf4c9a5c62.elb.us-east-2.amazonaws.com:9443",
  		"Sec-Fetch-Dest" -> "document",
  		"Sec-Fetch-Mode" -> "navigate",
  		"Sec-Fetch-Site" -> "same-origin",
  		"Sec-Fetch-User" -> "?1",
  		"Sec-GPC" -> "1"
  )

  private val login = exec(
    http("Load Login Page")
      .get("/trader/login")
      .headers(headers_0)
      .check(
        substring("HTTP 500 Internal Server Error").notExists,
        substring("Exception").notExists))
      .pause(2, 5)
      .exec(http("Login")
        .post("/trader/login")
        .headers(headers_1)
        .formParam("id", "stock")
        .formParam("password", "trader")
        .formParam("submit", "Submit")
        .check(substring("HTTP 500 Internal Server Error").notExists,
          substring("Exception").notExists))
      .pause(minThinkTime,maxThinkTime)

  private val createPortfolio = exec(
    http("Visit Create Portfolio Page")
      .post("/trader/summary")
      .headers(headers_1)
      .formParam("action", "create")
      .formParam("submit", "Submit")
      .check(substring("HTTP 500 Internal Server Error").notExists, substring("Exception").notExists))
    .pause(minThinkTime,maxThinkTime).feed(portfolioDataFeeder)
    .exec(http("Create New Portfolio")
      .post("/trader/addPortfolio")
      .headers(headers_1)
      .formParam("owner", "#{owner}")
      .formParam("balance", "10000.00")
      .formParam("currency", "USD")
      .formParam("submit", "Submit")
      .check(substring("HTTP 500 Internal Server Error").notExists, substring("Exception").notExists))
    .pause(minThinkTime,maxThinkTime)

  val viewSpecificPortfolio = exec(
    http("Visit Summary Page for a Portfolio")
      .post("/trader/summary")
      .headers(headers_1)
      .formParam("action", "retrieve")
      .formParam("owner", "#{owner}")
      .formParam("submit", "Submit")
      .check(substring("HTTP 500 Internal Server Error").notExists, substring("Exception").notExists))
    .pause(minThinkTime,maxThinkTime)
//    .exec(http("Get Portfolio Data").get("/trader/viewPortfolio?owner=GatlingTest").headers(headers_1))
//    .pause(5)

  val buyStockForPortfolio = exec(
    http("Visit Buy/Sell Stock Page for Owner to Buy")
      .post("/trader/viewPortfolio?owner=#{owner}")
      .headers(headers_1)
      .formParam("submit", "Buy/Sell Stock")
      .check(substring("HTTP 500 Internal Server Error").notExists, substring("Exception").notExists))
    .exec(http("Buy Shares of Stock 1")
      .post("/trader/addStock?owner=#{owner}&source=viewPortfolio")
      .headers(headers_1)
      .formParam("symbol", "#{stock1}")
      .formParam("shares", "#{amountToBuySell1}")
      .formParam("action", "Buy")
      .formParam("submit", "Submit")
      .check(substring("HTTP 500 Internal Server Error").notExists, substring("Exception").notExists))
    .pause(minThinkTime,maxThinkTime)

  val sellStockFromPortfolio = exec(
    http("Visit Buy/Sell Stock Page for Owner to Sell")
      .post("/trader/viewPortfolio?owner=#{owner}")
      .headers(headers_1)
      .formParam("submit", "Buy/Sell Stock")
      .check(substring("HTTP 500 Internal Server Error").notExists, substring("Exception").notExists))
    .pause(minThinkTime,maxThinkTime)
    .exec(
      http("Sell Shares of Stock1")
        .post("/trader/addStock?owner=#{owner}&source=viewPortfolio")
        .headers(headers_1)
        .formParam("symbol", "#{stock1}")
        .formParam("shares", "#{amountToBuySell1}")
        .formParam("action", "Sell")
        .formParam("submit", "Submit")
        .check(substring("HTTP 500 Internal Server Error").notExists, substring("Exception").notExists))
    .pause(minThinkTime,maxThinkTime)

  val deletePortfolio = exec(
    http("Return to All Portfolios")
      .post("/trader/viewPortfolio?owner=#{owner}")
      .headers(headers_1)
      .formParam("submit", "OK")
      .check(substring("HTTP 500 Internal Server Error").notExists, substring("Exception").notExists)
  ).pause(minThinkTime,maxThinkTime)
    .exec(
      http("Delete Generated Portfolio")
        .post("/trader/summary")
        .headers(headers_1)
        .formParam("action", "delete")
        .formParam("owner", "#{owner}")
        .formParam("submit", "Submit")
        .check(substring("HTTP 500 Internal Server Error").notExists, substring("Exception").notExists)
    ).pause(minThinkTime,maxThinkTime)

  private val auditScenario = scenario("Auditor")
    .exec(login)

  private val createAndExecuteOneTradeOneSell = scenario("Create Portfolio, Execute 1 Buys and 1 Sells, Deleting Portfolio at the end")
    .exec(login,createPortfolio,buyStockForPortfolio,sellStockFromPortfolio,deletePortfolio)

  //setUp(auditScenario.inject(rampUsers(100).during(15))).protocols(httpProtocol)

//  setUp(auditScenario.inject(rampUsers(100).during(15)),
//  createAndExecute4Trades.inject(rampUsers(1).during(15)).protocols(httpProtocol))

//  setUp(
//    createAndExecute4Trades.inject(rampUsers(100).during(30)).protocols(httpProtocol))

  setUp(
    // Open Model of testing
//     createAndExecuteOneTradeOneSell.inject(rampUsersPerSec(1).to(10).during(5.minutes).randomized).protocols(httpProtocol))

    // Closed Model of testing
    createAndExecuteOneTradeOneSell.inject(
      rampConcurrentUsers(1)
        .to(400)
        .during(5.minutes)
        //.during(30.minutes)
      ,
      constantConcurrentUsers(400)
        //.during(10.minutes)
        .during(4.hours)
    ).protocols(httpProtocol))
  //createAndExecute4Trades.inject(atOnceUsers(1)).protocols(httpProtocol))



  // below lies the original recording of trader
/* private val scn = scenario("RecordedSimulation")
    .exec(
      http("Load Login Page")
        .get("/trader/login")
        .headers(headers_0)
    )
    .pause(4)
    .exec(
      http("Login")
        .post("/trader/login")
        .headers(headers_1)
        .formParam("id", "stock")
        .formParam("password", "trader")
        .formParam("submit", "Submit")
    )
    .pause(4)
    .exec(
      http("Visit Create Portfolio Page")
        .post("/trader/summary")
        .headers(headers_1)
        .formParam("action", "create")
        .formParam("submit", "Submit")
    )
    .pause(4)
    .exec(
      http("Create New Portfolio")
        .post("/trader/addPortfolio")
        .headers(headers_1)
        .formParam("owner", "GatlingTest")
        .formParam("submit", "Submit")
    )
    .pause(5)
    .exec(
      http("Visit Summary Page for a Portfolio")
        .post("/trader/summary")
        .headers(headers_1)
        .formParam("action", "retrieve")
        .formParam("owner", "GatlingTest")
        .formParam("submit", "Submit")
    )
    .pause(2)
    .exec(
      http("Visit Buy/Sell Stock Page for Owner")
        .post("/trader/viewPortfolio?owner=GatlingTest")
        .headers(headers_1)
        .formParam("submit", "Buy/Sell Stock")
    )
    .pause(5)
    .exec(
      http("Buy 20 Shares of Kyndryl")
        .post("/trader/addStock?owner=GatlingTest&source=viewPortfolio")
        .headers(headers_1)
        .formParam("symbol", "KD")
        .formParam("shares", "20")
        .formParam("action", "Buy")
        .formParam("submit", "Submit")
    )
    .pause(2)
    .exec(
      http("Visit Buy/Sell Stock Page for Owner")
        .post("/trader/viewPortfolio?owner=GatlingTest")
        .headers(headers_1)
        .formParam("submit", "Buy/Sell Stock")
    )
    .pause(5)
    .exec(
      http("Buy 20 Shares of IBM for Owner")
        .post("/trader/addStock?owner=GatlingTest&source=viewPortfolio")
        .headers(headers_1)
        .formParam("symbol", "IBM")
        .formParam("shares", "20")
        .formParam("action", "Buy")
        .formParam("submit", "Submit")
    )
    .pause(4)
    .exec(
      http("Visit Buy/Sell Stock Page for Owner")
        .post("/trader/viewPortfolio?owner=GatlingTest")
        .headers(headers_1)
        .formParam("submit", "Buy/Sell Stock")
    )
    .pause(5)
    .exec(
      http("Sell 15 Shares of Kyndryl for Owner")
        .post("/trader/addStock?owner=GatlingTest&source=viewPortfolio")
        .headers(headers_1)
        .formParam("symbol", "KD")
        .formParam("shares", "15")
        .formParam("action", "Buy")
        .formParam("submit", "Submit")
    )
    .pause(2)
    .exec(
      http("Return to All Portfolios")
        .post("/trader/viewPortfolio?owner=GatlingTest")
        .headers(headers_1)
        .formParam("submit", "OK")
    )
    .pause(8)
    .exec(
      http("Delete Portfolio")
        .post("/trader/summary")
        .headers(headers_1)
        .formParam("action", "delete")
        .formParam("owner", "GatlingTest")
        .formParam("submit", "Submit")
    )*/

//	setUp(scn.inject(atOnceUsers(1))).protocols(httpProtocol)

}
