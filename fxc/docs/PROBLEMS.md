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

## P4 — Tigase's config-rewrite-on-startup vs. Salt's `file.managed` — **RESOLVED** (2026-08-18)

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

**Resolution.** Empirically settled on the live all-in-one converge (2026-08-18): the rewrite is
purely cosmetic — Tigase alphabetizes the keys, reflows blank lines, and materializes default
`seeOtherHost {}` beans; no values or runtime state are added — so clobbering loses nothing. But
the converse cost was real: `file.managed` diffed on EVERY converge and its `watch` bounced a
healthy XMPP server each highstate. Neither of P4's original two options fits ("reassert+restart
forever" churns; "manage once, hands off" forgoes config-as-code), so the state now does both:
the template renders to `etc/config.tdsl.salt` (stable — only pillar/template edits change it),
and a `cmd.run` copies it over the live `config.tdsl` only when the render differs from the
`.config.tdsl.applied` snapshot of what was last pushed (or the live file is missing). Tigase may
reformat the live file freely; pillar changes still land and restart the service via the watch on
the copy. Lesson: "does the daemon rewrite its own config?" is not a yes/no gate but a
three-sided contract between the daemon, the config manager, and the restart trigger — the
trigger must key on what YOU changed, never on what the daemon touched.

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

## P21 — Salt 3008 masks pillar.get returns, breaking every fxc_* execution module — **RESOLVED** (2026-08-18)

**Symptom.** `salt 'fxc-demo-1' fxc_exchange.status` (and open/close, and fxc_broker.*) returned
`[Errno -2] Name or service not known` on a fully converged, locally-curl-able stack. Debug logs
showed the request going to `http://**********:8090/api/status` — ten literal asterisks.

**Root cause.** Salt 3008 ships `pillar_mask_output: True` by default: `pillar.get`/`pillar.items`
return `**********` in place of every STRING value — including the caller-supplied default for a
missing key — except inside state rendering (a context flag exempts it, which is why all 87 states
converge with real values while the same call in an execution module gets the mask). Ints pass
through unmasked, which made the failure maximally confusing: `feed_http_port` came back 8090,
`local_host` came back as asterisks, and the module built a URL around a mask.

**Impact.** Every operator action the formula exposes through `_modules/` (market open/close,
broker trading gate, investor reset) was unusable via the salt CLI, i.e. the exit-criteria step
`salt -G 'roles:exchange' fxc_exchange.open` itself.

**Resolution.** The modules now read `__pillar__` directly via
`salt.utils.data.traverse_dict_and_list(..., delimiter=":")` (a `_pillar_get` helper) — the raw
pillar dict is never masked, and this stays portable to pre-3008 minions (unlike passing 3008's
new `unmask=True` kwarg). Lesson: when a value that "cannot possibly be wrong" (a hardcoded
default!) misbehaves, print the exact string the code received — ten asterisks look like display
masking right up until `len()` says the data itself is the mask.

## P22 — locust's unit env omitted the OFX credentials, so every swarm request signon-failed — **RESOLVED** (2026-08-18)

**Symptom.** First real investor-tier run: market OPEN, all services healthy, locust swarming —
and 100% of `ofx-order`/`ofx-statement` requests fail with `signon rejected (SONRS/STATUS/
CODE=15500)`. Zero trades reach the exchange.

**Root cause.** `fxclocust.service.jinja` mirrored docker-compose's locust env contract — which
never passes `FXC_OFX_USER`/`FXC_OFX_PASSWORD` because the compose stack's broker and loadgen
both happen to use the dev defaults (`investor`/`secret`). The formula's broker reads
`ofx.password` from pillar (`fxc:broker:ofx_password`), so the moment that pillar value differs
from `secret` — which is the entire point of making it pillar-driven — locust's built-in default
can no longer sign on, and there was no env var in the unit to tell it otherwise.

**Impact.** The locust investor tier (PLAN's exit-criteria load harness) could not produce a
single fill against any non-dev broker credential set.

**Resolution.** `fxc/locust/map.jinja` gains `ofx_user`/`ofx_password`, DEFAULTED to the broker's
own pillar keys (`fxc:broker:ofx_*`) — pillar top.sls applies every component's pillar file to
every minion precisely so cross-component contracts can be read like this; an explicit
`fxc:locust:ofx_*` override still wins. The unit template exports both as env vars. Lesson: when
a formula mirrors a compose file's env contract, mirror the CONTRACT (every knob the consumer
reads), not the subset the compose file happened to set — anything the compose stack agreed on
implicitly via shared defaults becomes an invisible coupling the moment one side goes
pillar-driven.

## P23 — the tradeable universe was hardcoded in two languages — **RESOLVED** (2026-08-19)

**Symptom.** Re-pointing the demo at 25 fictitious stocks looked like a pillar edit and was not.
`InstrumentCatalog.defaults()` returned a hardcoded `List.of(...)` of 4 FX pairs + 3 equities, and
`loadgen/fxc_loadgen/instruments.py` mirrored the same seven by hand. Nothing read the symbol set
from config, and `fxc:locust:symbols` merely *chose* from it.

**Impact.** Every consumer keys off the catalog — the exchange lists books from it, the broker
subscribes market data and validates orders against it, all four strategies snap ticks through it —
so a symbol absent from it is rejected by the matching engine **asynchronously over FIX**, while the
OFX reply still says ROUTED. A pillar-supplied symbol the catalog didn't know would have produced
load that looked accepted and never traded: the hardest failure mode in this system to see.

**Resolution.** `InstrumentCatalog.LISTINGS` is now the single table (symbol, issuer, reference
price), with `defaults()`/`bySymbol()`/`REFERENCE_PRICES` derived from it once at class-init rather
than rebuilt per call — `find()` is on a per-order hot path and used to allocate a fresh map every
time. `resolveSymbols(spec)` accepts `*`/blank for "every listed equity" and validates anything else,
so the 25-name list never appears in a conf file, a Salt pillar, the systemd unit, or
docker-compose; `FXC_SYMBOLS=`, `account.seedSymbol=*` and `agent.symbol=*` all resolve from the
catalog. FX pairs are de-listed but `FxSpotInstrument`, the `CURRENCY_EXCHANGE` settlement style and
the OFX `FX:` wire branch stay compiled and unit-tested — they are the second `Instrument`
implementation that keeps the abstraction honest. The Python mirror is now pinned symbol-by-symbol
*and price-by-price* by `loadgen/tests/test_instruments.py`, so cross-language drift is a test
failure rather than a book that prices itself wrongly. Lesson: "make it configurable" is a claim
about the whole consumer chain, not about the one file you were reading — `InstrumentCatalog`'s own
javadoc had promised "loading from config is a straightforward extension" since the first commit.

## P24 — cash-only account opening cannot work with more than one listed symbol — **RESOLVED** (2026-08-19)

**Symptom.** With 25 books and 512 rando investors, 24 books would never trade. Rando coin-flips
BUY/SELL over a randomly chosen symbol, but `account.open.seedShares` defaulted to 0, so every
opened account was cash-only and every SELL came back `insufficient shares for equity sell (no
shorting)`. No investor could quote an ask; buys rested unmatched forever.

**Root cause.** Cash-only opening was a deliberate fix for the root `docs/PROBLEMS.md` P19 (260
agents minting 260,000 shares against a 2,000-share float pushed the price down 23%), and
`account.seedSymbol` was a scalar, so the float existed for exactly ONE symbol anyway. Both
assumptions were load-bearing only while the universe had a single tradeable name.

**Resolution.** The float is issued and placed **per symbol** (`account.issue.shares` /
`account.mm.shares` are per listing, not divided — dividing would leave each name too thin for 512
investors and would silently dilute every existing name whenever a 26th listed), and opened accounts
now receive shares in every listed symbol **transferred off the issuer's reserve** rather than
minted. That keeps P19's invariant — market-wide outstanding stays exactly what was issued no matter
how many investors spawn — while giving all 25 books two sides from the first tick. Minting survives
only as a logged fallback when the reserve is exhausted. `AccountOpeningPolicy` grew `seedSymbols`
(list), a nullable `seedSharePrice` (null = each symbol's catalog price) and `seedFromAccount`;
`Main` validates `2 x mm <= issued` *before* the listener opens, because with 25 symbols an
over-allocation would otherwise die on symbol 4 and leave a half-seeded market behind a live port.
`AccountOpeningTest` now asserts the stronger invariant (issuer + investors == issued) instead of
"investors minted nothing". Lesson: a rule ("accounts are cash-only") outlives the reason for it;
when the premise changes, re-derive the rule rather than defending it.

## P25 — the publish script's "atomic" staging was a cross-device copy — **RESOLVED** (2026-08-19)

**Symptom.** `scripts/publish-artifacts.sh` staged into `mktemp -d` (i.e. `/tmp`, on the master's
root fs) and then `mv`'d each tarball into `/srv/fxc-artifacts`, which is a separate 23 GB ZFS pool.
Its own comment claimed "Per-file atomic-ish: rename tar then its sidecar".

**Impact.** A cross-filesystem `mv` is copy-then-unlink, not `rename(2)`. A minion fetching during a
publish could read a partially written 64 MB tarball — and would fail its `source_hash` check, which
looks exactly like a corrupt artifact rather than a race. Secondarily, ~250 MB of the publish landed
on the 8 GB root fs, which was already at 99%.

**Resolution.** Stage into a temp dir alongside the docroot, so the `mv` is a genuine same-filesystem
rename and the build's byproducts stay off root. Lesson: "atomic rename" is a property of a
filesystem boundary, not of the `mv` command — and a comment asserting atomicity is worth checking
against `df` the moment the docroot moves.

## P26 — ABBA deadlock between AccountService and PnlService wedged the whole broker — **RESOLVED** (2026-08-19)

**Symptom.** Ramping the Locust swarm from 32 to 512 investors wedged the broker completely within
seconds: the console stopped answering, every OFX request hung (locust showed 512 users and
*0* requests, because in-flight requests never complete), and the broker's FIX session to the
exchange died with `Timed out waiting for logon response` every 11 seconds forever. The host was
idle throughout — load 0.06, 25 GB free — which is what ruled out saturation and pointed at a lock.

**Root cause.** A textbook ABBA deadlock, confirmed by `jstack` ("Found one Java-level deadlock"):

- FIX thread, on a fill: `OmsService.onExecutionReport` (locks `OmsService`) →
  `PnlService.onFill` (locks **PnlService**) → `PnlService.equity` → `AccountService.positions`
  → wants **AccountService**.
- Console thread, on an account open: `AccountService.openAccount` (locks **AccountService**) →
  `AccountOpenedListener` → `PnlService.onAccountOpened` → wants **PnlService**.

Two monitors, two orders. **This was latent long before this change** — `openAccount` had always
notified its listeners while holding its own monitor — but it only ever held that monitor for a
single position write, so the collision window was microseconds and nobody hit it. Seeding opened
accounts across 25 symbols (P24) turned one write into ~50 under the same lock, and with 512
accounts opening while fills were already flowing the two threads met almost immediately.

**Impact.** Total broker outage, including the FIX session, from a single unlucky interleaving —
and the failure presents as "the exchange won't accept logons", which sends you to the wrong
process entirely. The exchange was healthy the whole time; its message-processor thread was parked
on an empty queue.

**Resolution.** `openAccount` now does its work in a private `openAccountLocked` under the monitor
and notifies `AccountOpenedListener`s *after* releasing it, so the AccountService monitor is never
held while acquiring PnlService's. Lock order is now consistently PnlService → AccountService.
Lessons: (1) a callback invoked while holding a lock is a lock-order edge you did not intend to
declare — notify listeners outside the critical section by default; (2) when a component that was
"fine at 8 users" dies at 512, suspect a widened race before suspecting capacity, and let load
average arbitrate — an idle box that is not serving requests is a lock, never a bottleneck; (3)
`jstack` names the deadlock outright, so reach for it before reading any more logs.

## P27 — republished artifacts never reached deployed minions — **RESOLVED** (2026-08-19)

**Symptom.** After rebuilding the four Java components and republishing them, a full `state.highstate`
reported 87/87 with *no* changes beyond the ready probes, and the minion went on running the previous
build: the installed jar kept its original mtime and the service kept its original PID. The P26
deadlock fix appeared to deploy successfully and changed nothing.

**Root cause.** Two independent gaps that hide each other.

1. `archive.extracted` treats itself as satisfied once the archive's **paths** exist under `name`.
   The publish pipeline deliberately writes stable filenames (`broker.tar` etc., P6) so pillar never
   changes per build — which means a new build has byte-different contents and byte-identical paths,
   and the state is a no-op. `source_hash` alone does not help: it validates the download, it does
   not force extraction. The option that does is `source_hash_update: True`.
2. No `running.sls` watched its own artifact — only `conf` and `unit`. So even once extraction was
   fixed, a new build would land on disk while the old code kept running in memory.

**Impact.** Silent and total: every artifact-level fix was undeployable, and the smoke test's earlier
"clean brown-field no-op" — which I read as proof of idempotence — was actually proof of this bug.
The failure has no error surface at all; it looks exactly like a successful converge.

**Resolution.** `source_hash_update: True` on all five `archive.extracted` states, and each service
now watches its own `archive:` in addition to its conf and unit. Lesson: a state that reports "no
changes" is only good news if you know what it compares — and "the files are all present" is not
"the files are correct". Verify a deploy by the artifact's identity (mtime, PID, a version marker),
never by the converge summary.
