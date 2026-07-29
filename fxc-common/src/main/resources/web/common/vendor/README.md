# Vendored front-end assets

Third-party browser assets, checked in and served from the classpath.

## Why vendored rather than fetched

`docs/DESIGN.md` §6 requires the component consoles to be static HTML+CSS+JS, and the demo has to
run offline (`scripts/demo.sh` brings the whole system up on localhost). A CDN `<script src>` would
make every console dependent on the network at page load, so the bundle is committed instead.

This is a static **asset**, not a Gradle runtime dependency: `gradle/libs.versions.toml` is
untouched, no npm/node tooling enters the build, and the framework-free convention
(`libs.versions.toml:3`, `docs/DESIGN.md` §4.1) still holds. The full minified bundle is used rather
than a hand-picked module subset precisely so there is no build step to maintain.

| File | Version | Source | License |
|---|---|---|---|
| `d3.v7.min.js` | 7.9.0 | <https://cdn.jsdelivr.net/npm/d3@7/dist/d3.min.js> | ISC — Copyright 2010–2023 Mike Bostock |

To upgrade: replace the file, keep the name (`StaticAssets` serves it by path, and both consoles
reference `/common/vendor/d3.v7.min.js`), and re-run `./gradlew test` — `FeedUiServingTest` asserts
the asset is reachable and served as JavaScript.
