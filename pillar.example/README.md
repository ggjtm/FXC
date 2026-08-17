# fxc pillar.example

Example pillar tree for the `fxc` formula. **None of the values here are real secrets** —
`CHANGEME` placeholders throughout. To use:

1. Copy this directory's contents into your own (private, likely encrypted or gpg-rendered)
   pillar repo / `pillar_roots` path.
2. Replace every `CHANGEME` with a real value, or better, reference an `sdb://` URI or a
   `#!yaml|gpg` encrypted block instead of a plaintext value — the `fxc/*.sls` state files read
   these via `pillar.get()` and are agnostic to which pillar backend actually supplies them.
3. Pick a topology: for salt-cloud-created minions, the `roles` grain is normally set per-profile
   (see `cloud.profiles.d/fxc-profiles.conf`) and this pillar tree's `topology/*.sls` files are
   unused — root `top.sls` matches on that grain. For hand-provisioned minions without salt-cloud,
   either (a) set the same `roles` grain directly (a static `/etc/salt/grains` file, or
   `salt-call grains.setval roles "[...]"`), using `topology/all-in-one.sls` /
   `topology/single-role-*.sls` here as the human-readable reference for the value, or (b) actually
   assign pillar `roles` via this directory's `top.sls` matching the minion ID *and* change root
   `top.sls`'s `match: grain` to `match: pillar` for every entry (the two approaches are not meant
   to be mixed in one tree — see root `top.sls`'s own comment).
4. Set `fxc:gridgain:license_source` to a real `salt://`, `s3://`, or `https://` URL for a signed
   GridGain 8 Ultimate XML license (v2.1) — never commit the license file itself (see root
   README.md's "GridGain license" section).

See `fxc/docs/DESIGN.md` for the full secrets-handling design and `fxc/docs/PROBLEMS.md` for known
gaps (e.g. cross-minion hostname resolution for `fix.exchange.host` etc. in the split topology).
