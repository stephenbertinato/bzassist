# Bazaar assistant

How the mod reads the bazaar, what it computes from it, and which parts are still unverified.

## What was measured, not assumed

| Fact | Value | How it was checked |
| --- | --- | --- |
| Endpoint | `GET api.hypixel.net/v2/skyblock/bazaar` | called live |
| API key needed | no | called without one, HTTP 200 |
| Response size | 3.65 MB JSON, **504 KB gzipped** | measured both ways |
| Products | **2207** | counted from the payload |
| Refresh rate | **~20 s** (`lastUpdated` advanced 19.9s, 20.1s, 19.9s) | sampled 9× over 47 s |
| Cache headers | `max-age=60, s-maxage=300` | response headers |
| Order book depth | **30 levels per side**, not sorted reliably | inspection |

The server refreshes every ~20 s, so a 5 s poll is the fastest useful cadence; anything quicker spends
bandwidth on identical bytes. A poll whose `lastUpdated` has not changed is not even parsed.

## The naming problem

Ids and names are not derivable from each other, and **Hypixel's item list only names 1028 of the 2207
products** — every enchantment (782), shard (324), faction (60) and essence (11) is missing. So names
come from three layers, best first:

1. **Learned** — the display name read off the real `ItemStack` when the player opens that product.
   Ground truth, persisted to `config/bzassist/learned-names.properties`.
2. **Item list** — `/v2/resources/skyblock/items`, cached to `config/bzassist/items.tsv` for 7 days.
3. **Derived** — a readable guess (`ENCHANTMENT_ULTIMATE_CROP_FEVER_5` → "Ultimate Crop Fever V").

## Price semantics (the trap)

Hypixel names the two summary arrays from the *order placer's* side, which is the reverse of what a
buyer sees:

- `buy_summary` = **sell offers** (asks). Cheapest one is what you pay. Exposed as `asks()`.
- `sell_summary` = **buy orders** (bids). Highest one is what you get. Exposed as `bids()`.
- `quick_status.buyPrice` / `sellPrice` are **smoothed averages**, not the top of the book — for a thin
  product they sit outside the spread entirely. Trade planning uses `bestAsk()`/`bestBid()`, never those.
- The arrays are **not reliably sorted**: stale orders appear mid-sequence (e.g. `1.5` inside a
  5135-coin band). The best price is found by scanning for min/max, not by taking index 0.

## The daily limit

**15,000,000,000 coins of order value per day, reset 12:00 UTC.** Source: the community wiki's Bazaar
page (the official Hypixel wiki was shut down in July 2026).

Three details drive the whole design:

- It is charged **when the order is created**, not when it fills. An unfilled order has already spent
  its share.
- It is **shared between buying and selling** — one pool.
- A **cancel-and-relist re-spends the limit**, which is a cost that never appears as a coin loss.

So every cycle is charged:

```
limitPerCycle = qty × (buyPrice × (1 + relistBuy) + sellPrice × (1 + relistSell))
```

The relist counts come from how long each order has to sit at the top of the book, divided by how
quickly rivals undercut — and each side is counted separately, since buy orders and sell offers are
different crowds. `Flip.limitEfficiency()` (profit per limit coin) is a hard gate rather than a
tiebreak: below 4% the flip is dropped. Running out of limit before two hours is fine; spending 100m
of it to earn 2m is not, which is exactly the "50m book, 1m margin" case.

**Limitation, stated plainly:** the game never shows remaining limit, so it is tracked locally by
reading `Total price:` / `You earn:` off each confirm screen and resetting at 00:00 UTC. A restart
loses the tally.

## How a flip is sized

Fill rate is a property of the market, not of your order — ordering 10,000 units when 500/day flow past
means waiting 20 days while being undercut. Quantity is therefore the smallest of:

- **five minutes of market flow, scaled up to 5× for a wide margin** — one short slice of the market,
  so an order is cheap to replace: being undercut costs little limit and a fill still pays,
- what the purse can pay for,
- what the free inventory can hold (essence is exempt; a 64-stack per free slot otherwise),
- what the market clears inside `max_fill_hours` (default 72),
- **twice the units visibly standing at the best price on each side** — ordering more than the book
  shows means queuing behind everybody already standing there,
- **60m coins of notional per order**, so one bad book can never commit a big slice of the cap,
- **never more than 2,240 units** — essence excepted, which stacks to ten million and caps at 71,680,
- a floor of 2 units when a spread is at least 10% and both sides trade at least 2 units a day.

The daily limit does not cap the size and does not shape the ranking. It is reported, and the one
thing enforced about it is that every limit coin has to pay for itself (the 4% floor above).

### What the limit actually costs, and why it is now on screen

Every placement is charged the moment it is created, and a cancel-and-relist charges it again even if
the order never filled. So a cycle is not one charge, it is this:

```
placements per cycle = 1 buy order + relistBuy + 1 sell offer + relistSell
limitPerCycle        = qty × (buyPrice × (1 + relistBuy) + sellPrice × (1 + relistSell))
limitPerHour         = limitPerCycle / fillHours
```

and the same item is put through that cycle over and over — many cycles an hour for anything fast. The
relist counts are the full `hours / undercutInterval` for each side, no longer truncated at 20, because
truncating hid the cost of exactly the busiest books, where an order is undercut repeatedly and never
fills.

`/bzassist best` therefore ends with the only number that matters for planning:

```
14 slots running at once
  earns   120.4m/h while the limit lasts
  spends  7.60b/h of limit  (1.58% back per limit coin)
  cap lasts 1.9h → 232.6m before it is gone
```

That is the shape of a real session: a return of one or two percent per limit coin, the whole 15B gone
in around two hours, and a couple of hundred million made. A flip whose `cap lasts` reads 60h is one
that can run all day; a flip whose `cap lasts` reads 0.2h is a burst that ends your session. Both are
printed next to profit per hour rather than being hidden inside one blended score.

`worth` (0..1) = profit/hour 90%, limit efficiency 10%. It is a label; ranking uses profit/hour.

## A units bug worth remembering

Hypixel's API docs are explicit: `sellVolume`/`buyVolume` are "the sum of item amounts in all orders",
and `movingWeek` is "the historic transacted volume" — **units, not coins**. An earlier version divided
`movingWeek` by the average price. That did almost nothing to a 2-coin item and divided a million-coin
item's flow by a million, so every expensive product looked dead and only cheap churn survived. Flow is
now taken from the API undivided, which is what lets a slow, high-margin item outrank fast junk.

## Phantom margins

Removing the price division exposed the opposite failure. Sorting the live book by margin put
`FACTION_RABBIT_LAGOON` (best bid 0 coins, best ask 1.6m) at the top: an empty bid side produces an
infinite-looking margin that could never fill.

Manipulation and dead books are filtered by explicit checks, each calibrated against the live book:

| Check | Rejects |
| --- | --- |
| best bid ≥ 1, best ask ≥ 2 | bids of 0–1 coins |
| top of book within 0.5–2× the 7-day volume-weighted average | stale/rogue orders |
| ≥ 5m coins traded per week on each side | books with flow in units but no money |
| ≥ 2 distinct orders in the five best levels of each side | single-order listings |
| profit per limit coin ≥ 4% | "50m book, 1m profit" flips |

The old **300% margin cap is gone**. It banned the wrong thing: `SHARD_BAMBULEAF` pays 227% and
trades hundreds of units a day, and several enchantments sit at several hundred percent while filling.
A wide spread is only rejected when the top of the book disagrees with its own 7-day average, which is
what actually marks a stale order.

Of 2207 products, 948 survive. `ENCHANTMENT_ULTIMATE_FATAL_TEMPO_3` still fails — `movingWeek` is
**0 on both sides**, so its 337m "margin" is a price nobody fills. `ENCHANTMENT_LOOTING_NEW_7` fails
the efficiency gate (1.9% of the limit it burns), while `ENCHANTMENT_SMOLDERING_6` passes at 6.8% and
ranks #42.

## Ranking

Sorted on **profit per hour after a competition discount**; the daily limit only breaks ties. Order
size barely matters: fill time scales with quantity, so profit/hour reduces to `margin × flow`, which
is independent of how many units you place. Cutting the flow floor is therefore what surfaces more
opportunities — not ordering bigger.

The discount that used to be applied here (`confidence = 1 / (1 + 10 × sellOffers / unitsSoldPerDay)`)
is gone. It counted competition twice: fill time is already `(queueAhead + qty) / flow`, so a crowded
book already has a long fill and a low profit per hour. Multiplying again buried books that are simply
good — `ENCHANTMENT_PERFECT_TOPAZ_7` among them. `Flip.realisation()` is now a constant 1.0, kept as a
field so the display and the sort have one place to hang a future correction.

The efficiency gate then keeps the top of it honest: a flip can be fast and still be a poor use of the
cap, and those are dropped rather than ranked low.

## Reading the game

`BazaarScreenReader` scans the open container every other tick: title, every slot's name, lore, count
and the Skyblock id out of `custom_data` → `ExtraAttributes.id`. It learns the real tax rate and order
slot count from the screen itself, so Bazaar Flipper upgrades are picked up automatically.

```bash
/bzassist best [n]      # ranked flips
/bzassist plan <item>   # guide this product
/bzassist price <item>  # one product in detail
/bzassist selftest      # check the parsing and formatting code
/bzassist session       # path of the last session report
/bzassist profile       # purse, slots, tax, limit
/bzassist dump          # what the scanner sees right now
/bzassist debug         # toggle config/bzassist/debug.jsonl
```

### What is still unverified

The exact lore strings of the order list, and the exact titles of a few screens, could not be
confirmed from any source — the wiki's own UI pages leave the orders screen blank. Everything that
depends on them is written defensively and the `/bzassist dump` output plus `debug.jsonl` are there to
settle it against the live game:

```
config/bzassist/debug.jsonl     one JSON object per screen you open, only when it changes
```

Open the bazaar, click through a product, place and cancel an order, then read that file.

## How the guide decides

`GuideEngine` holds a plan (item + position size) and reconcile it against the market, what you hold,
and what you have listed:

- **One sell offer per product, ever.** A second one undercuts your own first, so no sell is suggested
  while quantity is already listed.
- **Only buy what is not already covered** — `target − held − listed`. Two filled and listed out of a
  planned four means the next buy order is for two, so capital and limit are never spent twice on the
  same units.
- **Do not list crumbs** while a buy order is still filling: wait for a batch worth an order slot. Once
  the buy side is done, whatever is held is listed.

Planting: every session streams a full report to `config/bzassist/sessions/`, and nothing is shown
until a session is started or a plan exists — no idle card, no suggested command.

The overlay draws exactly one outline around the slot the step names, and one small panel at the top
centre: the instruction with profit per hour, the detail with the item name, and a three-column grid of
`PROFIT/H CYCLE SESSION` over `LIMIT SLOTS TIME`, labels left-aligned on fixed columns and values
right-aligned to each column's edge so the numbers line up.
