# fxc formula — Plan

Companion to [DESIGN.md](DESIGN.md). Mirrors root `docs/PLAN.md`'s Phase 8 entry; this file carries
the step-by-step breakdown for that phase specifically.

## Phase 8 — SaltStack deployment formula — IN PROGRESS

1. [x] `FORMULA` metadata file + root `README.md` "SaltStack formula" section.
2. [x] `fxc/common` (service user/group, base dirs, `jdk17.sls`/`jdk21.sls`) + `fxc/map.jinja`
   (formula-wide defaults, including the GridGain `--add-opens` JVM flag set mirrored from root
   `build.gradle.kts`'s `igniteJvmArgs`).
3. [x] `fxc/mariadb` — native `mariadb-server` + Salt's `mysql_database`/`mysql_user`/
   `mysql_grants` states, replacing `docker/mariadb/init/01-databases.sql`'s bootstrap SQL.
4. [x] `fxc/tigase` — native JDK 17 + the same vendor dist tarball `docker/tigase/Dockerfile`
   builds from, `config.tdsl` re-templated (not referenced in place) for pillar-driven MariaDB
   connection info, one-time schema-load script adapted from `docker/tigase/init-schema.sh`.
   **Needs empirical verification** (see PROBLEMS.md): the systemd unit's `Type=forking`/`PIDFile`
   assumption against `scripts/tigase.sh`'s actual daemonizing behavior, and whether Tigase's
   config-rewrite-on-startup clobbers or is clobbered by Salt's `file.managed` on repeat converges.
5. [x] `fxc/exchange`, `fxc/pub`, `fxc/broker`, `fxc/investor` — native JDK 21 + systemd + pre-built
   artifact, conf templates adapted from each component's `conf/*.conf`.
6. [x] `fxc/locust` — native venv + systemd, env-var contract mirrored from `docker-compose.yml`'s
   `locust` service.
7. [x] `_modules/fxc_exchange.py`, `fxc_broker.py`, `fxc_investor.py`, `fxc_mariadb.py`.
8. [x] `top.sls` (grain-based `roles` targeting) + `pillar.example/` (per-component pillar files,
   single-role and all-in-one topology references).
9. [x] `cloud.providers.d/`, `cloud.profiles.d/`, `cloud.maps.d/` for salt-cloud EC2 (6-instance
   split topology default, all-in-one alternative).
10. [x] `fxc/orchestrate/demo_stack.sls` (cross-minion convergence order) + `reset_hard.sls`
    (fleet-wide `scripts/reset.sh --hard` equivalent).
11. [ ] **Build/publish step** producing the pre-built artifacts (Java `distTar` tarballs +
    Gradle-side `conf/` bundling if not already included in the `application` plugin's
    distribution layout, plus a `loadgen/` tarball) that every `installed.sls` fetches. Does not
    exist yet — artifact-mode installs cannot be exercised end to end until it does. This is the
    single largest remaining gap.
12. [ ] Verification (see below) — none of the live-infrastructure stages have been run yet; this
    plan and the state files it describes are unexercised beyond template/syntax review.
- **Exit criteria**: `salt-cloud -m cloud.maps.d/fxc-fleet.map` stands up the 6-instance fleet, a
  subsequent `salt-run state.orchestrate fxc.orchestrate.demo_stack` converges every minion in
  dependency order, and `salt -G 'roles:exchange' fxc_exchange.open` + a Locust run produces
  fills — the same outcome `scripts/demo.sh` already proves locally.

## Verification (staged, cheapest to most expensive)

1. [ ] **Lint**: `salt-lint` over `fxc/**/*.sls`, `_modules/*.py`, `cloud.*.d/*.conf`.
2. [ ] **Masterless dry-run**: `salt-call --local state.apply fxc.<x> test=true` per component, in
   dependency order, against a local VM/container.
3. [ ] **Masterless real converge**, one VM/container per role (or one all-in-one VM): apply for
   real, replay `scripts/demo.sh`'s health-check sequence by hand. This is where item 11 above
   must exist first.
4. [ ] **Real salt-cloud smoke test** against a short-lived AWS account: full fleet up, orchestrate,
   `fxc_exchange.open`, a short Locust run, fills confirmed, fleet torn down.

## Suggested review checkpoint

Stop-and-review after item 11 (the artifact publish step) lands — it's the one piece every other
state depends on to actually converge for real, and the concrete tarball layout it produces may
require adjusting the `installed.sls`/`*.service.jinja` assumptions made in items 3-6 (see
PROBLEMS.md's "distTar layout" entry).
