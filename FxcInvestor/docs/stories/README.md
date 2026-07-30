# FxcInvestor — Stories

One markdown file per user story / work item for the agent + CLI. Suggested naming:
`NNN-short-slug.md` (e.g. `001-ofx-client.md`, `002-cli-repl.md`, `003-momentum-strategy.md`). Keep
each story small and testable; link back to the component [PLAN.md](../PLAN.md) and root design.

Suggested front-matter per story:

```
# <title>
Status: proposed | blocked | in-progress | done
Relates to: PLAN item / DESIGN §
```

## Filed

- [001 — rando agent](001-rando-agent.md)
- [002 — booker agent](002-booker-agent.md)
- [003 — bookfish agent](003-bookfish-agent.md)
- [004 — single-instance runner](004-single-instance-runner.md)
- [005 — Gatling multi-agent runner](005-gatling-multi-agent-runner.md) — **superseded by 006**
- [006 — Locust multi-agent runner](006-locust-multi-agent-runner.md): Python + Locust load harness
  with a live control UI on :8089; replaces Gatling, which could not re-rate a run in progress
- [007 — investor mix control](007-investor-mix-control.md): how many of each investor type to run,
  steerable in that UI mid-run; market-wide traded volume and a patient `bookfish` came with it
