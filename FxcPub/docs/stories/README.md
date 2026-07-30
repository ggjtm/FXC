# FxcPub — Stories

One markdown file per user story / work item for the publication component. Suggested naming:
`NNN-short-slug.md` (e.g. `001-pubsub-client.md`, `002-fix-dropcopy-status.md`,
`003-timeline-projection.md`). Keep each story small and testable; link back to the component
[PLAN.md](../PLAN.md) and root design.

Suggested front-matter per story:

```
# <title>
Status: proposed | blocked | in-progress | done
Relates to: PLAN item / DESIGN §
```

No stories filed yet. The component itself is **not blocked** — the Tigase/AGPLv3 hold was accepted on
2026-07-13 (root PROBLEMS.md P2, this component's PROBLEMS.md P-1), and stock Tigase 8.4.1 plus the
Smack client layer, the GridGain projections and the FIX drop-copy acceptor are all running; see
[PLAN.md](../PLAN.md). The open work is listed there (the broker's direct-XMPP bot leg,
subscription-fed projections, non-admin service accounts) and has not been written up as stories.
