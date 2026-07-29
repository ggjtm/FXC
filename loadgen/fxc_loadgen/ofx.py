"""OFX 2.x request building and response parsing for the FXC load harness.

This is the Python side of a cross-language wire contract. FxcBroker speaks OFX 2.x over HTTP with a
custom order-entry message set (``FXCORDMSGSRQV1``); the Java client builds those envelopes with
OFX4J, and this module reproduces them without it.

Why the care: **every way of getting this wrong returns HTTP 200.**

* wrong credentials -> a signon-only envelope with ``SONRS/STATUS/CODE 15500``
* a misspelled tag  -> the broker silently skips the entire message set, no order, no error
* a malformed number -> swallowed, surfaces later as "quantity must be positive"

A load generator that trusts HTTP status would report 100% success while placing no orders at all.
So: requests are built from committed fixtures under ``FxcInvestor/sample_data/`` (see
``tests/test_ofx.py`` and the Java twin ``OfxGoldenEnvelopeTest``), and responses are always
classified on parsed content.

Two hard rules encoded below:

* **Never emit an empty element.** ``<LIMITPRICE/>`` or a whitespace-only value makes the broker's
  parser read an aggregate close, mismatch its stack, and return 400. :func:`_el` refuses.
  (This is also why requests are built as strings: ``xml.etree`` serialises a text-less element as
  exactly ``<TAG />``.)
* **Emit the ``<?OFX ?>`` processing instruction.** It is the v1/v2 discriminator; without it the
  broker silently takes its untested SGML path.
"""

from __future__ import annotations

import math
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import Decimal

__all__ = [
    "CONTENT_TYPE",
    "ENCODING",
    "BookLevel",
    "OfxProtocolError",
    "OrderOutcome",
    "Response",
    "build_book_request",
    "build_order_request",
    "build_order_with_book_request",
    "build_statement_request",
    "canonicalize",
    "dtclient",
    "java_double",
    "parse_response",
    "security_id",
]

#: The broker's OFX servlet accepts only this content type.
CONTENT_TYPE = "application/x-ofx"

#: The broker decodes request bodies as ISO-8859-1 regardless of the declared XML encoding
#: (ofx4j's ``OFXSettings.ENCODING``), so encode with latin-1 and keep values ASCII.
ENCODING = "latin-1"

#: Status codes the broker sends. 15500 is SIGNON_INVALID, 2000 is GENERAL_ERROR.
SIGNON_OK = 0
SIGNON_INVALID = 15500
TXN_OK = 0
TXN_ERROR = 2000

_PROLOG = (
    '<?xml version="1.0" encoding="utf-8" ?>\r\n'
    '<?OFX OFXHEADER="200" VERSION="202" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="{uid}"?>\r\n'
)


class OfxProtocolError(RuntimeError):
    """The broker's reply was well-formed HTTP but not a usable OFX answer.

    Raised for the silent-failure cases: a rejected signon, or a response missing the message set
    that was asked for (which is what a misspelled request tag looks like from the client side).
    """


# --------------------------------------------------------------------------- formatting


def java_double(value: Decimal | float | int) -> str:
    """Format a number the way Java's ``Double.toString`` does.

    The broker's aggregates type ``UNITS``/``LIMITPRICE``/``PRICE``/``SIZE`` as ``Double``, so the
    Java client's ``BigDecimal`` is narrowed before marshalling: ``10`` becomes ``10.0`` and ``42.10``
    becomes ``42.1``. Python's ``repr(float)`` uses the same shortest-round-trip rule and agrees over
    the ranges FXC trades in.

    Exponent notation is refused rather than sent: the broker parses with ``Double.parseDouble`` after
    a comma/period swap, and while it would in fact accept ``1e+16``, silently emitting a different
    lexical form than the Java client would is exactly the drift these fixtures exist to prevent.
    """
    number = float(value)
    if not math.isfinite(number):
        raise ValueError(f"cannot send non-finite value {value!r}")
    text = repr(number)
    if "e" in text or "E" in text:
        raise ValueError(
            f"{value!r} formats as {text!r} (exponent notation); keep quantities and prices "
            "within plain-decimal range"
        )
    return text


def dtclient(now: datetime | None = None) -> str:
    """``DTCLIENT`` in the broker's format: ``yyyyMMddHHmmss.SSS`` in UTC.

    The broker never reads this field, but the Java client always sends it, so the harness does too.
    """
    moment = now or datetime.now(timezone.utc)
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)

    moment = moment.astimezone(timezone.utc)
    return f"{moment:%Y%m%d%H%M%S}.{moment.microsecond // 1000:03d}"


def security_id(symbol: str) -> tuple[str, str]:
    """Map a symbol to ``(UNIQUEID, UNIQUEIDTYPE)``.

    Mirrors ``OfxBrokerClient.securityId``: FX pairs contain a slash and take an ``FX:`` prefix with
    the ``FXC`` id type; equities go through as a bare ``TICKER``. The broker strips the prefix again
    in ``symbolFromSecurityId`` and ignores the type entirely, but matching the Java client keeps one
    wire format rather than two.
    """
    if "/" in symbol:
        return f"FX:{symbol}", "FXC"
    return symbol, "TICKER"


def _el(tag: str, value: object) -> str:
    """One element. Refuses an empty value, because the broker cannot parse one.

    ``<TAG></TAG>``, ``<TAG/>`` and ``<TAG>   </TAG>`` are all indistinguishable to ofx4j's content
    handler from an aggregate close, which mismatches its stack and returns HTTP 400. Omit the tag
    instead of emitting it empty.
    """
    text = "" if value is None else str(value)
    if not text.strip():
        raise ValueError(
            f"<{tag}> would be empty; omit the element instead — an empty element is a fatal "
            "parse error at the broker (HTTP 400)"
        )
    escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return f"<{tag}>{escaped}</{tag}>"


def _signon(user: str, password: str, stamp: str) -> str:
    return (
        "<SIGNONMSGSRQV1><SONRQ>"
        + _el("DTCLIENT", stamp)
        + _el("USERID", user)
        + _el("USERPASS", password)
        + _el("LANGUAGE", "ENG")
        + _el("APPID", "FXC")
        + _el("APPVER", "0100")
        + "</SONRQ></SIGNONMSGSRQV1>"
    )


def _secid(symbol: str) -> str:
    unique_id, id_type = security_id(symbol)
    return "<SECID>" + _el("UNIQUEID", unique_id) + _el("UNIQUEIDTYPE", id_type) + "</SECID>"


def _envelope(uid: str, body: str) -> bytes:
    return (_PROLOG.format(uid=uid) + "<OFX>" + body + "</OFX>").encode(ENCODING)


# --------------------------------------------------------------------------- requests


def _order_message_set(
    account: str,
    cl_ord_id: str,
    symbol: str,
    side: str,
    price: Decimal | float | int | None,
    quantity: Decimal | float | int,
    order_type: str = "LIMIT",
) -> str:
    if side not in ("BUY", "SELL"):
        raise ValueError(f"side must be BUY or SELL, got {side!r}")
    if order_type not in ("LIMIT", "MARKET"):
        raise ValueError(f"order_type must be LIMIT or MARKET, got {order_type!r}")
    if order_type == "LIMIT" and price is None:
        # A LIMIT order with no price makes the broker treat it as marketable, skipping its cash
        # check, and then route a priceless limit order. Refuse locally instead.
        raise ValueError("a LIMIT order must carry a LIMITPRICE")

    order = (
        _el("ACCTID", account)
        + _secid(symbol)
        + _el("SIDE", side)
        + _el("UNITS", java_double(quantity))
        + _el("ORDERTYPE", order_type)
    )
    if price is not None:
        order += _el("LIMITPRICE", java_double(price))
    return (
        "<FXCORDMSGSRQV1><FXCORDTRNRQ>"
        + _el("TRNUID", cl_ord_id)
        + "<FXCORDRQ>"
        + order
        + "</FXCORDRQ></FXCORDTRNRQ></FXCORDMSGSRQV1>"
    )


def _book_message_set(trn_uid: str, symbol: str, depth: int) -> str:
    return (
        "<FXCMDMSGSRQV1><FXCMDTRNRQ>"
        + _el("TRNUID", trn_uid)
        + "<FXCMDRQ>"
        + _secid(symbol)
        + _el("DEPTH", int(depth))
        + "</FXCMDRQ></FXCMDTRNRQ></FXCMDMSGSRQV1>"
    )


def build_order_request(
    account: str,
    cl_ord_id: str,
    symbol: str,
    side: str,
    price: Decimal | float | int | None,
    quantity: Decimal | float | int,
    *,
    user: str,
    password: str,
    order_type: str = "LIMIT",
    stamp: str | None = None,
) -> bytes:
    """An OFX order-entry request. ``cl_ord_id`` becomes ``TRNUID`` and must be unique.

    The broker keys its order map on ``TRNUID``, so a duplicate silently overwrites prior state.
    """
    body = _signon(user, password, stamp or dtclient()) + _order_message_set(
        account, cl_ord_id, symbol, side, price, quantity, order_type
    )
    return _envelope(cl_ord_id, body)


def build_book_request(
    trn_uid: str, symbol: str, depth: int, *, user: str, password: str, stamp: str | None = None
) -> bytes:
    """An order-book snapshot request (``FXCMDMSGSRQV1``)."""
    body = _signon(user, password, stamp or dtclient()) + _book_message_set(trn_uid, symbol, depth)
    return _envelope(trn_uid, body)


def build_order_with_book_request(
    account: str,
    cl_ord_id: str,
    symbol: str,
    side: str,
    price: Decimal | float | int | None,
    quantity: Decimal | float | int,
    depth: int,
    *,
    user: str,
    password: str,
    order_type: str = "LIMIT",
    stamp: str | None = None,
) -> bytes:
    """Order **and** book snapshot in one envelope — one round trip instead of two.

    Legal because the two message sets carry different ``MessageSetType``s (``investment`` and
    ``investment_security``), so they do not collide in the envelope's type-ordered set. An order and
    a *statement* request may **not** share an envelope: both are ``investment`` and one is silently
    dropped.
    """
    body = (
        _signon(user, password, stamp or dtclient())
        + _order_message_set(account, cl_ord_id, symbol, side, price, quantity, order_type)
        + _book_message_set(f"{cl_ord_id}-BK", symbol, depth)
    )
    return _envelope(cl_ord_id, body)


def build_statement_request(
    account: str, *, user: str, password: str, stamp: str | None = None
) -> bytes:
    """An investment-statement request — how the harness reads cash and share positions.

    Mirrors ``OfxBrokerClient.fetchPortfolio``: positions plus balances, no open orders.
    """
    uid = f"STMT-{account}"
    body = (
        _signon(user, password, stamp or dtclient())
        + "<INVSTMTMSGSRQV1><INVSTMTTRNRQ>"
        + _el("TRNUID", uid)
        + "<INVSTMTRQ><INVACCTFROM>"
        + _el("BROKERID", "FXC-BROKER")
        + _el("ACCTID", account)
        + "</INVACCTFROM>"
        + "<INCTRAN>"
        + _el("INCLUDE", "N")
        + "</INCTRAN>"
        + _el("INCOO", "N")
        + "<INCPOS>"
        + _el("INCLUDE", "Y")
        + "</INCPOS>"
        + _el("INCBAL", "Y")
        + "</INVSTMTRQ></INVSTMTTRNRQ></INVSTMTMSGSRQV1>"
    )
    return _envelope(uid, body)


# --------------------------------------------------------------------------- responses


@dataclass(frozen=True)
class BookLevel:
    """One aggregated price level from a book snapshot."""

    side: str  # BID | OFFER
    price: float
    size: float


@dataclass(frozen=True)
class OrderOutcome:
    """The broker's verdict on one order."""

    trn_uid: str | None
    status_code: int | None
    message: str | None
    broker_order_id: str | None
    order_status: str | None  # NEW | ROUTED | PARTIALLY_FILLED | FILLED | CANCELLED | REJECTED

    @property
    def accepted(self) -> bool:
        return self.status_code == TXN_OK and self.order_status not in (None, "REJECTED")

    @property
    def rejected(self) -> bool:
        return not self.accepted


@dataclass
class Response:
    """A parsed OFX reply."""

    signon_code: int | None = None
    order: OrderOutcome | None = None
    last_price: float | None = None
    book: list[BookLevel] = field(default_factory=list)
    cash: dict[str, float] = field(default_factory=dict)
    shares: dict[str, float] = field(default_factory=dict)

    @property
    def signon_ok(self) -> bool:
        return self.signon_code == SIGNON_OK


def _strip_prolog(text: str) -> str:
    """Slice from the first ``<OFX`` element.

    The same thing the broker's own reader does: it scans for ``<OFX`` and hands the XML parser a
    document starting there. Doing likewise sidesteps the declared-vs-actual encoding mismatch and
    any prolog processing instructions.
    """
    start = text.find("<OFX")
    if start < 0:
        raise OfxProtocolError(f"no <OFX> element in reply: {text[:200]!r}")
    return text[start:]


def _int_or_none(node: ET.Element | None) -> int | None:
    if node is None or node.text is None or not node.text.strip():
        return None
    try:
        return int(node.text.strip())
    except ValueError:
        return None


def _float_or_none(node: ET.Element | None) -> float | None:
    if node is None or node.text is None or not node.text.strip():
        return None
    try:
        return float(node.text.strip())
    except ValueError:
        return None


def _text_or_none(node: ET.Element | None) -> str | None:
    if node is None or node.text is None:
        return None
    stripped = node.text.strip()
    return stripped or None


def parse_response(data: bytes | str) -> Response:
    """Parse a broker reply. Never raises on a *business* rejection — that is a valid outcome.

    Raises :class:`OfxProtocolError` only for protocol-level failures the caller cannot act on.
    """
    text = data.decode(ENCODING) if isinstance(data, bytes) else data
    root = ET.fromstring(_strip_prolog(text))
    result = Response()

    result.signon_code = _int_or_none(root.find("./SIGNONMSGSRSV1/SONRS/STATUS/CODE"))

    txn = root.find("./FXCORDMSGSRSV1/FXCORDTRNRS")
    if txn is not None:
        body = txn.find("./FXCORDRS")
        result.order = OrderOutcome(
            trn_uid=_text_or_none(txn.find("./TRNUID")),
            status_code=_int_or_none(txn.find("./STATUS/CODE")),
            message=_text_or_none(txn.find("./STATUS/MESSAGE")),
            broker_order_id=_text_or_none(body.find("./BROKERORDERID")) if body is not None else None,
            order_status=_text_or_none(body.find("./ORDERSTATUS")) if body is not None else None,
        )

    book = root.find("./FXCMDMSGSRSV1/FXCMDTRNRS/FXCMDRS")
    if book is not None:
        result.last_price = _float_or_none(book.find("./LASTPRICE"))
        for level in book.findall("./FXCBOOKLVL"):
            side = _text_or_none(level.find("./SIDE"))
            price = _float_or_none(level.find("./PRICE"))
            size = _float_or_none(level.find("./SIZE"))
            if side and price is not None and size is not None:
                result.book.append(BookLevel(side=side, price=price, size=size))

    stmt = root.find("./INVSTMTMSGSRSV1/INVSTMTTRNRS/INVSTMTRS")
    if stmt is not None:
        available = _float_or_none(stmt.find("./INVBAL/AVAILCASH"))
        if available is not None:
            result.cash["USD"] = available
        for position in stmt.iter():
            # Positions nest under POSLIST as POSSTOCK/POSOTHER wrappers; find the inner INVPOS.
            if position.tag != "INVPOS":
                continue
            unique_id = _text_or_none(position.find("./SECID/UNIQUEID"))
            units = _float_or_none(position.find("./UNITS"))
            if not unique_id or units is None:
                continue
            if unique_id.startswith("FX:"):
                result.cash[unique_id[3:]] = units
            else:
                result.shares[unique_id] = units

    return result


def require_order(response: Response) -> OrderOutcome:
    """Return the order outcome, or explain which silent failure occurred.

    This is the guard against the harness reporting success while doing nothing: an HTTP 200 with no
    order message set means either the signon was rejected or a request tag was wrong.
    """
    if not response.signon_ok:
        raise OfxProtocolError(
            f"signon rejected (SONRS/STATUS/CODE={response.signon_code}); "
            "check the OFX user/password against FxcBroker's ofx.user / ofx.password"
        )
    if response.order is None:
        raise OfxProtocolError(
            "reply carried no FXCORDMSGSRSV1 — the broker skipped the order message set, which "
            "means a request tag did not match the names it resolves (check FXCORDMSGSRQV1 / "
            "FXCORDTRNRQ / FXCORDRQ spelling)"
        )
    return response.order


# --------------------------------------------------------------------------- fixtures


_DTCLIENT_RE = re.compile(r"<DTCLIENT>.*?</DTCLIENT>", re.DOTALL)
_BETWEEN_TAGS_RE = re.compile(r">\s+<")


def canonicalize(ofx: bytes | str) -> str:
    """Reduce a document to the form Java and Python must agree on.

    Inter-tag whitespace removed (Java indents with CRLF, this module emits compact) and the one
    genuinely time-varying field neutralised. Mirrors ``OfxGoldenEnvelopeTest.canonicalize``.
    """
    text = ofx.decode(ENCODING) if isinstance(ofx, bytes) else ofx
    text = _DTCLIENT_RE.sub("<DTCLIENT>#</DTCLIENT>", text)
    return _BETWEEN_TAGS_RE.sub("><", text).strip()
