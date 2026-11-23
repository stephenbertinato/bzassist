# Changelog

All notable changes to BzAssist. The format follows [Keep a Changelog](https://keepachangelog.com/),
and the project uses [semantic versioning](https://semver.org/).

## [1.2.0] - 2026-11-23

### Added

- `/bzassist relist`: a relist-only pass. It reads `/managebazaarorders`, moves whatever is behind the
  book, and stops — nothing is bought, sold or placed, and the previous session's summary is left alone.
  `/bzassist orders` is the read-only dry run that prints the verdict for every row first.

## [1.1.1] - 2026-11-23

### Fixed

- Orders that were already listed were still not relisted. The reader now resolves a row's product from
  its name, then from its lore when the name alone does not match, and every row is compared with the
  live book by its unit price. `/bzassist orders` prints what was parsed, the price it read, and the exact
  reason each order is or is not being moved — paste its output if a relist still does not happen.
- The purse was a sizing hint, not a limit: with 0 coins it still proposed orders worth millions. Sizing
  now refuses a flip the purse cannot pay for, the two-unit floor only applies when two units are
  affordable, and the guide stops with `Not enough coins` instead of walking into a refused order.

### Changed

- Text now uses the font's own kerning pairs and lands glyphs on whole device pixels, which removes the
  uneven spacing between letter pairs and the soft, grainy look.
- Rounded corners are anti-aliased: each scanline through a corner is drawn with fractional coverage at
  both ends instead of a whole-pixel staircase, so the card has one smooth outline.

## [1.1.0] - 2026-11-23

The first published build, and the one that made the HUD and the session report worth looking at.

### Added

- **Session summary screen**: opens when a session ends with a profit. Four headline figures (total
  profit, sales, buys, net margin), a cumulative profit chart, the best and worst item with their real
  in-game icons, and three key stats (average profit per sale, flip success rate, total flips). Fades in
  like the HUD; `/bzassist summary` reopens it, `/bzassist dev summary` shows an example filled with
  reference figures.
- **Session reports**: every session streams a full timestamped record to `config/bzassist/sessions/` —
  config and profile at start, phase changes, every instruction, every container the scanner read, every
  chat line, feed updates and failures, a state sample every five seconds, and every exception the mod
  caught. Closed with a summary; `/bzassist session` prints the path.
- **Persistent daily limit**, per Minecraft account: `config/bzassist/limit-<uuid>.properties`. It is
  charged only when chat confirms an order, resets at 00:00 UTC (7pm EST), and can be cleared by hand.
- `/bzassist daily [reset]`, `/bzassist summary`, `/bzassist dev summary`, `/bzassist session` and
  `/bzassist selftest`.
- `FontRenderer` gained letterspacing, which every all-caps label now uses.

### Changed

- **HUD**: one wide rounded card with the instruction in 11 px white, profit right-aligned in green with a
  small tracked `/H`, the detail and item name beneath, and a single six-column row —
  `PROFIT/H · LIMIT · CYCLE · SLOTS · SESSION · TIME` — separated by hairlines. True 8 px rounded
  corners drawn row by row, a 1 px border, 6.5 px tracked labels over 9.5 px values.
- **HUD motion**: the card fades and rises in when a session starts and fades out when it stops; a step
  change cross-fades the instruction, detail and item name (old out and up, new in from below) while the
  width eases to the wider of the two; the slot outline breathes on a 1.8 s sine.
- `SLOTS` now means order slots in use over allowed (`2/14`), not free inventory slots.
- The daily limit is spent per placement and per relist, and the summary and HUD figures are fed from the
  same confirm screens.

### Fixed

- Bazaar search typed the tier ("Smoldering 6") while the item is "Smoldering VI", so nothing came up.
  The search term is now the base name with the tier stripped ("Smoldering"), a substring that exists.
- The daily limit was charged when the confirm screen opened, so a refused or abandoned click burned the
  cap. It is now held as pending and only committed when Hypixel confirms the order in chat.
- When Hypixel says the daily cap is gone, the session stops and the plan is cleared instead of walking
  into refused orders.
- Long-standing orders were never relisted: the unit price was read as the largest number on the row (the
  total, not "price per unit"), so every order looked like it was already at the top of the book. The
  unit-price line is used now, and any order behind the book is relisted — one-minute cooldown per order,
  and only if the limit can afford it — not just orders placed this session.
- The HUD and the chat prefill were active before a session started, showing "Pick a flip — run
  /bzassist best" and filling the chat box with it. Both are silent until F6 or an explicit plan.
- `/viewstash item` and `/viewstash material` answer with a chat line ("Your item stash is empty!") when
  there is nothing to collect. The session reads those lines and moves on instead of waiting forever for
  a screen that never opens.

### Known limitations

- The exact lore strings of the orders screen, the order-detail Cancel button and the stash screens are
  still not confirmed against the live game; `/bzassist dump` exists to settle them.
- Fill-time estimates come from weekly volume, not from observed fills.
- The session summary only opens on a profitable session; a losing one closes quietly.

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
