# Bazaar optimizer

How BzAssist reads the bazaar, how it ranks and sizes a flip, and what the model does not know.

## Measured API facts

| Fact | Value |
| --- | --- |
| Endpoint | `GET api.hypixel.net/v2/skyblock/bazaar` |
| API key | none |
| Response | 3.65 MB JSON, ~504 KB gzipped |
| Products | 2,207 |
| Refresh | ~20 seconds (`lastUpdated`) |
| Book depth | 30 levels per side, not reliably sorted |

The feed is active only while the Bazaar screen is open. It polls at `poll_interval_ms` (default 20 s),
skips parsing when `lastUpdated` has not moved, and backs off on failures.

## Price semantics

Hypixel names the summary arrays from the order placer's side:

- `buy_summary` = sell offers (asks). The cheapest ask is what you pay to buy instantly.
- `sell_summary` = buy orders (bids). The highest bid is what you receive selling instantly.
- `quick_status.buyPrice` / `sellPrice` are smoothed averages, not the top of the book.

The arrays are not sorted, so `BazaarProduct.bestAsk()` / `bestBid()` scan for the minimum / maximum.
Both sides are checked against the smoothed weekly average; a top of book more than 2× away is treated
as a stale or rogue order.

## Ranking

The engine builds a buy order one tick above the best bid and a sell offer one tick below the best
ask:

```
buyPrice  = bestBid + 0.1
sellPrice = bestAsk - 0.1
margin    = sellPrice × (1 − tax) − buyPrice
```

It rejects a product when any of these fails:

- both prices exist and the spread is positive after tax;
- each side trades at least 0.05 units/day and at least 5m coins/week;
- each side has at least two distinct orders in its five best levels;
- the top of the book is within 0.5×–2× of the 7-day average;
- the spread is at most 500%;
- profit per daily-limit coin is at least 4%.

Survivors are sorted by expected profit per hour. The list is cached by snapshot `lastUpdated` and by
the player/settings context; ranking runs on the bazaar worker thread and the overlay only reads the
published list.

## Order size

The recommendation is the largest order that still clears inside the target fill time:

```
buyFlowPerHour  = sellMovingWeek / 7 / 24
sellFlowPerHour = buyMovingWeek / 7 / 24
capacity        = freeInventorySlots × 64          (essence: 10,000,000)
notional        = 60,000,000 / (buyPrice + sellPrice)

quantity = floor(min(
    purse / buyPrice,
    capacity,
    target_fill_hours / (1 / buyFlowPerHour + 1 / sellFlowPerHour),
    notional,
    2,240                            (essence: 71,680)
))
```

`target_fill_hours` defaults to 1 hour for the whole buy-plus-sell cycle. The expected fill is then:

```
hours = (queueAheadBuy + quantity) / buyFlowPerHour
      + (queueAheadSell + quantity) / sellFlowPerHour
      + 0.033 hours of click overhead
```

The order is rejected if that exceeds `max_fill_hours` (default 6 h). This is a cap, not an interior
optimum: under the model, profit per hour rises with quantity, so the useful question is how much can
be placed before the order becomes slow. `target_fill_hours` is the knob for that trade-off.

The old 2× top-of-book cap is deliberately gone. When you improve the top price by one tick, the
units standing at the old best price are behind you, not ahead; using their depth as a cap suppressed
good flips without modelling fill.

## Sign prefill

When the Bazaar opens its `Custom Amount` sign, BzAssist writes the recommended quantity into its
first line. `Custom Price` gets the buy-order price on the `How much do you want to pay?` screen and
the sell-offer price on the `At what price are you selling?` screen. The value comes from the top-20
row for the product shown on that page; products outside the list are left blank. The screen still
closes normally and sends its own sign update, so this is prefill only, not an automatic order.

## Total profit tracker

`ProfitTracker` listens to the confirmation lines Hypixel already prints:

```
[Bazaar] Buy Order Setup! 64x Cobblestone for 115.2 coins.
[Bazaar] Claimed 113.8 coins from selling 64x Cobblestone at 1.8 each!
```

A buy order adds its units and total cost to that product's basis. A sale claim realizes revenue
against the average cost of the units still unmatched, and only that gain is added to the total. A
sell-offer setup changes nothing because the coins have not arrived yet. Items claimed from a buy
order can add a cost basis when the `bought for` figure is present, which covers stock bought before
the tracker saw the order.

A sale with no recorded cost basis is deliberately not counted as profit. The total and the
remaining cost basis are written to `config/bzassist/profit.tsv` after each confirmation. Reset with
`/bzassist profit reset`; it zeroes the displayed total but keeps open-buy basis, so a later sale is
still priced correctly.

## What the model does not know

- **Observed fills.** Weekly volume is an average over everyone. The model has no per-product fill
  history and no measured probability of being undercut.
- **Your own open orders.** The panel is stateless; it does not subtract your existing orders from
  the queue. An item you already have an order on can still appear.
- **Tax tier per item.** Tax is one setting; lower it if you have Bazaar Flipper.
- **Purse when the scoreboard is hidden.** The panel says `PURSE UNKNOWN` and uses a 1m fallback.
- **Price history.** There is no "is this spread normal?" baseline beyond the API's smoothed weekly
  average.
- **Profit history outside the tracker.** The total starts at zero and only includes confirmed
  bazaar orders and claims seen while BzAssist is running. Sales from untracked stock add revenue,
  not profit.

## Live verification

The 2.0 rewrite compiles and produces the jar, but has not been visually verified in-game in the
build environment. In particular, check the panel placement at GUI scale 2/3/4, the direct
`/bz <item>` product screen trigger, and the purse warning with the scoreboard hidden.
