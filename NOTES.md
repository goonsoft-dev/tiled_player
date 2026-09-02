# Repo notes

Working notes on generalization work and open follow-ups. Not part of the
spec ([`DESIGN.md`](DESIGN.md) is authoritative) — just a place to track
what's left.

## Generalization pass (this change)

Replaced vendor-specific wording (ColorOS, Oppo, "Private Safe") with
platform-neutral language across `README.md`, `DESIGN.md`,
`AndroidManifest.xml`, and comments in `TiledPlayerApp.kt`, `VaultImport.kt`,
`LibraryScreen.kt`, `VaultStore.kt`. The underlying behavior (hidden-app
drawer compatibility, no-permission private storage) is unchanged — only the
naming of the specific OEM/feature it was originally built around.

`CHANGELOG.md` was already removed in an earlier commit (`ccdedf1`); its
0.2-vault entry had the same "Built for ColorOS's hidden-app drawer, which
can't reach the Private Safe" line. Worth deciding whether to reintroduce a
changelog with the generalized wording, or leave history out of the repo.

## What the repo needs next

Pulled from `DESIGN.md`'s own "reasonable addition, not required" notes and
gaps observed while reading the codebase — not commitments, just a punch
list:

- **No CI.** No `.github/workflows`, no test task wired up. Worth adding at
  least a lint/assembleDebug workflow before this grows further.
- **No automated tests** for the pure-function layers DESIGN.md §7 calls out
  as "easy to exhaustively test" (layout tree building in `GridLayout.kt`,
  session/segment planning). These are the cheapest correctness net available
  and currently unexercised.
- **Preset reordering** (§5.2) only requires an up/down list view per the
  spec; free-form drag reordering is called out as a nice-to-have, not done.
- **Destructive trim** (§8.7) — only non-destructive in/out points exist;
  an explicit "rewrite and reclaim space" action is flagged as a reasonable
  follow-up, not yet implemented.
- **Software-decoder fallback** (§4) for when the hardware decoder budget is
  exceeded is mentioned as a design goal ("if available") but its actual
  implementation status isn't documented — worth confirming it exists or
  explicitly deferring it.
- **Second implementation of §1–§7** — DESIGN.md notes a single-file web
  implementation of the core player "once existed alongside the Android one"
  but no longer does. If a web build is still wanted, it'd need to be rebuilt
  from the spec.
- **`keystore.properties.example`** exists for release signing but there's no
  documented process for who holds the actual keystore/passwords — fine for
  a personal sideload build, worth a line in the README if this repo is
  meant to be picked up by others.
