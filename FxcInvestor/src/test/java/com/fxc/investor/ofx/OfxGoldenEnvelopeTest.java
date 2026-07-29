package com.fxc.investor.ofx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.investor.strategy.Side;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins FxcBroker's OFX order-entry wire format to committed fixtures in {@code sample_data/}
 * (FxcInvestor/docs/stories/006).
 *
 * <p><b>Why this exists.</b> The Locust load harness builds these same envelopes in Python, and every
 * way of getting them wrong returns <b>HTTP 200</b>: a misspelled tag makes the broker silently skip
 * the whole message set, and a bad password returns a signon-only envelope. A load generator would
 * therefore report 100% success while placing no orders at all. These fixtures are the shared
 * contract — this test guards them against Java-side drift, and
 * {@code loadgen/tests/test_ofx.py} asserts the Python builder produces the same thing.
 *
 * <p>To regenerate after an intentional format change:
 * {@code FXC_WRITE_GOLDEN=1 ./gradlew :FxcInvestor:test --tests '*OfxGoldenEnvelopeTest'}
 * — then review the diff, because it is also a change to the Python harness's contract.
 */
class OfxGoldenEnvelopeTest {

    /** Test JVMs run with the subproject directory as their working directory. */
    private static final Path SAMPLE_DIR = Path.of("sample_data");

    /** Fixed inputs — the fixtures must be byte-reproducible, so nothing here may vary. */
    private static final String ACCOUNT = "000123456";
    private static final String CL_ORD_ID = "GOLDEN-1";

    private record Case(String fixture, String symbol, Side side, String price, String quantity) {
    }

    /** Both SECID branches: an equity ticker, and an FX pair which gets the {@code FX:} prefix. */
    private static final List<Case> CASES = List.of(
            new Case("ofx-order-acme.xml", "ACME", Side.BUY, "42.10", "10"),
            new Case("ofx-order-eurusd.xml", "EUR/USD", Side.SELL, "1.08420", "1000"));

    private static OfxBrokerClient client() {
        // No network: marshalOrder only builds bytes.
        return new OfxBrokerClient("http://localhost:8082/ofx", "investor", "secret", "FXC-BROKER");
    }

    @Test
    void marshalledOrdersMatchTheCommittedFixtures() throws IOException {
        boolean write = "1".equals(System.getenv("FXC_WRITE_GOLDEN"));
        if (write) {
            Files.createDirectories(SAMPLE_DIR);
        }

        for (Case testCase : CASES) {
            byte[] marshalled = client().marshalOrder(ACCOUNT, CL_ORD_ID, testCase.symbol(),
                    testCase.side(), new BigDecimal(testCase.price()), new BigDecimal(testCase.quantity()));
            Path fixture = SAMPLE_DIR.resolve(testCase.fixture());

            if (write) {
                Files.write(fixture, marshalled);
                System.out.println("wrote golden fixture: " + fixture.toAbsolutePath());
                continue;
            }

            assertTrue(Files.exists(fixture), () -> "missing fixture " + fixture.toAbsolutePath()
                    + " — regenerate with FXC_WRITE_GOLDEN=1");
            String expected = canonicalize(Files.readString(fixture, StandardCharsets.UTF_8));
            String actual = canonicalize(new String(marshalled, StandardCharsets.UTF_8));
            assertEquals(expected, actual, "OFX wire format drifted for " + testCase.fixture()
                    + " — if intentional, regenerate the fixture AND update loadgen's Python builder");
        }
    }

    @Test
    void fixturesCarryTheConstraintsThePythonBuilderMustHonour() throws IOException {
        String acme = Files.readString(SAMPLE_DIR.resolve("ofx-order-acme.xml"), StandardCharsets.UTF_8);

        // The <?OFX ?> processing instruction is the v1/v2 discriminator: without it the broker
        // silently takes the untested SGML path.
        assertTrue(acme.contains("<?OFX OFXHEADER=\"200\" VERSION=\"202\""),
                "fixture must carry the OFX v2 processing instruction");
        // Root element must be exactly OFX, unprefixed — the parser runs with namespaces disabled.
        assertTrue(acme.contains("<OFX>") && !acme.contains(":OFX"), "root must be bare <OFX>");
        // The custom message set and its nesting.
        for (String tag : List.of("SIGNONMSGSRQV1", "SONRQ", "FXCORDMSGSRQV1", "FXCORDTRNRQ",
                "FXCORDRQ", "ACCTID", "SECID", "UNIQUEID", "UNIQUEIDTYPE", "SIDE", "UNITS",
                "ORDERTYPE", "LIMITPRICE", "TRNUID")) {
            assertTrue(acme.contains("<" + tag + ">"), "fixture must contain <" + tag + ">");
        }
        // An empty element is a fatal parse error at the broker (400), so no fixture may contain one.
        assertTrue(!acme.contains("/>"), "no self-closing/empty elements — they fail to parse");

        String fx = Files.readString(SAMPLE_DIR.resolve("ofx-order-eurusd.xml"), StandardCharsets.UTF_8);
        assertTrue(fx.contains("<UNIQUEID>FX:EUR/USD</UNIQUEID>"), "FX symbols take the FX: prefix");
        assertTrue(fx.contains("<UNIQUEIDTYPE>FXC</UNIQUEIDTYPE>"), "FX SECID uses the FXC id type");
        assertTrue(acme.contains("<UNIQUEID>ACME</UNIQUEID>")
                && acme.contains("<UNIQUEIDTYPE>TICKER</UNIQUEIDTYPE>"), "equities use TICKER");
    }

    /**
     * Reduce an OFX document to the form both languages must agree on: inter-tag whitespace removed
     * (Java indents with CRLF, Python emits compact) and the one genuinely time-varying field
     * neutralised. Mirrored exactly by {@code canonicalize()} in loadgen/fxc_loadgen/ofx.py.
     */
    static String canonicalize(String ofx) {
        return ofx
                .replaceAll("(?s)<DTCLIENT>.*?</DTCLIENT>", "<DTCLIENT>#</DTCLIENT>")
                .replaceAll(">\\s+<", "><")
                .trim();
    }
}
