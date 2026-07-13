package com.fxc.investor.sim;

import static io.gatling.javaapi.core.CoreDsl.ByteArrayBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import com.fxc.common.instrument.Instrument;
import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.investor.ofx.OfxBrokerClient;
import com.fxc.investor.strategy.MarketView;
import com.fxc.investor.strategy.OrderIntent;
import com.fxc.investor.strategy.PortfolioView;
import com.fxc.investor.strategy.Strategies;
import com.fxc.investor.strategy.Strategy;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gatling multi-agent investor simulation (docs/stories/005): drives a configurable population of
 * autonomous investor agents ({@code rando}/{@code booker}/{@code bookfish}) against the broker's
 * OFX endpoint for performance testing and bulk market simulation.
 *
 * <p>Reuses the production strategies and the shared OFX request-building
 * ({@link OfxBrokerClient#marshalOrder}); Gatling drives the HTTP and reporting.
 *
 * <p><b>Run:</b> {@code ./gradlew :FxcInvestor:gatlingRun
 * -Dgatling.simulationClass=com.fxc.investor.sim.FxcInvestorSimulation} against a running broker.
 * All knobs are system properties (see below). Not part of the default build.
 */
public class FxcInvestorSimulation extends Simulation {

    // --- configuration (system properties with defaults) ---
    private static final String OFX_URL = System.getProperty("fxc.ofx.url", "http://localhost:8082/ofx");
    private static final String OFX_USER = System.getProperty("fxc.ofx.user", "investor");
    private static final String OFX_PASSWORD = System.getProperty("fxc.ofx.password", "secret");
    private static final String BROKER_ID = System.getProperty("fxc.ofx.brokerId", "FXC-BROKER");
    private static final String ACCOUNT = System.getProperty("fxc.account", "000123456");
    private static final String SYMBOL = System.getProperty("sim.symbol", "ACME");
    private static final BigDecimal BASE = new BigDecimal(System.getProperty("sim.basePrice", "42.10"));

    private static final int USERS = Integer.getInteger("sim.users", 20);
    private static final int RAMP_SECONDS = Integer.getInteger("sim.rampSeconds", 10);
    private static final int ORDERS_PER_USER = Integer.getInteger("sim.ordersPerUser", 20);
    private static final int PAUSE_MS = Integer.getInteger("sim.pauseMs", 200);
    private static final long SEED = Long.getLong("sim.seed", 1L);
    private static final String PROFILE = System.getProperty("sim.profile", "ramp"); // ramp|steady|spike
    private static final int RATE = Integer.getInteger("sim.ratePerSec", 20);         // steady profile

    // Population mix (percentages; the remainder after rando/booker is bookfish).
    private static final int MIX_RANDO = Integer.getInteger("sim.mix.rando", 80);
    private static final int MIX_BOOKER = Integer.getInteger("sim.mix.booker", 15);

    // Perf assertion thresholds.
    private static final int MAX_P95_MS = Integer.getInteger("sim.maxP95Ms", 1000);
    private static final int MAX_P99_MS = Integer.getInteger("sim.maxP99Ms", 2000);
    private static final double MAX_ERROR_PCT = Double.parseDouble(System.getProperty("sim.maxErrorPct", "5"));

    private static final OfxBrokerClient OFX = new OfxBrokerClient(OFX_URL, OFX_USER, OFX_PASSWORD, BROKER_ID);
    private static final AtomicLong CL_ORD_SEQ = new AtomicLong();

    public FxcInvestorSimulation() {
        URI uri = URI.create(OFX_URL);
        String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/ofx" : uri.getRawPath();

        HttpProtocolBuilder httpProtocol = http
                .baseUrl(origin)
                .contentTypeHeader("application/x-ofx")
                .acceptHeader("application/x-ofx")
                .userAgentHeader("FxcInvestor-Gatling");

        ScenarioBuilder scenario = scenario("fxc-investor-" + PROFILE)
                // Per-user seeded strategy + market view.
                .exec(session -> {
                    long uid = session.userId();
                    Random rng = new Random(SEED + uid);
                    Strategy strategy = pickStrategy(uid);
                    MarketView market = seededMarket();
                    return session.set("rng", rng).set("strategy", strategy).set("market", market);
                })
                .repeat(ORDERS_PER_USER).on(
                        exec(session -> {
                            Random rng = (Random) session.get("rng");
                            Strategy strategy = (Strategy) session.get("strategy");
                            MarketView market = (MarketView) session.get("market");
                            Optional<OrderIntent> decision =
                                    strategy.decide(SYMBOL, market, PortfolioView.empty(), rng);
                            if (decision.isEmpty()) {
                                return session.set("skip", true);
                            }
                            OrderIntent intent = decision.get();
                            BigDecimal price = snapToTick(SYMBOL, intent.price());
                            byte[] body = OFX.marshalOrder(ACCOUNT, "GAT-" + CL_ORD_SEQ.incrementAndGet(),
                                    SYMBOL, intent.side(), price, intent.quantity());
                            return session.set("ofxBody", body).set("skip", false);
                        })
                                .doIf(session -> !session.getBoolean("skip")).then(
                                        exec(http("ofx-order")
                                                .post(path)
                                                .body(ByteArrayBody(s -> (byte[]) s.get("ofxBody")))
                                                .check(status().is(200))))
                                .pause(Duration.ofMillis(PAUSE_MS)));

        PopulationBuilder population = switch (PROFILE.toLowerCase()) {
            case "spike" -> scenario.injectOpen(atOnceUsers(USERS));
            case "steady" -> scenario.injectOpen(constantUsersPerSec(RATE).during(Duration.ofSeconds(RAMP_SECONDS)));
            default -> scenario.injectOpen(rampUsers(USERS).during(Duration.ofSeconds(RAMP_SECONDS)));
        };

        setUp(population)
                .protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile3().lt(MAX_P95_MS),
                        global().responseTime().percentile4().lt(MAX_P99_MS),
                        global().failedRequests().percent().lt(MAX_ERROR_PCT));
    }

    // --- strategy population + market seeding ---

    private Strategy pickStrategy(long uid) {
        int bucket = (int) (Math.floorMod(uid, 100));
        String name;
        if (bucket < MIX_RANDO) {
            name = "rando";
        } else if (bucket < MIX_RANDO + MIX_BOOKER) {
            name = "booker";
        } else {
            name = "bookfish";
        }
        return Strategies.byName(name);
    }

    /** A small synthetic market so all three strategies have a signal to price against. */
    private MarketView seededMarket() {
        MarketView market = new MarketView();
        BigDecimal tick = new BigDecimal("0.01");
        List<MarketView.Level> book = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            BigDecimal bid = BASE.subtract(tick.multiply(BigDecimal.valueOf(i)));
            BigDecimal ask = BASE.add(tick.multiply(BigDecimal.valueOf(i)));
            BigDecimal size = BigDecimal.valueOf(100L * (4 - i));
            book.add(new MarketView.Level(bid, size));
            book.add(new MarketView.Level(ask, size));
            market.recordTrade(SYMBOL, bid, BigDecimal.valueOf(20));
            market.recordTrade(SYMBOL, ask, BigDecimal.valueOf(20));
        }
        market.recordTrade(SYMBOL, BASE, BigDecimal.valueOf(50));
        market.setBook(SYMBOL, book);
        market.setLastSale(SYMBOL, BASE); // pin the last sale (recordTrade moved it)
        return market;
    }

    private static BigDecimal snapToTick(String symbol, BigDecimal price) {
        Instrument instrument = InstrumentCatalog.find(symbol).orElseThrow();
        BigDecimal tick = instrument.tickSize();
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick).setScale(tick.scale(), RoundingMode.HALF_UP);
    }
}
