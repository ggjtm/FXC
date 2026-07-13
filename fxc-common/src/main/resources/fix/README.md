# FIX 4.4 data dictionary

FXC uses **QuickFIX/J's bundled `FIX44.xml`** data dictionary, which ships on the classpath in
`org.quickfixj:quickfixj-messages-fix44` (see `.reference/quickfixj-fix/`). There is no need to
vendor a copy of the standard dictionary.

Place a **custom** `FIX44.xml` here only if/when FXC needs non-standard fields or messages (none
are required for the FX/equity order + market-data flows in DESIGN §4.1/§4.2). If a custom
dictionary is added, point each QuickFIX/J session's `DataDictionary` / `UseDataDictionary`
setting at `classpath:fix/FIX44.xml`.

This placeholder keeps the resource location stable and documented per PLAN Phase 0.
