# BzAssist

A client-side **Hypixel SkyBlock bazaar optimizer** for Fabric. While the Bazaar is open it shows the
top 20 flips with the buy-order price, the sell-offer price, the recommended order size, expected
profit per hour, expected fill time, and a cumulative realized-profit readout.

There is no slot highlighting, no step-by-step guide, no flipping session, no chat prefill and no
clicking. The mod reads the public bazaar API, draws a panel, and pre-fills the Bazaar's own Custom
Amount/Custom Price sign for the item you opened; you still click every button and place every order.

* Minecraft 26.2 · Fabric Loader 0.20.1+ · Fabric API · Java 25 · client side only

## What it does

- **Ranks the live book** by expected profit per hour, using one-tick pricing, tax-aware margins, flow
  and book-quality filters, and a profit-per-limit-coin gate.
- **Sizes each order** from your purse, free inventory, the product's market flow, a target fill time,
  and hard notional/unit caps. The panel shows the resulting quantity, not a generic "buy a stack".
- **Shows the top 20 only while the Bazaar is open.** The root, category, product and order screens
  count; the panel disappears in chests, inventories, chat and the normal HUD.
- **Refreshes only when useful.** The API is polled while the Bazaar is open, at the API's own ~20 s
  cadence. Closing the Bazaar stops the feed.
- **Pre-fills the two custom signs.** Clicking `Custom Amount` fills the amount recommendation for the
  product you opened; `Custom Price` fills its buy or sell price. This only applies to products in the
  current top 20 after the book has loaded.
- **Tracks realized profit.** Confirmed bazaar sales add to a small cumulative total in the panel
  header. It counts coins actually claimed, after matching the sold units to a confirmed buy order,
  and persists the total across restarts. `/bzassist profit reset` zeroes it at any time.
- **Stays where you drag it.** Hold the left mouse button on the panel and move it anywhere; the
  position is saved and restored next time. Clicks over the panel do not reach the container behind
  it, and the panel is clamped back on screen after a window resize.
- **Does not click, highlight, or place orders.** It never sends a chat command and never clicks a
  slot; the only input it writes is the recommendation into a bazaar sign you opened.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2 and put
   [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder.
2. Drop `bzassist-<version>.jar` into the same `mods` folder.
3. Launch the game, join Hypixel SkyBlock and open the Bazaar. The panel opens immediately; the
   rows fill in when the first feed arrives.

The mod is client side and does not need to be installed server-side.

## The panel

A single table, beside the Bazaar GUI by default (centred over it when the window is too narrow).
It is draggable: hold the left mouse button anywhere on the panel and move it; the position is saved
in `config/bzassist.properties` and restored next time.

```
BZASSIST  TOP 20              12S AGO  TOTAL PROFIT +1.24m
BUY ORDER · SELL OFFER · QTY · PROFIT/H · FILL
  #  ITEM                    BUY      SELL    QTY   PROFIT/H   FILL
  1  Wither Catalyst       2.30m     3.03m     10    91.7m/h    5m
  2  Enchanted Blaze Rod   480.1k    650.0k     53    29.7m/h   17m
  ...
```

The panel shows the API age, a loading/error line, and a warning when the purse could not be read.
There is no scrollbar: the 20 rows are the product.

## How an order is sized

The engine ranks on expected profit per hour, but the quantity is the largest order that still clears
inside the configured target fill time:

```
quantity = floor(min(
    purse / buyPrice,
    freeInventorySlots × 64,
    target_fill_hours / (1 / buyFlowPerHour + 1 / sellFlowPerHour),
    60,000,000 / (buyPrice + sellPrice),
    2,240 units
))
```

Essence is exempt from the inventory/stack cap and uses a 71,680-unit cap. The order is rejected if
the modelled fill still exceeds `max_fill_hours`, if the margin does not cover the daily-limit cost,
or if the book fails the liquidity/price-quality filters. The full model is in
[docs/bazaar.md](docs/bazaar.md).

## Commands

| Command | What it does |
| --- | --- |
| `/bzassist best [n]` | print the current top flips in chat (default 20, max 20) |
| `/bzassist refresh` | fetch the bazaar immediately |
| `/bzassist profit` | show the cumulative realized-profit total |
| `/bzassist profit reset` | zero the total, keeping the cost basis of open buys |
| `/bzassist off` | toggle the panel |

There is no keybind and no plan/session command.

## Configuration

`config/bzassist.properties` is created on first launch. Every value is clamped to a sane range.

| Key | Default | Meaning |
| --- | --- | --- |
| `poll_interval_ms` | `20000` | fetch cadence while the Bazaar is open |
| `tax_percent` | `1.25` | bazaar sell tax: 1.25 without Bazaar Flipper, 1.125 at I, 1.0 at II |
| `target_fill_hours` | `1.0` | market flow one recommended order should cover |
| `max_fill_hours` | `6.0` | reject orders expected to take longer than this |
| `overlay` | `true` | show the top-20 panel while the Bazaar is open |
| `panel_x`, `panel_y` | `-1` | remembered panel top-left in GUI pixels; `-1` is the automatic position |

Tax is a setting because the 2.0 overlay no longer scans the confirm screen. If you have Bazaar
Flipper, lower `tax_percent` to match; otherwise the profit figures are slightly conservative.

The profit total and the cost basis of open buys are kept in `config/bzassist/profit.tsv`. Delete
that file to clear both; `/bzassist profit reset` clears only the displayed total.

## Build from source

```bash
./gradlew build        # jar in build/libs/bzassist-<version>.jar
./gradlew runClient    # launch Minecraft 26.2 with the mod loaded
```

The first build downloads Gradle, Loom, Minecraft 26.2 and the assets into `~/.gradle`.

Releasing is a version bump: raise `version` in `gradle.properties`, add a `## [x.y.z]` section to
`CHANGELOG.md`, and push to `main`. The release workflow builds the jar and publishes it with that
changelog section as the notes.

## Documentation

- [docs/bazaar.md](docs/bazaar.md) — measured API facts, the price-field trap, ranking, filters and
  the sizing model.
- [docs/fonts.md](docs/fonts.md) — the FreeType text renderer used by the panel.
- [CHANGELOG.md](CHANGELOG.md) — what changed.

## Layout

```
src/main/java/dev/bzassist/BzAssist.java           common entrypoint, MOD_ID, MOD_VERSION, logger
src/client/java/dev/bzassist/client/               entrypoint, overlay, commands, text renderer
src/client/java/dev/bzassist/client/bazaar/        API client, pure flip engine, player context
src/main/resources/fabric.mod.json                 mod metadata
src/main/resources/assets/bzassist/                icon and bundled Inter SemiBold
```

## Limitations

- Sign prefill only knows products in the current top 20. Opening the Custom Amount/Price button for
  any other bazaar product leaves the sign blank, rather than filling a number from another item.
- The purse is read from the scoreboard or the SkyBlock menu item. If neither is visible, the panel
  says so and sizes with a 1m fallback rather than pretending the quantity is optimal.
- Fill time is modelled from weekly traded volume and queue depth, not from observed fills. It is an
  estimate, not a promise.
- There is no price history, no automatic tax detection and no open-order tracking. The panel is a
  snapshot of the current book.
- The profit tracker only sees orders and claims confirmed in chat while BzAssist is running. A sale
  whose units cannot be matched to a recorded buy adds no profit, rather than treating the whole
  payout as gain.
- No in-game visual verification has been performed for this rewrite in the build environment; the
  source compiles and produces the jar.

## License

CC0-1.0. The bundled Inter font is under the SIL Open Font License, see `tools/fonts/OFL.txt`.

BzAssist is not affiliated with Hypixel or Mojang.
