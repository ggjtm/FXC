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

**Verified (2026-08-17) — half wrong.** The all-in-one claim held, but "the plain `require:`
requisites remain correct" did not: on any minion whose run doesn't include the referenced sls, the
requisite is a hard compile error, not an inert hint — see P13 for the fix.

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

## P6 — the artifact publish pipeline doesn't exist yet — **RESOLVED** (2026-08-17)

**Symptom.** Every `installed.sls` reads `fxc:<component>:artifact_url`/`artifact_sha256` from
pillar and expects a fetchable pre-built distribution tarball there. No step in this repo currently
builds and publishes one (`./gradlew :FxcExchange:distTar` exists via the `application` plugin, but
nothing runs it and stages the output anywhere fetchable, and the `loadgen/` equivalent doesn't
exist at all).

**Impact.** Artifact-mode `installed.sls` states cannot converge for real until this exists —
verification stage 3 (masterless real converge) is blocked on it.

**Resolution.** `scripts/publish-artifacts.sh` + the new `fxc.artifact_repo` role. The salt-master
itself is enrolled as a minion (static `/etc/salt/grains` → `roles: [artifact-repo]`) and serves
`/srv/fxc-artifacts` via lighttpd (mod_openssl) at `https://artifacts.mariagrid.ddzone.io/` — a
Route53-hosted name because Let's Encrypt refuses `*.compute.amazonaws.com` by policy; the cert is
certbot dns-01 (`python3-certbot-dns-route53`, instance-role creds), renewed by `certbot.timer` +
a lighttpd-reload deploy hook. The publish script runs Gradle `distTar` for the four Java
components and repacks the output FLAT (the component `archive.extracted` states expect `bin/` +
`lib/` at archive top — no `--strip-components`, contrast P11), tars `loadgen/`'s contents
(pyproject at top, for locust's `pip install -e`), and writes `<name>.tar` + `.sha256` sidecars
under stable names, so the pillar `artifact_url`/`artifact_sha256` values (now filled in
`pillar.example`) never change per build. `top.sls`'s `'*'` → `fxc.common.installed` applying to
the master-minion is fine — it creates only the fxc user and `/opt/fxc`, no JDK. P7's
conf/-layout question is now testable (P7 stays OPEN until verified). The GridGain license is
deliberately NOT hosted in the (world-readable) docroot.

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

## P8 — Tigase systemd unit's `Type=forking`/`PIDFile` assumption is unverified — **VERIFIED OK** (2026-08-17)

**Symptom (anticipated).** `fxc/tigase/files/fxctigase.service.jinja` assumes `scripts/tigase.sh
start` daemonizes and writes `logs/tigase.pid`, matching `Type=forking` + `PIDFile=`. This is a
reasonable guess based on typical Tigase distribution behavior but has not been confirmed against
the actual `8.4.1-b12419` dist tarball's `scripts/tigase.sh`.

**Resolution.** Verify during the first masterless real converge (PLAN.md verification stage 3):
confirm the PID file path and that systemd's default `TimeoutStartSec` is generous enough for
Tigase's actual startup time (which can be tens of seconds — see the `fxc-tigase-ready` retry
loop's 60×3s budget in `running.sls`, itself a guess pending the same verification).

**Verified (2026-08-17).** `Type=forking` + `PIDFile=logs/tigase.pid` work as assumed against the
real 8.4.1-b12419 dist (systemd tracks the daemonized JVM; startup ~24s on a t4g.2xlarge, well
inside default timeouts). The readiness probe next to it was broken for a different reason — P16.

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

**Impact.** Two independent causes, both hitting salt-cloud/bootstrap-installed minions. (1) Salt
3008 (Argon) removed the `mysql_*` execution/state modules from core in the Great Module Migration;
they now live in the `saltext-mysql` PyPI package, which nothing installs. (2) Onedir salt bundles
its own Python, which cannot import distro site-packages — the apt `python3-pymysql` satisfies only
distro-python salt installs. Every `mysql_database`/`mysql_user`/`mysql_grants` state fails,
cascading through Tigase schema load and all app-role DB dependencies.

**Resolution.** `fxc/mariadb/installed.sls` gained `fxc-mariadb-minion-pymysql`: pip-installs
`pymysql` + `saltext-mysql` into `grains['pythonexecutable']` (the minion's own interpreter) with
`reload_modules: true`, guarded by an `unless` import check so it is a no-op where both already
import. The distro package stays (harmless, still right for distro-python pre-3008 installs).
Salt < 3008 simply finds `saltext-mysql`'s modules shadowing its identical in-tree copies.

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
/ `dist_sha256`) when pinning a different build. One more layer of the same onion: with `options`
set, `archive.extracted` does not apply `user:` recursively (the tar output stays root-owned), which
broke `load-schema.sh`'s in-place sed as the service user — a `fxc-tigase-dist-ownership`
`file.directory` recurse state now chowns the tree after extraction.

## P12 — only the root-password state passed MySQL connection credentials — **RESOLVED** (2026-08-17)

**Symptom.** Verifying the P10 fix: `fxc-mariadb-root-password` converges, then every following
`mysql_database`/`mysql_user`/`mysql_grants` state fails with `MySQL Error 1045: Access denied for
user 'root'@'localhost' (using password: NO)`.

**Impact.** Only `fxc-mariadb-root-password` carried `connection_*` args; the other six mysql states
relied on Salt's ambient `mysql.user`/`mysql.pass` config, which nothing (formula or
`pillar.example`) provides. That works exactly once — on a fresh Debian install root authenticates
via unix_socket — and breaks the moment the root-password state does its job, since the follow-on
states then connect passwordless. The `installed.sls` header comment claimed pillar-mapped
connection keys that never existed. The unix-socket path was also hardcoded to Debian's
`/var/run/mysqld/mysqld.sock` (wrong on RedHat).

**Resolution.** All seven mysql states now pass the same explicit `connection_user: root` /
`connection_pass: fxc:mariadb:root_password` / `connection_unix_socket: {{ mariadb.socket }}` args,
with `socket` added to `fxc/mariadb/map.jinja` per os_family. No ambient minion/pillar mysql config
is needed anymore.

## P13 — cross-role `require: sls:` requisites break compilation on split-topology minions — **RESOLVED** (2026-08-17)

**Symptom.** `state.apply fxc.tigase` on the all-in-one test minion (and, by construction, any
highstate on a split-topology tigase/pub/broker/exchange/investor/locust minion): `Data failed to
compile: Referenced state does not exist for requisite [require: (sls: fxc.mariadb.running)]`.

**Impact.** P2 assumed an unresolvable `sls:` requisite is merely inert cross-minion; it is actually
a compile failure. Every `running.sls` carrying a cross-role requisite (pub → tigase/mariadb,
broker → exchange/pub/mariadb, exchange → mariadb, tigase → mariadb, investor/locust → broker) made
its role un-convergeable on any minion not also carrying the referenced role — i.e. the entire
split topology, and any targeted single-tree `state.apply`.

**Resolution.** Each cross-role dependency is now a jinja-guarded *include + require* pair keyed on
the minion's own `roles` grain/pillar: when the dependency role is local, `include: fxc.<dep>`
pulls that tree into the run (so the requisite resolves from ANY entry point — full highstate, the
orchestrate's single-tree `salt.state` applies, or a manual `state.apply fxc.<role>`); when it
isn't, both are omitted and cross-minion sequencing falls to `fxc/orchestrate/demo_stack.sls`,
which was P2's design intent all along. A role-membership guard alone was not enough — the first
attempt kept the bare requisite on all-in-one minions and still compile-failed every single-tree
apply, including the orchestrate's own. Same-tree requisites (`fxc.<role>.installed` from
`fxc.<role>.running`) stay unconditional: the `fxc.<role>` init always includes both.

## P14 — all-in-one pillar topology never localized cross-role addresses — **RESOLVED** (2026-08-17)

**Symptom.** With the P9–P13 fixes in place, Tigase converges, starts under systemd… and crashloops
on `java.net.UnknownHostException: fxc-mariadb-1.internal`: `config.tdsl` was rendered with
`pillar.example/fxc/mariadb.sls`'s split-topology placeholder hostname.

**Impact.** `topology/all-in-one.sls` only set the `roles` list. Every cross-role address in the
`fxc/*.sls` pillar files (`fxc:mariadb:host`, broker's `exchange_host`/`pub_host`/`xmpp_host`,
pub's `xmpp_host`, investor's URLs + `xmpp_host`, locust's three URLs) kept pointing at
`fxc-<role>-1.internal`, so on an all-in-one box every service would fail name resolution at
startup even though the formula's own map.jinja defaults are `localhost` (the explicit example
pillar overrides those defaults, for the worse, in this topology).

**Resolution.** `topology/all-in-one.sls` now overrides all of those keys back to `localhost`
URLs/hostnames; pillar top ordering (`fxc.*` first, topology last) makes them win. Split-topology
`single-role-*.sls` files are unaffected.

## P15 — `load-schema.sh` reported success on a failed schema load — **RESOLVED** (2026-08-17)

**Symptom.** With P14's hostname still broken, `fxc-tigase-schema-loaded` ran `tigase.sh
upgrade-schema` against an unresolvable DB host — the loader failed, but `tigase.sh` exits 0 and the
script's only failure check was one narrow phrasing (`Tigase XMPP Server \(Core\).*error`) that
didn't match, so the script printed "schema load OK", the state succeeded, and the `.schema-loaded`
marker permanently guarded an empty `tigasedb`. Tigase then crashlooped on `Component server
(TigaseCustomAuth) schema version is not loaded in the database or it is old!`.

**Impact.** Any transient DB unavailability during first converge silently produces a
never-retried, permanently broken Tigase. The marker guard (correct in itself) amplifies the
false positive.

**Resolution.** `load-schema.sh.jinja` now asserts the loader's positive completion markers
(`Schema upgrade finished` and `Checking connection to database…ok`, both verified against real
8.4.1-b12419 output) and fails on `error`/`failed` in the summary's per-step status column —
scoped there because the verbose log above it contains those words benignly (a first, broader
grep false-failed a fully successful load). One deliberate tolerance: `Adding XMPP admin accounts`
reporting `error` is downgraded to a loud warning, because re-adding accounts that already exist
reports the (misleading) `Database schema is invalid` — reachable only in a crash-retry window
after a load that already provisioned them, and failing there would wedge the state forever.

## P16 — Tigase readiness probe grepped journald, which the daemonized JVM never writes to — **RESOLVED** (2026-08-17)

**Symptom.** With P9–P15 fixed, Tigase starts and logs `Server finished starting up in (24s)` to
`logs/tigase-console.log`, all four ports listening — but `fxc-tigase-ready` still exhausts its
60×3s retry budget: it grepped `journalctl -u fxctigase`, and under `Type=forking` the detached
JVM's stdout goes to Tigase's own log files, never journald (the journal only carries
`tigase.sh`'s brief wrapper output).

**Impact.** The tigase role could never report converged, even when fully healthy — and everything
downstream that requisites `fxc.tigase.running` (pub, and transitively broker/investor/locust)
stayed blocked in the all-in-one run order.

**Resolution.** The probe now greps `{{ install_dir }}/logs/tigase-console.log` (the same signal
`scripts/demo.sh`'s `wait_for_tigase_ready` uses), guarded by `systemctl is-active` so a stale
success line from a previous boot can't mask a dead service. Relatedly, P8's `Type=forking` +
`PIDFile` assumptions are now verified working against the real 8.4.1-b12419 dist.

## P17 — publish build OOM-thrashed a swapless 1.8 GB master past any timeout — **RESOLVED** (2026-08-17)

**Symptom.** The first real `fxc.artifact_repo.publish` run hit `cmd.run`'s 1800s timeout with no
stderr; after 30 minutes, not one Gradle module had even created its `build/` directory. The
salt-master (t4g.small: 2 vCPU, 1.8 GB, no swap) had ~790 MB available with salt-master +
salt-minion + lighttpd resident, and Gradle's wrapper/daemon JVMs plus the Kotlin-DSL build-script
compiler thrashed against that ceiling indefinitely — no OOM kill, no error, just no progress.

**Root cause.** Memory, not CPU or timeout budget: with a 2 GB swapfile added the identical
`--no-daemon` build completed in **81 seconds** (peak swap use ~300 MB). `--no-daemon` alone
(already in `publish-artifacts.sh`) doesn't save you — Gradle still forks a single-use daemon JVM
to honour JVM settings, plus the Kotlin compiler.

**Impact.** The artifact-repo role — and therefore every artifact-mode `installed.sls` downstream —
could never converge on the smallest sensible master instance, failing in the most expensive way
possible (a silent 30-minute hang per attempt).

**Resolution.** `fxc.artifact_repo.installed` now manages a swapfile (`build_swap` /
`build_swap_size`, default 2G at `/swapfile`, `mount.swap` with `persist: true` so it survives
reboot; falsy size opts out) and `publish.sls` requires it before building. The publish timeout
went to 3600s — with swap that budget is for cold-start dependency downloads, not the build.
`absent.sls` tears the swapfile down. Lesson: a JVM build's failure mode on a memory-starved box
is not an error you can grep for — it's the absence of progress; check `free` before blaming the
build.

## P18 — jdk21_home hardcoded the amd64 path, crash-looping every JDK21 service on arm64 — **RESOLVED** (2026-08-18)

**Symptom.** First all-in-one converge with real artifacts + license: `fxcexchange` and `fxcpub`
enabled but dead, systemd restart-looping on `ERROR: JAVA_HOME is set to an invalid directory:
/usr/lib/jvm/java-21-openjdk-amd64` (broker/investor/locust blocked downstream as requisite
failures). The minion is arm64; the JDK actually installed at `/usr/lib/jvm/java-21-openjdk-arm64`.

**Root cause.** `fxc/map.jinja`'s `jdk21_home` hardcoded the `-amd64` suffix. P9's fix gave
`jdk17_home` (tigase/map.jinja) the `grains['osarch']` suffix treatment; `jdk21_home` was written
earlier and never revisited.

**Resolution.** `'/usr/lib/jvm/java-21-openjdk-' ~ grains['osarch']` on Debian, exactly like
jdk17_home. One fix covers exchange, pub, broker, and investor — all four units template
`JAVA_HOME={{ jdk21_home }}`. Lesson: a launcher script's JAVA_HOME validation turns a path typo
into a clean, greppable journald error — but only the FIRST failing service's journal shows it;
the rest drown as requisite noise.

## P19 — Salt 3008's virtualenv state can't create a venv on a minimal Debian 13 — **RESOLVED** (2026-08-18)

**Symptom.** `fxc-locust-venv` (`virtualenv.managed` with `python: /usr/bin/python3`) raised
`CommandExecutionError: The 'python'('--python') option is not supported by 'venv'`, killing the
whole locust chain.

**Root cause.** The `virtualenv` CLI isn't installed (and isn't a locust_pkgs dependency), so
Salt's virtualenv module silently falls back to stdlib `python -m venv` — which it refuses to pass
`--python` to. The `python:` option was there precisely to keep the venv off the onedir minion's
private interpreter (P10's lesson), making the state self-defeating on exactly the boxes it
targets.

**Resolution.** Replaced with an explicit `cmd.run: /usr/bin/python3 -m venv ...` guarded by
`creates: .venv/bin/pip` (python3-venv was already in locust_pkgs); `pip.installed` keeps working
against the venv via `bin_env`. Same doctrine as the artifact-repo role's aws-CLI choice: when the
Salt module layer is the moving part (3008 onedir), pin the mechanism to the OS's own tools.

## P20 — unquoted systemd Environment= silently dropped every JVM opt after the first — **RESOLVED** (2026-08-18)

**Symptom.** With P18 fixed, fxcexchange died on
`Failed to load license: file:///opt/fxc/exchange/gridgain-license.xml` — the path WITHOUT
`conf/`, i.e. GridNode.licenseUrl()'s working-directory fallback, despite the unit setting
`-Dgridgain.license.url=file://.../conf/gridgain-license.xml`. fxcpub crash-looped identically —
yet converged green (see below).

**Root cause.** `Environment=JAVA_OPTS={{ gridgain_jvm_opts }} -D...` is parsed by systemd as a
space-separated LIST of assignments, not one value: only the first `--add-opens=...` token became
JAVA_OPTS; the remaining 18 add-opens and the license -D were each "Invalid environment
assignment, ignoring:" (the journal says so plainly). The whole value needs quotes:
`Environment="JAVA_OPTS=... ... ..."`.

**Compounding gap.** Pub had no ready probe, so `service.running` (Type=simple: start returns
before the first crash) reported success and the converge stayed green while fxcpub restart-looped
— the failure only surfaced as broker/investor/locust requisite errors pointing at exchange.
Exchange/broker's probes also grepped journald unguarded, so a "started" line surviving from any
earlier boot would have masked a current crash-loop (P16's exact lesson, unapplied here).

**Resolution.** Quoted the `Environment="JAVA_OPTS=..."` line in all three GridGain unit templates;
added the missing fxc-pub-ready probe; prefixed all three probes with
`systemctl is-active --quiet <svc> &&` per P16. Lesson: grep the minion's journal for "Invalid
environment assignment" after any unit-template change — systemd tells you exactly what it threw
away, but only if you look.
