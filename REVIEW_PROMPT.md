# Review prompt: the bazaar assistant, end to end

This is the brief for reviewing BzAssist as a whole. It lists what the system is supposed to do, the
invariants that must hold, every defect found in the 1.3.0 pass and how each was fixed, and the handful
of facts that are still not confirmed against the live game. Anything not confirmed here is a guess, and
a guess in a screen reader is what makes a step silently do nothing.

## 1. What the system is

One continuous flipping loop, driven by a keybind, that never automates a click:

```
SCAN  ->  RELIST  ->  STASH  ->  SELL  ->  PLACE  ->  (back to SCAN)
```

* **SCAN** — open `/managebazaarorders`, parse every row into an order: side, product id, unit price,
  filled and total units.
* **RELIST** — for each order that is no longer at the front of the book, click the row and then
  `Cancel Order`, so it can be replaced at the new price.
* **STASH** — `/viewstash material`, then `/viewstash item`, collecting only what fits.
* **SELL** — plan a sell-only listing for whatever is in the bag.
* **PLACE** — pick the best-ranked flip that is not already ordered, is affordable, and fits the free
  order slots; guide the buy order; on chat confirmation move to the next product.

## 2. Invariants

1. **Only four commands may ever be typed**: `/bz <term>`, `/bz`, `/managebazaarorders`,
   `/viewstash item|material`. A `/bz` term must resolve to that exact product.
2. **Nothing is charged until Hypixel says so.** The daily limit and the session stats move on the chat
   line (`Buy Order Setup!`, `Sell Offer Setup!`, `Claimed …`), never on a confirm screen.
3. **Profit is only realised on a claim**, and only for the units that claim names, against the cost of
   those same units.
4. **Nothing is cancelled that was not asked for.** A cancel is offered only for an order the session is
   relisting or a row the guide just told the player to open.
5. **A sell offer never buys.** A sell-only plan lists the bag and cannot ask for more of the item.
6. **The loop always progresses.** No phase may stay on one product indefinitely: an order placed, a
   sale listed, or a dead market all hand back to the next step.
7. **The HUD shows one step and outlines one slot**, only on SkyBlock, only while a plan or session
   exists.

## 3. Defects found and fixed in 1.3.0

| # | Symptom | Cause | Fix |
| --- | --- | --- | --- |
| 1 | One order occupied the whole session | `PLACE` had no exit: once a plan existed it returned early forever | plan clears on chat confirmation; `PLACED` set stops repeats; rescan after 2 min or on a claim |
| 2 | Claimed items were invisible to the stats | only the coin claim was parsed | `ITEMS_CLAIMED` parser → `SessionStats.claimItems`, which also supplies a cost basis from `bought for` |
| 3 | Guide offered "claim" on a sell offer | claim test ran on every row | claim only on a buy row |
| 4 | Relist pass seemed to see nothing | "right-click to cancel" is not a flow; opening the row replaced the step with "Go Back" | row click → `Cancel Order`, with the cancel step held while the row is open |
| 5 | An idle order could be cancelled | order screen treated as a cancel step | only when the guide sent you there or the session is relisting |
| 6 | Sell phase asked for more buying | one plan type for both directions | `GuideEngine.planSell` + `sellOnly` |
| 7 | Daily limit undercounted on sell offers | chat figure is post-tax | gross reconstructed from the tax rate |
| 8 | `at 1.8 coins each` broke the item name | regex did not allow `coins` before `each` | optional `coins` in the pattern |
| 9 | `/bz Chum Fishinng` | loose term check accepted any resolvable string | term used only when it resolves to the exact id |
| 10 | `/bzassist orders` said "parsed: 0" with the list open | it reported the last scan, not the screen | parses the live view, prints side and price per row |
| 11 | Stash step could hang on one stack | no patience limit | 30 s, then advance |
| 12 | HUD session figure never reset | `resetSession()` was never called | reset at session start |

## 4. Still unconfirmed against the live game

Each of these is matched by a guess. Send the capture named and it stops being a guess.

1. **The item-claim line, character for character.** `Claimed 6x Null Atom worth … bought for …` is
   parsed leniently: count and name are required, the two money figures are optional. If the real line
   differs, cost basis for pre-session stock is lost again.
2. **What a partially filled buy order looks like.** No `Filled:` line was ever seen, and the row's stack
   count is ambiguous, so the guide never offers a row-level claim and relies on `Claim All Coins`.
3. **The confirm button's slot name.** The confirmed slot list does not include one, so `confirm()` falls
   back to a contains-match on `Buy Order` / `Sell Offer`.
4. **Page arrows on a long order list.** Unknown labels, so more than one page of orders is only partly
   readable.
5. **The two sign editors.** They are not containers, so no log can capture them; the amount assumed is
   the first line.
6. **The stash row names.** `Coal x2` is assumed; if the game prints only `Coal`, the stack count is used.

Ask for, in this order: one `debug.jsonl` with the order list, the order detail and both stash screens;
the raw chat line of one item claim and one coin claim; a screenshot of each sign editor.

## 5. How to review a change

1. Does it move the limit or the stats off a chat line? It must not.
2. Does it let a phase stay on one product forever? It must not.
3. Can it type a command outside the four allowed forms? It must not.
4. Does it outline or instruct a cancel that the session did not ask for? It must not.
5. Does it change what a sell-only plan can do? It must stay sell-only.
