# FxcBroker — Stories

One markdown file per user story / work item for the broker. Suggested naming: `NNN-short-slug.md`
(e.g. `001-oms-fix-initiator.md`, `002-ofx-statement.md`, `003-order-entry-message-set.md`). Keep
each story small and testable; link back to the component [PLAN.md](../PLAN.md) and root design.

Suggested front-matter per story:

```
# <title>
Status: proposed | in-progress | done
Relates to: PLAN item / DESIGN §
```

## Filed

- [001 — investor-requested order-book snapshot](001-order-book-snapshot.md): relay an exchange
  FIX order-book snapshot to an investor over OFX (unblocks `booker`/`bookfish`).
