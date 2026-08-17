# fxc formula — Problems

Format follows the repo convention (root `docs/PROBLEMS.md`): `## P<n> — <title> — **STATUS**`
(OPEN/RESOLVED/MITIGATED/AVOIDED), discovered-date, symptom/root-cause/impact/resolution/lesson as
applicable. All entries below were identified during design/authoring, not runtime, since the
formula has not yet been exercised against live infrastructure (see PLAN.md's verification stages).

## P1 — execution modules can't get the dotted `fxc.exchange.open` namespace — **AVOIDED** (2026-08-14)

**Discovered:** while designing `_modules/`, translating the user's requested naming
(`fxc.exchange.open`/`fxc.exchange.close`) into actual Salt execution-module files.

**Root cause.** Salt state `.sls` refs get dotted namespacing for free (directory structure IS the
namespace: `fxc/exchange/installed.sls` → `fxc.exchange.installed`). Execution modules have no
equivalent — a module's callable name is fixed to its filename/`__virtualname__`, so `fxc.py`
containing a nested `exchange.open` is not addressable that way; Salt would parse `fxc` as the
module and `exchange.open` as an invalid function reference.

**Resolution.** One flat module per component, named `fxc_<component>` (`fxc_exchange.py`,
`fxc_broker.py`, `fxc_investor.py`, plus supporting `fxc_mariadb.py`), giving refs like
`fxc_exchange.open`. Documented as a forced, accepted deviation in DESIGN.md rather than treated as
an oversight.

**Lesson.** Don't assume state-tree conventions transfer to execution modules; verify against
Salt's actual loader mechanics before committing to a naming scheme in a plan.

## P2 — cross-minion ordering: `require:` doesn't reach other minions — **MITIGATED** (2026-08-14)

**Symptom (anticipated).** `fxc/broker/running.sls`'s `require: [sls: fxc.exchange.running]` reads
like it would block the broker's converge until the exchange is up — but under the split topology
those are two different minions, and `require:` only orders states within one minion's own
`state.apply`.

**Resolution.** `fxc/orchestrate/demo_stack.sls`, a `state.orchestrate` target using `salt.state`
with `tgt`/`tgt_type: grain` per role, sequences convergence across minions in the same dependency
order. The plain `require:` requisites in each `running.sls` remain correct and are what makes the
all-in-one topology (one state run, one minion) work without orchestration at all.

**Verification needed.** Not yet run against a real fleet — this is a design-time mitigation, not a
verified one. First real salt-cloud smoke test (PLAN.md item 12/verification stage 4) is where this
gets proven.

## P3 — "started" is not "actually ready" — **MITIGATED (by design), not eliminated** (2026-08-14)

**Symptom (anticipated).** `service.running` succeeding only means systemd accepted the start
request, not that the process is ready to serve — `scripts/demo.sh`'s existing log-grep/port-wait
logic exists precisely because Tigase opens its ports before it can serve a stream, and FxcExchange/
FxcBroker log a `"... started"` line only once GridGain + FIX + feed services are all up.

**Resolution.** `fxc/tigase/running.sls` and `fxc/exchange/running.sls` add a `cmd.run` with
`retry:` that greps `journalctl` for the same marker strings `demo.sh` waits on. `fxc/broker/
running.sls` and `fxc/pub/running.sls` do not (yet) have an equivalent — broker's log-grep
equivalent was scoped out of this pass since `demo.sh`'s broker readiness check (`wait_for_log ...
"FxcBroker started"`) is straightforward to add the same way; tracked as a follow-up rather than
done now.

**Explicitly out of scope:** market-opening. `session.startClosed=true` is deliberate; opening the
market is a separate action (`fxc_exchange.open`), not something any `running.sls` should do
automatically.

## P4 — Tigase's config-rewrite-on-startup vs. Salt's `file.managed` — **OPEN** (2026-08-14)

**Symptom (anticipated).** `docker/tigase/Dockerfile`'s comment states Tigase "rewrites/backs up its
config on startup," which is why the Docker path bakes `config.tdsl` into the image at build time
rather than bind-mounting it (a bind-mount rewrite failed outright). The native path renders
`config.tdsl` via `file.managed`, which will keep re-asserting the Salt-rendered template on every
highstate — this will silently overwrite whatever Tigase itself wrote back to that file at its last
startup.

**Impact (unknown until verified).** If Tigase's rewrite is purely cosmetic (reformatting,
normalizing), Salt clobbering it back to the canonical template on every highstate is actually the
desired config-as-code behavior. If the rewrite carries meaningful runtime state (e.g. anything
beyond what `upgrade-schema` already persists to MariaDB), repeated highstates could lose it.

**Resolution.** Not yet determined — needs an empirical test: start Tigase natively, diff
`config.tdsl` before/after its first startup, and decide whether `file.managed`'s reassertion is
safe or whether the state needs a "manage once, then hands off" pattern (e.g. `unless: test -f
{path}` on the initial `file.managed` only, forgoing drift correction after first boot).

## P5 — JDK 17/21 coexistence risk under the all-in-one topology — **OPEN** (2026-08-14)

**Symptom (anticipated).** The default split topology gives Tigase its own EC2 instance, so
`fxc.common.jdk17` and `fxc.common.jdk21` never need to coexist. The all-in-one topology
(`cloud.profiles.d`'s `fxc-all-in-one` profile / `roles: [mariadb, tigase, exchange, ...]` on one
minion) installs both JDKs on one host — each component's systemd unit sets its own
`Environment=JAVA_HOME`, which should keep them from stepping on each other via `update-alternatives`
defaults, but this has not been verified.

**Resolution.** Deferred until the all-in-one topology is actually exercised (it is not the default
and no verification stage currently targets it specifically). Tracked here so it isn't forgotten
when someone does pick that topology.

## P6 — the artifact publish pipeline doesn't exist yet — **OPEN** (2026-08-14)

**Symptom.** Every `installed.sls` reads `fxc:<component>:artifact_url`/`artifact_sha256` from
pillar and expects a fetchable pre-built distribution tarball there. No step in this repo currently
builds and publishes one (`./gradlew :FxcExchange:distTar` exists via the `application` plugin, but
nothing runs it and stages the output anywhere fetchable, and the `loadgen/` equivalent doesn't
exist at all).

**Impact.** Artifact-mode `installed.sls` states cannot converge for real until this exists —
verification stage 3 (masterless real converge) is blocked on it.

**Resolution.** Not started. Tracked as `docs/PLAN.md` Phase 8 item 11 (root) / this file's PLAN.md
item 11.

## P7 — Gradle `application`-plugin distributions may not bundle `conf/` — **OPEN** (2026-08-14)

**Symptom (anticipated).** Every `fxc/<component>/files/*.service.jinja` unit sets
`WorkingDirectory={{ install_dir }}` and relies on `FxcConfig` resolving `conf/<name>.conf` relative
to that CWD — which matches how `./gradlew :Module:run` already works (Gradle sets the module
directory, containing `conf/`, as CWD). But Gradle's `application` plugin does not automatically
bundle an arbitrary `conf/` resource directory into its `distTar`/`installDist` output; that output
normally contains only `bin/` and `lib/`.

**Impact.** If the eventual publish step (P6) doesn't separately arrange for `conf/` to exist
alongside the unpacked artifact, `fxc-<component>-conf`'s `file.managed` target
(`{{ install_dir }}/conf/<name>.conf`) still gets created by Salt regardless (Salt creates it
itself, independent of what the tarball contains) — so this is likely a non-issue in practice, but
it's an assumption that should be confirmed once a real artifact is built, not asserted confidently
here.

**Resolution.** Verify once P6 exists: does the unpacked artifact's directory layout conflict with
Salt's independently-managed `conf/` subdirectory in any way (e.g. a distribution that assumes
`conf/` doesn't exist and creates its own default one)? If so, add `distributions { main { contents
{ from("conf") { into("conf") } } } }`-style wiring to the relevant `build.gradle.kts`, or confirm
Salt's independently-managed copy is sufficient as-is.

## P8 — Tigase systemd unit's `Type=forking`/`PIDFile` assumption is unverified — **OPEN** (2026-08-14)

**Symptom (anticipated).** `fxc/tigase/files/fxctigase.service.jinja` assumes `scripts/tigase.sh
start` daemonizes and writes `logs/tigase.pid`, matching `Type=forking` + `PIDFile=`. This is a
reasonable guess based on typical Tigase distribution behavior but has not been confirmed against
the actual `8.4.1-b12419` dist tarball's `scripts/tigase.sh`.

**Resolution.** Verify during the first masterless real converge (PLAN.md verification stage 3):
confirm the PID file path and that systemd's default `TimeoutStartSec` is generous enough for
Tigase's actual startup time (which can be tens of seconds — see the `fxc-tigase-ready` retry
loop's 60×3s budget in `running.sls`, itself a guess pending the same verification).

## P9 — Debian 13 (trixie) has no `openjdk-17-jdk` package — **RESOLVED** (2026-08-17)

**Symptom.** First real all-in-one highstate (fxc-demo-1, Debian 13 arm64, salt-cloud): `fxc-jdk17`
fails with `E: Unable to locate package openjdk-17-jdk`, cascading through the whole Tigase chain.
Trixie's archive ships only `openjdk-21-jdk` and `openjdk-25-jdk` — JDK 17 was dropped.

**Impact.** The Tigase role (which is pinned to JDK 17, see P5 and `fxc/common/jdk17.sls`'s header
comment) cannot converge on any Debian ≥ 13 minion, in both split and all-in-one topologies.

**Resolution.** `fxc/map.jinja` now switches `jdk17_pkg` to `temurin-17-jdk` on Debian ≥ 13 (unless
pillar overrides `fxc:common:jdk17_pkg`), and `fxc/common/jdk17.sls` manages the Adoptium apt repo
(`packages.adoptium.net`, per-codename, signed-by keyring) when that switch is active.
`fxc/tigase/map.jinja`'s `jdk17_home` follows (`/usr/lib/jvm/temurin-17-jdk-<arch>`) — which also
fixed its previously hardcoded `-amd64` suffix that was wrong on arm64 minions. Residual risk: the
Adoptium repo is a new external dependency; air-gapped deployments must mirror it or pillar-override
`jdk17_pkg`.

## P10 — `mysql_*` states unavailable on bootstrap-installed (onedir) minions — **RESOLVED** (2026-08-17)

**Symptom.** Same first highstate: `fxc-mariadb-root-password` fails with `State 'mysql_user.present'
was not found in SLS 'fxc.mariadb.installed'`, even though `python3-pymysql` installed fine.

**Impact.** Salt-cloud/bootstrap-installed minions run onedir salt with a bundled Python that cannot
import distro site-packages — the apt `python3-pymysql` satisfies only distro-python salt installs.
Every `mysql_database`/`mysql_user`/`mysql_grants` state fails, cascading through Tigase schema load
and all app-role DB dependencies.

**Resolution.** `fxc/mariadb/installed.sls` gained `fxc-mariadb-minion-pymysql`: pip-installs
`pymysql` into `grains['pythonexecutable']` (the minion's own interpreter) with `reload_modules:
true`, guarded by an `unless: import pymysql` so it is a no-op where the driver already imports.
The distro package stays (harmless, still right for distro-python installs).

## P11 — Tigase dist tarball fetch had no `source_hash` and extracted one level too deep — **RESOLVED** (2026-08-17)

**Symptom.** Same first highstate: `fxc-tigase-dist` fails with `Unable to verify upstream hash of
source file …tigase-server-8.4.1-b12419-dist.tar.gz, please set source_hash…` — Salt refuses remote
archives without a pinned hash. Fixing that exposed a second, latent bug: the tarball nests
everything under `tigase-server-8.4.1-b12419/`, while `if_missing`, `fxc-tigase-config`
(`etc/config.tdsl`), the scripts perms state, and the systemd unit all assume content directly in
`install_dir` — the state lacked the `--strip-components=1` its reference implementation
(docker/tigase/Dockerfile line 25) uses.

**Impact.** Tigase could never converge: first hard-fail on the hash, then (had that passed) every
follow-on state would miss its paths and the un-stripped extract would re-run each highstate
(`if_missing` never satisfied).

**Resolution.** `fxc/tigase/map.jinja` pins `dist_sha256`
(`d38e97613eec8b9e9be641b89c5397e60ef4b5db2eb0d83839fb33d803416e5d`, computed from the fetched
vendor tarball) and `installed.sls` passes `source_hash` plus `options: z --strip-components=1`,
mirroring the Dockerfile. Pillar can override both url and hash together (`fxc:tigase:download_url`
/ `dist_sha256`) when pinning a different build.
