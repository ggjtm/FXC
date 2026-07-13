# FxcExchange — Component Plan

Scoped plan for the exchange (market data, matching, clearing). Companion to the root
[docs/PLAN.md](../../docs/PLAN.md) §Phase 1 and [docs/DESIGN.md](../../docs/DESIGN.md) §4.1.

## Status: **Phase 1 complete** — 26 tests green (19 book + 1 grid + 1 integration + shared common)

Delivered:
- **Matching core** (`book/`): pure-domain price-time-priority `OrderBook` + `MatchingEngine` —
  limit/market orders, partial fills, price improvement, FIFO time priority, tick/lot validation,
  cancel, depth views. Asset-class agnostic (operates on `Instrument`). Exhaustively unit-tested.
- **GridGain** (`grid/`): `GridNode` (isolated single-node, in-memory), `ExchangeTables`
  (`INSTRUMENT`/`ORDERS`/`TRADE`/`SETTLEMENT_OBLIGATION`), `ExchangeRepository`. Boots on JDK 21.
- **Services** (`service/`): `MatchingEngineService`, `MarketDataService` (snapshot + incremental),
  `ClearingService` (nets fills per broker/symbol per cycle via `SettlementProfile`).
- **FIX** (`fix/`): QuickFIX/J 4.4 acceptor (`NewOrderSingle`/`OrderCancelRequest` in,
  `ExecutionReport` out to both trade sides, `MarketDataRequest` → `W`/`X`), `ExchangeServer`, `Main`.
- **Exit criteria met**: `ExchangeIntegrationTest` crosses orders in EUR/USD and ACME, receives
  fills + market data for each, and produces clearing obligations for both asset classes.

## Backlog / next

- [ ] **Formal Ignite Service deployment** (optional): services are currently node-hosted POJOs on
  the data grid (DESIGN §3.1 implementation note). Wrap as `org.apache.ignite.services.Service`
  deployments if/when multi-node scale-out is needed. **Decision pending with Jeremy.**
- [ ] **Dynamic FIX acceptor sessions**: Phase 1 uses fixed `BROKER1`/`BROKER2` sessions; support
  dynamic broker sessions for the demo/multi-broker scenarios.
- [ ] **Self-trade prevention**: a broker crossing its own resting order is currently allowed
  (documented ToDo in `OrderBook`).
- [ ] **ArchiveService** (root Phase 5): drain terminal ORDERS/TRADE/SETTLEMENT_OBLIGATION rows to
  the `fxc_exchange` MariaDB cold schema; keep hot tables bounded.
- [ ] **Market data depth**: currently top-of-book; extend to configurable depth if a client needs it.
- [ ] **Order amend / cancel-replace** (`OrderCancelReplaceRequest`, 35=G) — not yet handled.
- [ ] **BigDecimal on the wire**: FIX price/qty fields are `double`; revisit if precision matters for
  settlement reconciliation.

## Related integration points

- Broker connects here as a FIX **initiator** (order flow) — see FxcBroker Phase 2.
- FxcPub receives **drop-copy** `ExecutionReport`s — see FxcPub Phase 3.
