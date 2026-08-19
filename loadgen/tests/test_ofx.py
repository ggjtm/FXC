"""Cross-language wire-contract tests for the OFX codec.

The important ones are :class:`GoldenFixtureTest`, which assert this module produces byte-identical
requests to the Java client's OFX4J output, using the fixtures in ``FxcInvestor/sample_data/``
generated and guarded by ``OfxGoldenEnvelopeTest``. Everything the harness can get wrong about the
wire format returns HTTP 200 from the broker, so these fixtures are the only cheap defence.

Uses stdlib ``unittest`` deliberately: the codec and strategy logic must be testable with no pip
install at all. Run with::

    python3 -m unittest discover -s loadgen/tests -t loadgen
"""

from __future__ import annotations

import unittest
from datetime import datetime, timezone
from pathlib import Path

from fxc_loadgen import ofx

SAMPLE_DIR = Path(__file__).resolve().parents[2] / "FxcInvestor" / "sample_data"

# The same fixed inputs OfxGoldenEnvelopeTest uses.
ACCOUNT = "000123456"
CL_ORD_ID = "GOLDEN-1"
USER = "investor"
PASSWORD = "secret"


class GoldenFixtureTest(unittest.TestCase):
    """This module must reproduce OFX4J's bytes for the committed fixtures."""

    def _golden(self, name: str) -> str:
        path = SAMPLE_DIR / name
        self.assertTrue(
            path.exists(),
            f"missing fixture {path}; regenerate with "
            "FXC_WRITE_GOLDEN=1 ./gradlew :FxcInvestor:test --tests '*OfxGoldenEnvelopeTest'",
        )
        return ofx.canonicalize(path.read_bytes())

    def test_equity_order_matches_java(self):
        built = ofx.build_order_request(
            ACCOUNT, CL_ORD_ID, "ARVX", "BUY", "42.10", "10", user=USER, password=PASSWORD
        )
        self.assertEqual(self._golden("ofx-order-arvx.xml"), ofx.canonicalize(built))

    def test_fx_order_matches_java(self):
        built = ofx.build_order_request(
            ACCOUNT, CL_ORD_ID, "EUR/USD", "SELL", "1.08420", "1000", user=USER, password=PASSWORD
        )
        self.assertEqual(self._golden("ofx-order-eurusd.xml"), ofx.canonicalize(built))


class FormattingTest(unittest.TestCase):
    def test_java_double_matches_javas_lexical_form(self):
        # The broker types these fields as Double, so BigDecimal("10") marshals as "10.0" and
        # BigDecimal("42.10") loses its trailing zero. Verified against the golden fixtures.
        self.assertEqual("10.0", ofx.java_double("10"))
        self.assertEqual("42.1", ofx.java_double("42.10"))
        self.assertEqual("1.0842", ofx.java_double("1.08420"))
        self.assertEqual("1000.0", ofx.java_double(1000))

    def test_java_double_refuses_exponent_notation(self):
        with self.assertRaises(ValueError):
            ofx.java_double(1e16)

    def test_java_double_refuses_non_finite(self):
        for bad in (float("nan"), float("inf")):
            with self.assertRaises(ValueError):
                ofx.java_double(bad)

    def test_dtclient_format_is_utc_millis(self):
        stamp = ofx.dtclient(datetime(2026, 7, 29, 16, 15, 0, 123_000, tzinfo=timezone.utc))
        self.assertEqual("20260729161500.123", stamp)

    def test_dtclient_converts_to_utc(self):
        from datetime import timedelta

        eastern = timezone(timedelta(hours=-5))
        stamp = ofx.dtclient(datetime(2026, 7, 29, 11, 15, 0, 0, tzinfo=eastern))
        self.assertTrue(stamp.startswith("20260729161500"), stamp)

    def test_security_id_branches(self):
        self.assertEqual(("ARVX", "TICKER"), ofx.security_id("ARVX"))
        self.assertEqual(("FX:EUR/USD", "FXC"), ofx.security_id("EUR/USD"))


class EmptyElementTest(unittest.TestCase):
    """An empty element is a fatal parse error at the broker, so it must be impossible to emit."""

    def test_blank_values_are_refused(self):
        for blank in ("", "   ", None):
            with self.assertRaises(ValueError):
                ofx._el("TRNUID", blank)

    def test_no_request_contains_a_self_closing_element(self):
        built = ofx.build_order_request(
            ACCOUNT, CL_ORD_ID, "ARVX", "BUY", "42.10", "10", user=USER, password=PASSWORD
        ).decode(ofx.ENCODING)
        self.assertNotIn("/>", built)

    def test_empty_client_order_id_is_refused(self):
        with self.assertRaises(ValueError):
            ofx.build_order_request(
                ACCOUNT, "", "ARVX", "BUY", "42.10", "10", user=USER, password=PASSWORD
            )


class RequestShapeTest(unittest.TestCase):
    def test_processing_instruction_is_present_and_well_formed(self):
        built = ofx.build_order_request(
            ACCOUNT, CL_ORD_ID, "ARVX", "BUY", "42.10", "10", user=USER, password=PASSWORD
        ).decode(ofx.ENCODING)
        # Exactly one space after <?OFX and no '?' inside the attributes, or the broker's regex
        # misses it and silently falls back to the SGML path.
        self.assertIn('<?OFX OFXHEADER="200" VERSION="202" SECURITY="NONE"', built)
        self.assertIn(f'NEWFILEUID="{CL_ORD_ID}"?>', built)

    def test_limit_order_without_price_is_refused(self):
        with self.assertRaises(ValueError):
            ofx.build_order_request(
                ACCOUNT, CL_ORD_ID, "ARVX", "BUY", None, "10", user=USER, password=PASSWORD
            )

    def test_bad_side_is_refused(self):
        with self.assertRaises(ValueError):
            ofx.build_order_request(
                ACCOUNT, CL_ORD_ID, "ARVX", "buy", "42.10", "10", user=USER, password=PASSWORD
            )

    def test_order_and_book_share_one_envelope(self):
        built = ofx.build_order_with_book_request(
            ACCOUNT, CL_ORD_ID, "ARVX", "BUY", "42.10", "10", 5, user=USER, password=PASSWORD
        ).decode(ofx.ENCODING)
        # Legal: different MessageSetTypes (investment vs investment_security).
        self.assertIn("<FXCORDMSGSRQV1>", built)
        self.assertIn("<FXCMDMSGSRQV1>", built)
        self.assertIn("<DEPTH>5</DEPTH>", built)

    def test_book_request_shape(self):
        built = ofx.build_book_request("BK-1", "ARVX", 10, user=USER, password=PASSWORD).decode(
            ofx.ENCODING
        )
        self.assertIn("<FXCMDMSGSRQV1><FXCMDTRNRQ><TRNUID>BK-1</TRNUID>", built)
        self.assertIn("<UNIQUEID>ARVX</UNIQUEID>", built)

    def test_escaping(self):
        # Values are escaped for XML text; the broker escapes only & < > on the way out too.
        self.assertEqual("<TRNUID>a&amp;b</TRNUID>", ofx._el("TRNUID", "a&b"))
        self.assertEqual("<TRNUID>a&lt;b</TRNUID>", ofx._el("TRNUID", "a<b"))


def _envelope(*sets: str) -> bytes:
    return (
        '<?xml version="1.0" encoding="utf-8" ?>\r\n'
        '<?OFX OFXHEADER="200" VERSION="202" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="X"?>\r\n'
        "<OFX>" + "".join(sets) + "</OFX>"
    ).encode(ofx.ENCODING)


_SIGNON_OK = (
    "<SIGNONMSGSRSV1><SONRS><STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS>"
    "<DTSERVER>20260729161500.456</DTSERVER><LANGUAGE>ENG</LANGUAGE></SONRS></SIGNONMSGSRSV1>"
)
_SIGNON_BAD = (
    "<SIGNONMSGSRSV1><SONRS><STATUS><CODE>15500</CODE><SEVERITY>ERROR</SEVERITY></STATUS>"
    "<DTSERVER>20260729161500.456</DTSERVER><LANGUAGE>ENG</LANGUAGE></SONRS></SIGNONMSGSRSV1>"
)


class ResponseParsingTest(unittest.TestCase):
    def test_accepted_order(self):
        reply = _envelope(
            _SIGNON_OK,
            "<FXCORDMSGSRSV1><FXCORDTRNRS><TRNUID>GOLDEN-1</TRNUID>"
            "<STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS>"
            "<FXCORDRS><BROKERORDERID>EX-42</BROKERORDERID><ORDERSTATUS>ROUTED</ORDERSTATUS>"
            "</FXCORDRS></FXCORDTRNRS></FXCORDMSGSRSV1>",
        )
        parsed = ofx.parse_response(reply)
        self.assertTrue(parsed.signon_ok)
        outcome = ofx.require_order(parsed)
        self.assertTrue(outcome.accepted)
        self.assertEqual("ROUTED", outcome.order_status)
        self.assertEqual("EX-42", outcome.broker_order_id)

    def test_rejected_order_is_a_business_outcome_not_an_error(self):
        reply = _envelope(
            _SIGNON_OK,
            "<FXCORDMSGSRSV1><FXCORDTRNRS><TRNUID>GOLDEN-1</TRNUID>"
            "<STATUS><CODE>2000</CODE><SEVERITY>ERROR</SEVERITY>"
            "<MESSAGE>insufficient shares for equity sell (no shorting)</MESSAGE></STATUS>"
            "<FXCORDRS><BROKERORDERID>GOLDEN-1</BROKERORDERID><ORDERSTATUS>REJECTED</ORDERSTATUS>"
            "</FXCORDRS></FXCORDTRNRS></FXCORDMSGSRSV1>",
        )
        parsed = ofx.parse_response(reply)
        outcome = ofx.require_order(parsed)  # must NOT raise
        self.assertTrue(outcome.rejected)
        self.assertEqual("REJECTED", outcome.order_status)
        self.assertIn("no shorting", outcome.message)

    def test_bad_signon_is_detected_despite_http_200(self):
        parsed = ofx.parse_response(_envelope(_SIGNON_BAD))
        self.assertFalse(parsed.signon_ok)
        self.assertEqual(ofx.SIGNON_INVALID, parsed.signon_code)
        with self.assertRaises(ofx.OfxProtocolError) as caught:
            ofx.require_order(parsed)
        self.assertIn("signon rejected", str(caught.exception))

    def test_missing_order_message_set_is_detected(self):
        # What a misspelled request tag looks like from the client: valid signon, no order response.
        parsed = ofx.parse_response(_envelope(_SIGNON_OK))
        self.assertTrue(parsed.signon_ok)
        with self.assertRaises(ofx.OfxProtocolError) as caught:
            ofx.require_order(parsed)
        self.assertIn("no FXCORDMSGSRSV1", str(caught.exception))

    def test_book_response(self):
        reply = _envelope(
            _SIGNON_OK,
            "<FXCMDMSGSRSV1><FXCMDTRNRS><TRNUID>BK-1</TRNUID>"
            "<STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS>"
            "<FXCMDRS><SECID><UNIQUEID>ARVX</UNIQUEID><UNIQUEIDTYPE>TICKER</UNIQUEIDTYPE></SECID>"
            "<LASTPRICE>42.1</LASTPRICE>"
            "<FXCBOOKLVL><SIDE>BID</SIDE><PRICE>42.09</PRICE><SIZE>300.0</SIZE></FXCBOOKLVL>"
            "<FXCBOOKLVL><SIDE>OFFER</SIDE><PRICE>42.11</PRICE><SIZE>200.0</SIZE></FXCBOOKLVL>"
            "</FXCMDRS></FXCMDTRNRS></FXCMDMSGSRSV1>",
        )
        parsed = ofx.parse_response(reply)
        self.assertEqual(42.1, parsed.last_price)
        self.assertEqual(2, len(parsed.book))
        self.assertEqual(ofx.BookLevel("BID", 42.09, 300.0), parsed.book[0])
        self.assertEqual("OFFER", parsed.book[1].side)

    def test_statement_response_yields_cash_and_shares(self):
        reply = _envelope(
            _SIGNON_OK,
            "<INVSTMTMSGSRSV1><INVSTMTTRNRS><TRNUID>STMT-1</TRNUID>"
            "<STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS>"
            "<INVSTMTRS><INVBAL><AVAILCASH>999000.0</AVAILCASH></INVBAL>"
            "<INVPOSLIST>"
            "<POSSTOCK><INVPOS><SECID><UNIQUEID>ARVX</UNIQUEID>"
            "<UNIQUEIDTYPE>TICKER</UNIQUEIDTYPE></SECID><UNITS>1010.0</UNITS></INVPOS></POSSTOCK>"
            "<POSOTHER><INVPOS><SECID><UNIQUEID>FX:EUR</UNIQUEID>"
            "<UNIQUEIDTYPE>FXC</UNIQUEIDTYPE></SECID><UNITS>1000.0</UNITS></INVPOS></POSOTHER>"
            "</INVPOSLIST></INVSTMTRS></INVSTMTTRNRS></INVSTMTMSGSRSV1>",
        )
        parsed = ofx.parse_response(reply)
        self.assertEqual(999000.0, parsed.cash["USD"])
        self.assertEqual(1000.0, parsed.cash["EUR"])  # FX: positions are cash, not shares
        self.assertEqual(1010.0, parsed.shares["ARVX"])

    def test_reply_without_ofx_element_is_a_protocol_error(self):
        with self.assertRaises(ofx.OfxProtocolError):
            ofx.parse_response(b"OFX parse error: something went wrong")

    def test_parses_a_reply_with_java_style_indentation(self):
        # Real broker replies arrive CRLF-indented; parsing must not care.
        reply = _envelope(_SIGNON_OK).decode(ofx.ENCODING).replace("><", ">\r\n  <")
        self.assertTrue(ofx.parse_response(reply).signon_ok)


class CanonicalizeTest(unittest.TestCase):
    def test_neutralises_dtclient_and_whitespace(self):
        java_style = (
            "<OFX>\r\n  <SONRQ>\r\n    <DTCLIENT>20260729161500.123</DTCLIENT>\r\n"
            "  </SONRQ>\r\n</OFX>"
        )
        compact = "<OFX><SONRQ><DTCLIENT>19990101000000.000</DTCLIENT></SONRQ></OFX>"
        self.assertEqual(ofx.canonicalize(java_style), ofx.canonicalize(compact))

    def test_does_not_collapse_significant_text(self):
        self.assertIn("<A>x y</A>", ofx.canonicalize("<A>x y</A>"))


if __name__ == "__main__":
    unittest.main()
