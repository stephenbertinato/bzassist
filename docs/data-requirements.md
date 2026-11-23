# What the mod needs from the game

Everything below is a fact about Hypixel's interface that the mod currently guesses at. One pass through
the bazaar with debug on covers almost all of it.

## The one thing to send

1. `/bzassist debug` → chat says `Debug logging on`.
2. Open each screen in the list below **once**, pausing a second on each so the scanner sees it.
3. Close the game and send me these two files:

```
config/bzassist/debug.jsonl     every screen, every slot: name, item id, count, every lore line,
                                plus the purse, tax and order-slot values it read at the time
logs/latest.log                 the chat lines (they are the "[CHAT]" lines)
```

`debug.jsonl` already contains the exact title of each screen and every slot name and lore line on it —
that is the whole of sections 1 and 2 answered. Screenshots of the two sign editors and of an order row
would still help, because a sign is not a container and cannot be logged.

## Part 2, answered from the 2026-11-23 capture

Everything below was taken from `debug.jsonl` and `latest.log`, so it is what the game actually prints.
Screens missing from the capture are listed at the end.

### Screen titles

| Screen | Exact title | Classified as |
| --- | --- | --- |
| Bazaar main menu | `Bazaar` | ROOT |
| Search / category | `Bazaar ➜ Oddities`, `Bazaar ➜ "smoldering"` | CATEGORY |
| Product | `Smoldering ➜ Smoldering VI` | PRODUCT |
| Quantity | `How many do you want?` | QUANTITY |
| Price, buy | `How much do you want to pay?` | PRICE |
| Price, sell | `At what price are you selling?` | PRICE |
| Confirm buy | `Confirm Buy Order` | CONFIRM |
| Confirm sell | `Confirm Sell Offer` | CONFIRM |
| Order list | `Co-op Bazaar Orders` | ORDERS |
| Order detail | `Order options` | ORDER_DETAIL |

### Slot names

- Product page: `Buy Instantly`, `Sell Instantly`, the product itself, `Create Buy Order`, `Create Sell Offer`
- Quantity: `Custom Amount` (lore: `Buy up to 256x.`). The presets (`Buy only one!`, `Buy 4!`, `Buy 32!`,
  `Buy 50!` …) differ per item and are never clicked: the guide always opens the sign, pre-filled.
- Price, buy: `Same as Top Order`, `Top Order +0.1`, `5% of Spread`, `Custom Price`
- Price, sell: `Same as Best Offer`, `Best Offer -0.1`, `10% of Spread`, `Custom Price`
- Confirm: `Buy Order` / `Sell Offer`
- Order list: `Go Back` (lore `To Bazaar`), `Close`, `Claim All Coins` (lore `You don't have any coins to
  claim.` when empty)
- Order detail: `Cancel Order` (lore `You will be refunded 89x items.`)

### Order rows

Titled `SELL <name>` or `BUY <name>` — **the side is the name prefix, not the lore**. The item's SkyBlock
id on the slot is the bazaar id itself (`BROKEN_RADAR`, `NULL_ATOM`). Lore:

```
Worth 8.2M coins

Offer amount: 11x

Price per unit: 749,986.8 coins

By: [MVP+] Hiipo
```

There is no `Filled:` line and no "claim" marker on the row.

### Confirm screens

- Buy: `Order: 2x Smoldering VI`, `Total price: 11,422,740 coins`
- Sell: `Selling: 89x Null Atom`, `You earn: 4,102,282 coins`

### Chat

- `[Bazaar] Cancelling order...`
- `[Bazaar] Cancelled! Refunded 89x Null Atom from cancelling Sell Offer!`

### Stash

- Title: **`View Stash`** for both the material and the item stash.
- Rows are ordinary item slots (nine of them); a stack is named after the item, e.g. `Coal`, and the
  amount is either the stack count or an ` x2` suffix on the name. Both are accepted, and the `xN` suffix
  is stripped before the item is resolved to a bazaar product.
- Materials are collected **first**; the item stash follows.

### Purse

Sidebar line, read through the team prefix: `Purse: 622,458,473` → `purse=622458473 source=SIDEBAR`.

### Still missing from the capture

- a `debug.jsonl` line for `View Stash` itself (title confirmed by eye; the slot names and their counts
  have not been seen by the scanner yet)
- the **sign editors** (not containers, cannot be logged)
- the chat line after **creating** a buy/sell order
- the chat line when **coins are claimed**
- what an **outbid/undercut** order looks like (the rows captured were at the front)
- the order list when it has more rows than fit (page arrows)

## 1. Screens to open, in this order

| # | Screen | What I read from it |
| --- | --- | --- |
| 1 | Bazaar main menu | exact title, the category slot names |
| 2 | One category (e.g. Farming) | title format, product slot names |
| 3 | A product page (e.g. Enchanted Book) | title format, `Create Buy Order` / `Create Sell Offer` labels, the buy/sell price lines |
| 4 | That product's **quantity** page | title, every preset label and its number |
| 5 | The **price** page, buy side | title, `Top Order +0.1`, `Custom Price`, any other preset |
| 6 | The **confirm buy** page | title, `Buy Order` button, `Total price:` / `You earn:` lines |
| 7 | The **confirm sell** page | title, `Sell Offer` button, `You earn:` line |
| 8 | **Bazaar Orders** (solo) | exact title, `Go Back`, `Close`, `Claim All Coins`, row layout |
| 9 | **Bazaar Orders** (co-op, if you have one) | whether the title differs |
| 10 | One order **detail** page (click a row) | title, the `Cancel` button's name, colour and lore |
| 11 | `/viewstash item` | title, and what an empty one looks like |
| 12 | `/viewstash material` | title |
| 13 | The **SkyBlock Menu** item in your inventory | whether its lore carries `Purse:` |

## 2. Exact strings the code depends on now

If any of these is wrong, the step that uses it silently fails. Tick the ones that match.

### Screen titles (`BazaarView.classify`)

- [ ] `Bazaar` — main menu
- [ ] `Bazaar ➜ <Category>` — category page
- [ ] `<Category> ➜ <Product>` — product page
- [ ] contains `How many` — quantity page
- [ ] contains `How much do you want to pay` or `At what price` — price page
- [ ] starts with `Confirm ` — confirm page
- [ ] contains `Bazaar Orders` or `Manage Orders` — order list (a page whose rows contain `Price per unit`
      and `SELL OFFER`/`BUY ORDER` is also accepted as the order list, whatever its title)

### Slot names

- [ ] `Go Back`, or `Back` — recovery button
- [ ] `Close`
- [ ] `Create Buy Order`
- [ ] `Create Sell Offer`
- [ ] `Custom Amount` — quantity sign
- [ ] `Custom Price` — price sign
- [ ] `Top Order +0.1` — buy price preset
- [ ] `Best Offer -0.1` — sell price preset
- [ ] `Buy Order` / `Sell Offer` — confirm buttons
- [ ] `Claim All Coins` — orders screen
- [ ] `Cancel` — order detail screen
- [ ] quantity presets: books use `Get me a stack`, essence uses `Buy 50!` / `Buy 100!` / `Buy 500!`;
      **send every variant you see** (the mod matches any preset whose number equals the amount it wants,
      then falls back to `Custom Amount`)

### Lore lines

- [ ] order row: `SELL OFFER` or `BUY ORDER` somewhere in the row
- [ ] order row: `Price Per Unit: 749,986.6 coins`
- [ ] order row: `Offer amount: 89x`
- [ ] order row: `Filled: 32` or `32/64` — **is there a filled line, and what is its exact wording?**
- [ ] order row: anything that marks a claimable row (the word `Claim`?)
- [ ] order row: anything that marks an order that has been outbid or undercut
- [ ] order detail: the same price/amount lines, plus whatever the `Cancel` button says
- [ ] confirm page: `Total price:` and `You earn:`
- [ ] bazaar GUI: the line that prints `Bazaar Tax: 1.25%` (the mod reads any `n%` on any screen)
- [ ] bazaar GUI: the line that prints `Orders: 2/14` (the mod reads `orders … n/m`)
- [ ] purse: the exact sidebar line, e.g. `Purse: 666,666,666`

### Chat lines

Copy these verbatim from `latest.log` — the mod matches on words, so the exact wording matters:

- [ ] creating a buy order: the line that appears after you confirm one
- [ ] creating a sell offer: the same
- [ ] a sale completing / claiming coins: the line with the coins in it
- [ ] `Claim All Coins`: the line(s) it prints
- [ ] the daily limit: the line Hypixel sends when it refuses an order for the cap
- [ ] not enough coins: the refusal line
- [ ] `/viewstash item` empty: `Your item stash is empty!` (confirmed)
- [ ] `/viewstash material` empty: `Your material stash is empty!` (confirmed)
- [ ] anything printed when an order is outbid or undercut

## 3. Not covered by `debug.jsonl`

- **The two sign editors** (custom amount, custom price). A screenshot of each is enough: I need the
  prompt labels and whether the input line is the first one (the mod types into line 0).
- **The order detail screen's Cancel button**: name, whether it is terracotta, and its lore.
- **Whether the order list scrolls**: how many rows are visible at once, and the page arrow labels.

## 4. If you would rather write it down

```
BAZAAR ROOT TITLE:
CATEGORY TITLE:
PRODUCT TITLE:
QUANTITY TITLE:
PRICE TITLE (buy):
PRICE TITLE (sell):
CONFIRM BUY TITLE:
CONFIRM SELL TITLE:
ORDERS TITLE (solo):
ORDERS TITLE (co-op):

ORDER ROW LORE (paste the whole tooltip):
ORDER DETAIL LORE (paste the whole tooltip):
CANCEL BUTTON NAME + LORE:

QUANTITY PRESETS SEEN (book / essence / normal item):
PRICE PRESETS SEEN:

CHAT: order created (buy):
CHAT: order created (sell):
CHAT: coins claimed / sale completed:
CHAT: daily limit reached:
CHAT: not enough coins:

SIDEBAR PURSE LINE:
```

Anything left blank I will have to keep guessing at, and the step that depends on it will keep failing
silently — which is exactly the class of bug you have been hitting.
