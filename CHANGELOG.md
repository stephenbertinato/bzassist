# Changelog

All notable changes to BzAssist. The format follows [Keep a Changelog](https://keepachangelog.com/),
and the project uses [semantic versioning](https://semver.org/).

## [Unreleased]

### Added

- Session reports: every session streams a full timestamped record to `config/bzassist/sessions/` —
  config and profile at start, phase changes, every instruction, every container the scanner read,
  every chat line, feed updates and failures, a state sample every five seconds, and every exception
  the mod caught. Closed with a summary; `/bzassist session` prints the path.
- `/bzassist session` and `session` help entry.

### Fixed

- Bazaar search typed the tier ("Smoldering 6") while the item is "Smoldering VI", so nothing came up.
  The search term is now the base name with the tier stripped ("Smoldering"): a substring that exists.
- The daily limit was only remembered in memory and was charged on the confirm screen, so a refused or
  abandoned click burned the cap. It now persists per Minecraft account in
  `config/bzassist/limit-<uuid>.properties`, charges only when chat confirms the order was created, and
  resets at 00:00 UTC (7pm EST) or with `/bzassist daily reset`.
- When Hypixel says the daily cap is gone, the session stops and the plan is cleared instead of walking
  into refused orders.
- Long-standing orders were never relisted: the unit price was read as the largest number on the row
  (the total, not "price per unit"), so every order looked like it was already at the top of the book.
  The unit-price line is used now, and any order behind the book is relisted (one-minute cooldown per
  order, and only if the limit can afford it) — not just orders placed this session.
- The HUD's `SLOTS` figure showed free inventory slots; it now shows order slots in use over allowed.

### Changed

- HUD motion: the card fades and rises in when a session starts and fades out when it stops; a step
  change cross-fades the instruction, detail and item name (old out and up, new in from below) while the
  width eases; the slot outline breathes on a 1.8 s sine.
- HUD look: true 6 px rounded corners drawn row by row, stepped shadow, inner top highlight, a 2 px
  accent rail with rounded ends, and a reworked type hierarchy — 10 px instruction, 8 px detail and
  values, 6.5 px all-caps labels with 0.45 px letterspacing (new tracking support in `FontRenderer`),
  and profit split into an accent number with a small muted `/H` unit.

### Fixed

- The HUD and the chat prefill were active before a session was started, showing "Pick a flip — run
  /bzassist best" and filling the chat box with it. Both are now silent until F6 is pressed or an item
  is planned.
- `/viewstash item` and `/viewstash material` answer with a chat line ("Your item stash is empty!")
  instead of a container when there is nothing to collect. The session now reads those lines and moves
  to the next step instead of waiting forever for a screen that never opens.

## [1.0.0]

First release. Client-side Hypixel SkyBlock bazaar flipping assistant for Minecraft 26.2.

### Added

- Live bazaar feed: `api.hypixel.net/v2/skyblock/bazaar` polled every 5 s on a daemon thread, skipped
  when `lastUpdated` has not moved, backed off exponentially on failure.
- Flip engine: half-spread pricing one tick in front of the book, tax-aware margins, flow and
  book-depth filters, 15B daily order-value accounting per placement, profit-per-hour ranking with a
  profit-per-limit-coin gate, and sizing from purse, inventory, flow, book depth and a 60m coin cap.
- Step-by-step guide: highlights exactly one slot, pre-fills the quantity and price signs, handles
  buy, claim, list, cancel and relist, and tells you to press Esc when a chat command is needed while a
  menu is open.
- Order session (F6): scans `/managebazaarorders`, finds outbid and undercut orders, decides whether
  relisting is worth its limit cost, collects the stash, lists finished units, and places the next order.
- HUD: one small panel at the top centre with the instruction, profit per hour, and an aligned grid of
  `PROFIT/H CYCLE SESSION` over `LIMIT SLOTS TIME`; drawn only on SkyBlock and only when there is a step.
- Commands: `/bzassist best|plan|price|profile|dump|debug|refresh|off|start|end|stop|selftest`, plus
  `/bza <item>` with tab completion.
- FreeType text renderer: glyphs rasterised at their exact device pixel size into a cached atlas, drawn
  through vanilla's `GUI_TEXTURED` pipeline.
- Documentation: `docs/bazaar.md` (measured API facts and the limit model), `docs/fonts.md` (why MSDF was
  dropped), `docs/improvements.md` (roadmap and known gaps).

### Known limitations

- The exact lore strings of the orders screen, the order-detail Cancel button and the stash screens have
  not been confirmed against the live game; `/bzassist dump` exists to settle them.
- The daily limit is tracked locally from confirm screens, so it resets on a game restart.
- Fill-time estimates are derived from weekly volume, not from observed fills.
