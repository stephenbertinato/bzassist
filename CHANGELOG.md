# Changelog

All notable changes to BzAssist. The format follows [Keep a Changelog](https://keepachangelog.com/),
and the project uses [semantic versioning](https://semver.org/).

## [1.3.0] - 2026-11-24

A full pass over the session machine and the order reader. The theme is the same everywhere: the
assistant now keeps moving instead of settling on one order.

### Fixed

- **The session never came back from PLACE.** One product filled the whole run. It now clears the plan
  the moment Hypixel confirms the order in chat and moves straight to the next product, remembers what it
  placed so the same item cannot be picked twice before the list is re-read, counts those placements
  against the order-slot budget, and goes back to a full scan after two minutes or as soon as a claim
  lands. That is what makes it place several buy orders for several items instead of one.
- **Claiming items out of a buy order is read from chat** (`Claimed 6x Null Atom worth …, bought for …`).
  Claimed units are tracked, and the `bought for` figure gives stock ordered before the session a real
  cost basis instead of leaving those sales as "no cost basis" revenue.
- **No more "claim" on a sell offer.** Only a buy order holds items waiting; a sell offer holds ours.
  The old test offered a claim on any row that mentioned claiming.
- **Cancelling is the game's two clicks**: click the order row, then `Cancel Order`. The guide said
  "right-click to cancel" — a flow that does not exist — and the moment the row was open it replaced the
  step with "Click Go Back", which is why a relist pass looked like it saw nothing.
- An order screen opened by hand is only offered for cancellation when it is the row the guide sent you
  to, so an order you are merely looking at can never be suggested for cancelling.
- **The sell phase no longer plans a fresh position.** A sell-only plan lists exactly the units in the
  bag, and the custom-amount sign fills in the held count; before, it planned a new position and could
  ask for more of the same item to be bought instead of listing what was already there.
- A sell offer's chat figure is what you earn after tax, but the daily limit is charged on the order
  value, so the gross is reconstructed before it is counted.
- `Claimed … at 1.8 coins each` parses. The word `coins` between the price and `each` used to be
  swallowed into the item name.
- `/bz Sell Offer`-style commands cannot be produced: a term is only typed when it resolves to that exact
  product, then the full display name, then bare `/bz`.
- `/bzassist orders` reads the screen in front of you rather than the last session's snapshot, and prints
  the side and parsed price of rows it cannot identify.
- The stash step gives up on a stack after 30 seconds instead of leaving the player on one slot forever.
- The HUD's session profit and clock restart with the session, not with the game.

## [1.2.9] - 2026-11-24

### Fixed

- Confirmed bazaar orders and claims are read from the real chat lines (`Buy Order Setup!`,
  `Sell Offer Setup!`, `Claimed … from selling …`), so a screen opened and closed without ordering is
  no longer counted, and the daily limit is charged from the figure chat printed.
- Undercut and overbid are judged against the live order book with the 0.1-coin tick: matching the top
  price is not behind, 0.1 coins or more is, and the HUD, order list and log show how many units and
  people are in front.
- Order rows are parsed from their real `SELL <item>` / `BUY <item>` titles, so the guide can find,
  reprice and cancel its own orders instead of looking for a side in the lore.
- `/bz Sell Offer` and similar invented commands are gone: button labels and order-row titles are no
  longer learned as product names, polluted `learned-names.properties` entries are dropped on load, and
  a `/bz` command containing a UI label is refused.
- The relist step no longer vanishes after one tick. The one-minute cooldown starts when an order is
  actually taken on, not while deciding, and the `Cancel Order` step stays until the order is gone.
- The session summary opens after any session that recorded an order or a claim, and from
  `/bzassist summary`; it is opened on the next client tick so the closing chat screen cannot erase it.

### Changed

- A claim with no cost basis no longer counts its whole revenue as profit. Coins from stock bought
  before the session, or with no buy order recorded, are still included in Total Revenue and shown as
  `No Cost Basis` in the summary and the session log, but they are not added to profit, the profit
  chart, the HUD session figure, average profit per sale or the flip success rate.

## [1.2.8] - 2026-11-23

### Changed

- The stash step is real now: the screen is titled `View Stash`, its rows are ordinary item slots, and a
  stack is named after the item with the amount either in the stack count or as an ` x2` suffix — both are
  read, the suffix is stripped before the item is matched to a bazaar product, and rows that do not fit in
  the free inventory are logged and skipped instead of silently dropped.
- Materials are collected first, then the item stash: materials are what a bazaar flip returns.
- `View Stash` is its own screen kind, so it is no longer mistaken for an unknown container.

## [1.2.7] - 2026-11-23

### Changed

- The quantity step always uses **`Custom Amount`**, never a preset. Presets differ per product
  (`Buy only one!`, `Buy 4!`, `Buy 32!`, `Buy 50!` …) and clicking one ordered a number the flip maths did
  not choose; the sign opens pre-filled with the exact amount, so it is still one click and Done.
- Slot names confirmed against the game and recorded: `Go Back`, `Close`, `Create Buy Order`,
  `Create Sell Offer`, `Custom Amount`, `Custom Price`, `Top Order +0.1`, `Same As Top Order`,
  `Best Offer -0.1`, `Claim All Coins`, `Cancel Order`.

## [1.2.6] - 2026-11-23

Everything here came out of a real `debug.jsonl` capture, not a guess.

### Fixed

- Order rows are titled `SELL <item>` / `BUY <item>`; the side was being looked for in the lore, where it
  never appears, so every row was skipped and the session saw no orders at all. The name prefix is read
  now, the SkyBlock id printed on the row (`NULL_ATOM`) is used as the bazaar id directly, and the
  `Offer amount: 89x` / `Price per unit: 46,619.7 coins` lines are parsed from that same lore.
- The order detail screen is titled `Order options` and its button is `Cancel Order`. It is now its own
  kind, so cancelling an order actually happens instead of the session asking for the order list again.
- Product and price pages were being mistaken for the order list: their lore also contains
  "Price per unit" and the words "Sell Offer". The order list is only recognised now by rows titled
  `BUY …` / `SELL …` that print `Offer amount`.
- `Claim All Coins` is offered whenever it would do something, judged by its own lore
  (`You don't have any coins to claim.`).
- Quantity preset `Buy only one!` counts as one; presets differ per item (`Buy only one!`, `Buy 4!`,
  `Buy 32!` for a book) and anything not offered goes through `Custom Amount`.

### Added

- `docs/data-requirements.md` part 2 now records the real titles, buttons, lore and chat lines from the
  capture, and lists what is still unknown (stash screens, sign editors, order-created and claim chat
  lines).

## [1.2.5] - 2026-11-23

### Fixed

- **Purse, actually.** A sidebar row on screen is the team prefix, the entry's display name and the team
  suffix concatenated — on Hypixel nearly all of the text lives in the prefix and suffix, and the entry's
  own display name is empty. Reading only the display name therefore found nothing, which is why the
  purse stayed "not detected" with `Purse: 45,813` plainly visible. The reader now builds each row the way
  the game renders it, then parses that.
- `/bzassist pursedebug` prints every sidebar row as the reader sees it, with the team prefix and suffix,
  the parse result for each, and the purse/source it ended up with.

## [1.2.4] - 2026-11-23

### Changed

- **Navigation is a step again.** `Go Back` (and `Back` when that is the label) is now outlined as the
  recovery route when you leave the guide's page: it is one click back to a page the plan can work from,
  faster than Esc and retyping. Only when there is no such button does the guide fall back to "Press Esc,
  then /bz <term>".
- A confirm screen for a product that is not the plan is caught before the click, and routed back the same
  way — so a stray Confirm can never place the wrong order.

## [1.2.3] - 2026-11-23

### Fixed

- **Purse detection, properly.** The figure is now read from four independent places, in order: every
  scoreboard display slot and every objective (not only `SIDEBAR`), the SkyBlock Menu item in the
  inventory, and the lore of any open container. Each candidate goes through one tolerant parser that
  accepts `Purse: 666,666,666`, `Purse 1.2M`, `Purse: 3 000 000`, a coin glyph instead of the word, and
  dot-separated thousands; implausible values are rejected rather than stored, and a source that finds
  nothing falls through to the next instead of clearing a value that was already right.
- `/bzassist profile` now says which source the purse came from, so a wrong figure can be traced.

## [1.2.2] - 2026-11-23

### Fixed

- A `/bz` term that does not name anything in the bazaar (a derived name can be misspelled) is no longer
  offered. The term is checked against the live product list; if it fails, the display name is used, then
  the words from the product id, and bare `/bz` as the last resort.
- Stopping a session now clears the plan as well, so `F6` really ends everything: the card disappears,
  the highlight goes away, and starting again begins cleanly instead of resuming a plan you thought was
  stopped.
- The profit series no longer moves on a five-second timer. It advances only when something is claimed
  (or an order is placed), which is also the only moment the profit figure changes.

### Added

- The order list's **Claim All Coins** button is used when it is there, so every finished order is claimed
  in one click instead of one row at a time.
- `Go Back`, `Back`, `Close` and the page arrows are never outlined, whatever the step names.

## [1.2.1] - 2026-11-23

### Fixed

- **Purse not detected.** The purse line was only looked for on the `SIDEBAR` display slot, and a purse
  of 0 coins was thrown away as "no value". It is now read from every display slot, and zero is a real
  reading, so sizing stops proposing orders you cannot pay for.
- **The order list was not recognised.** A page whose title the classifier does not know is now treated
  as the orders screen when its rows carry a unit price and a buy/sell side, which is what coop titles
  and renamed menus look like.
- **Order rows were misread.** `Offer amount: 89x` and `Price Per Unit: 749,986.6 coins` are now parsed
  directly, so an order's quantity and unit price are right even when nothing has filled.
- **Profit is counted when it is claimed, not when it is listed.** A sale only adds to the session when
  the chat line says the coins arrived, and only for the units it names, against the average cost of the
  units bought. Placing a sell offer no longer counts as profit.
- **Random product pages were adopted as the plan.** Opening any bazaar product used to replace the plan
  and propose "buy 64" of it. Now the guide only ever follows the planned item: open something else and
  it says "Wrong product open — press Esc, then /bz <term>".
- **Commands could be nonsense.** Only `/bz <term>`, bare `/bz`, `/managebazaarorders` and
  `/viewstash item|material` are ever offered; anything else a step might have produced is dropped.
- **Quantity presets assumed books.** "Get me a stack / A big stack / One thousand" do not exist for
  essence, which offers "Buy 50! / Buy 100! / Buy 500!". The guide now matches whatever preset the screen
  actually shows for the exact amount, and otherwise uses the custom amount sign, pre-filled.
- **Highlighting.** Only slots that belong to the plan (or a running session) are outlined, and
  navigation buttons — Back, Go Back, Close, page arrows — never are.

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
