# fxc-common — Stories

One markdown file per user story / work item for the shared library. Suggested naming:
`NNN-short-slug.md` (e.g. `001-instrument-model.md`). Keep each story small and testable; link back
to the component [PLAN.md](../PLAN.md) and the root design where relevant.

Suggested front-matter per story:

```
# <title>
Status: proposed | in-progress | done
Relates to: PLAN item / DESIGN §
```

## Filed

- [001 — shared web toolkit](001-shared-web-toolkit.md): the pieces both component consoles share
  (root DESIGN §6.1) — `com.fxc.common.web` (`Json`, `HttpJson`, `StaticAssets`) plus the
  classpath-served `web/common/` theme, hover menu, D3 status indicator and vendored D3 bundle.
