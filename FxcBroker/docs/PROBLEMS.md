# FxcBroker — Problems & Risk Log

Component-scoped risks. Project-wide risks live in the root [docs/PROBLEMS.md](../../docs/PROBLEMS.md).
Status per entry: **OPEN**, **RESOLVED**, or **MITIGATED**.

---

## B1 — OFX4J server side is thin — **RESOLVED (Phase 2)**

Root PROBLEMS.md P3 / DESIGN §6.5. Confirmed: we bypass the Jakarta `OFXServlet` and drive
`AggregateMarshaller`/`AggregateUnmarshaller` from our own `com.sun.net.httpserver` handler
(`OfxHttpServer` + `OfxCodec`), hand-building every response aggregate in `OfxService`. Gotcha
found: **`OFXV2Writer` buffers and only flushes trailing close-tags on `close()`** — marshalling
without closing yields truncated XML (`OfxCodec` always closes). Also, the marshaller **enforces
`required=true`** elements (e.g. signon `APPID`/`APPVER`, statement `CURDEF`/`INCPOS`), so every
aggregate must be fully populated.

## B2 — Custom order-entry aggregates are package-locked — **RESOLVED (Phase 2)**

Root PROBLEMS.md P4 / DESIGN §6.4. Placed the `FXCORDMSGSRQV1`/`RSV1` aggregates under
`com.webcohesion.ofx4j.domain.data.fxc` (in FxcBroker) so the introspector's package scan resolves
them on unmarshal. Verified by `OfxOrderRoundTripTest` (envelope marshal → unmarshal preserves all
fields). They reuse the `investment` `MessageSetType` slot (no collision — order and statement
requests are separate). **Phase 4 note:** FxcInvestor needs these same classes to unmarshal the
order *response*; relocate them to a shared location (fxc-common or fxc-grid-style module) then.

## B3 — FX positions in OFX are awkward — **OPEN**

Root PROBLEMS.md / DESIGN §6.6. Equities map natively to `POSSTOCK`/`STOCKINFO` (CUSIP); FX pairs
map to `POSOTHER`/`OtherPosition` with a synthetic `SECID` (e.g. `UNIQUEID=FX:EURUSD`). Confirm the
mapping renders sensibly in a real OFX client during Phase 2.

## B4 — XMPP publication leg blocked by Tigase hold — **OPEN**

The fill-status XMPP post (Smack bot) depends on FxcPub/Tigase, which is on hold pending AGPLv3
acceptance (root PROBLEMS.md P2). The **FIX drop-copy** leg to FxcPub's FIX acceptor is independent
and can proceed with a stub receiver. Sequence the XMPP leg after the Tigase decision.

## B5 — Static dev credentials — **OPEN (low)**

OFX signon uses static dev credentials initially (root DESIGN §6.7 auth realism). Fine for the demo.

## B6 — HTTP transport for OFX — **RESOLVED (Phase 2)**

Used the JDK's built-in `com.sun.net.httpserver.HttpServer` (`OfxHttpServer`) — no web framework,
no new dependency. Javalin stays reserved for the Phase-7 gateway.

## B7 — GridGain `GridNode` duplicated across components — **OPEN (low, tech-debt)**

`com.fxc.broker.grid.GridNode` is a near-copy of `com.fxc.exchange.grid.GridNode` (and FxcPub will
want the same). Extract a shared `fxc-grid` module (depended on by Exchange/Broker/Pub, not by
Investor/common) to avoid drift. Deferred to keep Phase 2 focused; low risk (~50 lines).

## B8 — FIX decimals arrive as doubles; column scale mismatch — **RESOLVED (Phase 2)**

FIX transports price/qty as `double`, whose string form can carry long fractional scales (e.g.
scale 12–16 after `qty*price`). The GridGain columns are `DECIMAL(_,8)`, so persistence threw
`Value for a column ... out of range. Maximum scale : 8`. Fixed by clamping to scale 8 at the
boundaries: `BrokerFixClient.decimal(...)` on parse, and `AccountService` on every position/cash
mutation. (Relates to FxcExchange E4 — precision over FIX.)
