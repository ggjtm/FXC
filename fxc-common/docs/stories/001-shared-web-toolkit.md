# Shared web toolkit for the component consoles
Status: done
Relates to: root DESIGN §6.1 / PLAN "Additional stories"

## Summary

One place for everything the FxcExchange and FxcBroker consoles have in common: the JSON writer, the
HTTP request/response plumbing, classpath static-asset serving, the dark theme, the hover control
menu, the D3 status indicator, and the vendored D3 bundle. Served off the classpath from the
`fxc-common` jar, so each component mounts the shared assets rather than keeping a copy.

## Motivation

Root DESIGN §6 asks for a console on more than one component. The exchange's first chart UI was a
single self-contained HTML file with an inline `<style>` and `<script>`, and `FeedHttpServer`
whitelisted exactly two paths (`/` and `/index.html`) — there was no static-asset handler at all. A
second console would have meant a second copy of the theme, the JSON helpers and the response
plumbing, and the two would have drifted apart.

## As built

New package `com.fxc.common.web`:

- **`Json`** — moved verbatim from `com.fxc.exchange.feed`; `str`/`num`/`array`. Write-only:
  the control endpoints take query parameters with an empty body, so FXC still has no JSON *parser*
  and does not need one.
- **`HttpJson`** — `sendJson`/`sendError`/`sendText`/`send` with the shared CORS header, `OPTIONS`
  preflight, `requireMethod`, `requireExactPath`, and query parsing. Extracted from
  `FeedHttpServer`'s private helpers.
- **`StaticAssets`** — an `HttpHandler` serving an index page plus mounted asset trees from the
  classpath, with a MIME map, a path allowlist, and a content-hash `ETag` + `If-None-Match` → 304.
- **Shared resources** under `src/main/resources/web/common/`: `fxc.css`, `fxc-menu.js`,
  `fxc-status.js`, `fxc-api.js`, `vendor/d3.v7.min.js` (+ `vendor/README.md` for provenance).

## Approach notes

**Two ordering decisions are load-bearing, and both were found by a failing test.**

1. `StaticAssets` resolves the path *before* gating the method. It is mounted on `/` as a catch-all,
   so it also receives requests for API paths that are not registered — for example the control
   endpoints when they are switched off. Answering those `405 method not allowed` implies the endpoint
   exists and was called wrongly; they must read as 404.
2. `HttpJson.requireExactPath` exists because `HttpServer` contexts match by **longest prefix**: a
   context registered at `/api/book` also receives `/api/book/clear`. Without the check, disabling the
   controls made `POST /api/book/clear` fall through to the read-only book endpoint and answer 405.

Assets are read and hashed once and then answered from memory: the previous handler re-read and
re-allocated its resource on every request, which is untenable for a ~280 KB D3 bundle.

**D3 is vendored rather than fetched.** The demo runs offline (`scripts/demo.sh` is all localhost), so
a CDN `<script src>` is not acceptable. The full minified bundle is committed rather than a
hand-picked module subset specifically so that no npm/node build step enters the repo. It is a static
*asset*: `gradle/libs.versions.toml` is untouched and the framework-free convention holds.

**Colour was computed, not chosen by eye.** The categorical series slots, the candle up/down pair and
the status steps were each measured against the console surface (`#16181d`) for lightness band, chroma
floor, colour-vision-deficiency separation (OKLab ΔE under simulated protanopia/deuteranopia) and
WCAG contrast. Two findings changed the design and are recorded in `fxc.css`:

- candle up/down (`#26a269` / `#e01b24`) measures ΔE 8.2 under deuteranopia — over the target, but
  only just — so candle bodies carry **shape** as well as colour (hollow = up, filled = down);
- the status steps fail the *categorical* gates by construction (they are a reserved, non-categorical
  set), so they are held to contrast instead and always render with a **glyph and a text label**.

## Acceptance criteria

- [x] Both consoles resolve `/common/*` from the `fxc-common` jar with no per-component copy.
- [x] Path traversal (`..`, encoded forms, empty segments, absolute-looking paths) is rejected.
- [x] Assets carry an `ETag`; a matching `If-None-Match` returns 304 with no body.
- [x] A POST to a path nothing is mounted at returns 404, not 405.
- [x] `Json` escaping and plain-number form (no exponent notation, scale preserved) are pinned.

## Verification

`fxc-common`: `StaticAssetsTest` (7), `JsonTest` (5). Also exercised end to end by
`FeedUiServingTest` and `BrokerWebApiTest`, which fetch every asset each console references.

## Out of scope / later

A console for FxcPub or FxcInvestor. Both are headless by design (DESIGN §4.3/§4.4); adding one is
additive — mount `StaticAssets` and reuse `web/common/*`.
