# fxc formula — Design

## MVP

Turn the FXC repo into a conformant SaltStack formula (`fxc`) that deploys the same simulated
market — FxcExchange, FxcBroker, FxcPub, FxcInvestor, MariaDB, Tigase, and the Locust load harness
— onto a fleet of AWS EC2 minions, natively (JDK + systemd, no containers), sequenced the same way
`scripts/demo.sh` sequences it locally: MariaDB → Tigase → Exchange → Pub → Broker →
Investor/Locust. The MVP is done when `salt-cloud -m cloud.maps.d/fxc-fleet.map` stands up the
default 6-instance fleet, `salt-run state.orchestrate fxc.orchestrate.demo_stack` converges every
minion in dependency order, and `salt -G 'roles:exchange' fxc_exchange.open` plus a short Locust
run produces fills — the same outcome `scripts/demo.sh` already proves on a laptop.

This is additive to, not a replacement for, the existing `docker-compose.yml` demo path. Both are
expected to keep working independently.

## Confirmed decisions

- **Artifact strategy**: each Java component and the Locust harness are deployed from a **pre-built
  distribution tarball** (`fxc:<component>:artifact_url` / `artifact_sha256` in pillar), not built
  from source on the minion. The build/publish step that produces these tarballs does not exist yet
  — see `fxc/docs/PLAN.md` item 11 and `PROBLEMS.md`.
- **Infra components**: MariaDB, Tigase, and Locust are managed as **native packages/services**, the
  same as the four Java components — not Docker. `docker-compose.yml` is untouched; it remains the
  local dev/demo path, entirely independent of this formula.
- **AWS topology**: the default `cloud.maps.d/fxc-fleet.map` fleet is **one EC2 instance per role**
  (mariadb, tigase, exchange, pub, broker, investor+locust — 6 instances). The pillar/grain `roles`
  list mechanism supports collapsing onto an all-in-one instance any time (`cloud.profiles.d`'s
  `fxc-all-in-one` profile), with no `top.sls` changes required either way.

## JDK split

Per root `README.md`'s "JDK requirements" table: FxcExchange/FxcBroker/FxcPub/FxcInvestor need JDK
21 (`fxc.common.jdk21`); Tigase needs JDK 17 (`fxc.common.jdk17`) because its bundled Groovy/ASM
cannot read JDK 21+ class files. The default split topology gives Tigase its own EC2 instance, so
the two JDKs never need to coexist on one host. This *would* become a real concern under the
all-in-one topology — flagged, not solved, in `PROBLEMS.md`.

## State tree pattern

Every `fxc/<component>/` follows: `map.jinja` (os-family-keyed defaults, pillar-overridable) →
`init.sls` (includes `installed` + `running`, the ref used elsewhere as bare `fxc.<component>`) →
`installed.sls` (noun+adjective **installed**: idempotent deploy, does not start anything) →
`running.sls` (**running**: `service.running` + `watch` + cross-component `require`) →
`stopped.sls` / `absent.sls` (the rest of the noun+adjective set) → `files/` (every template/asset).
`fxc/common/` holds the shared service user/group/base-dir plus the two JDK states.

## Execution modules — naming deviation

Salt execution modules do **not** support the state tree's dotted directory namespacing — a
module's callable name is fixed by its filename/`__virtualname__`. There is no way to address a
literal `fxc.exchange.open`; the practical equivalent is one flat module per component named
`fxc_<component>` (`_modules/fxc_exchange.py`, `fxc_broker.py`, `fxc_investor.py`, plus the
supporting `fxc_mariadb.py`), giving refs like `fxc_exchange.open`. This is a forced, accepted
deviation from the state tree's dotted style — see `PROBLEMS.md`.

Function names are verbs/verb+adverb, matching the imperative, non-idempotent actions they wrap:
`fxc_exchange.open`/`close` (aliased `resume`/`halt`) and `clear_book`, `fxc_broker.start_trading`/
`stop_trading`, `fxc_investor.enable_agent`/`disable_agent`/`reset_hard`, `fxc_mariadb.
truncate_archives`. None of these are folded into `running.sls` — opening the market, for example,
is a deliberate operator action (`session.startClosed=true` is intentional cold-start behavior),
not something convergence should silently do.

## Ordering and readiness

Plain `require:`/`watch:` requisites inside a component's `running.sls` order states **within one
minion's own state run only** — they cannot block one minion's convergence on another minion
actually being ready, which is the normal case under the split topology. Real cross-minion
sequencing is `fxc/orchestrate/demo_stack.sls` (`salt-run state.orchestrate fxc.orchestrate.
demo_stack`), translating `scripts/demo.sh`'s dependency order into `salt.state` requisites keyed
by the `roles` grain.

"Started" is not "actually ready": `demo.sh`'s log-grep/port-wait logic exists because Tigase in
particular opens its ports well before it can serve a stream. Where this can be expressed
declaratively (`fxc/tigase/running.sls`, `fxc/exchange/running.sls`), a `cmd.run` with `retry:`
reproduces the same log-grep check as a Salt idiom. Opening the market is explicitly **not**
one of these — it stays a separate, deliberate step (`fxc_exchange.open`), mirroring `demo.sh`'s
own separation of "stack up" from "market open."

## Cross-component hostnames

In the split topology, `fix.exchange.host`, `fix.pub.host`, `xmpp.host`, `ofx.broker.url`, etc. must
resolve to the actual role-carrying minions' hostnames, not `localhost`. This formula resolves them
via pillar keys set per-environment (`pillar.example/fxc/*.sls`'s `exchange_host`/`pub_host`/
`xmpp_host` etc.) rather than Salt Mine, to keep the first cut simple — using Salt Mine to resolve
these automatically from the fleet's own grains/IPs is a natural follow-up (see `docs/stories/`).

## Secrets handling

All passwords (MariaDB root/app/tigase, XMPP service accounts, OFX signon) move from the
plaintext `conf/*.conf`/`docker-compose.yml` literals into pillar. `pillar.example/` ships key
*names* with `CHANGEME` placeholders, never real values. The GridGain license
(`gridgain-license.xml`) is a whole file, not a scalar: `fxc:gridgain:license_source` holds a
`salt://`, `s3://`, or `https://` URL consumed by `file.managed`, consistent with the existing
`.gitignore` treatment of the real license file — it is never committed to this formula.

## Security-group / dependency table

| Role | Listens on | Reachable from |
|---|---|---|
| mariadb | 3306 | exchange, broker, pub, investor SGs |
| tigase | 5222 (c2s), 5269 (s2s), 5280 (BOSH/WS), 8080 (admin) | pub, broker, investor SGs |
| exchange | 9876 (FIX), 8090 (feed HTTP), 8091 (feed WS) | broker, pub SGs + operator IP |
| pub | 9878 (FIX drop-copy) | broker SG |
| broker | 8082 (OFX), 8083 (console) | investor/locust SG + operator IP |
| investor / locust | 8089 (Locust UI, investor role only) | operator IP |
| artifact-repo (salt-master) | 443 (HTTPS), 80 (redirect only) | world-OK: public, non-secret artifacts (the GridGain license is deliberately NOT hosted here); at minimum every role SG + operator IP |

## Deferred / out of scope for this pass

- Salt Mine-based automatic hostname resolution across roles (currently static pillar values).
- kitchen-salt or equivalent automated formula testing (see `docs/PLAN.md`'s verification section).
- Running more than one FxcInvestor agent per minion (currently one `fxcinvestor.service` per
  investor-role minion; `scripts/demo.sh`'s two-agent behavior is approximated by two separate
  investor-role instances, not two services on one).
