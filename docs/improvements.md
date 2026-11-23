# Improvements roadmap

Everything below is a suggestion, ordered by what would most improve the mod per hour spent. Effort is
S (an evening), M (a few evenings), L (a week or more). Nothing here is hidden behind a secret plan: if
an item says "measured", it means the number is not in the code yet.

## 1. Before anything else: verify the live screens

The whole guide is built on screen titles, lore strings and slot labels that no public source documents.
It works defensively, but one in-game pass removes the guesswork and makes every later improvement
cheaper.

| # | Item | Why | Effort |
| --- | --- | --- | --- |
| 1.1 | Capture `/bzassist dump` on the orders screen, an order detail (the Cancel button), and both `viewstash` screens, then pin the real strings | Every "click this" step currently falls back to "first slot containing X"; one wrong label means a wrong highlight | S |
| 1.2 | Capture the sign editor (quantity and price) and confirm the value is typed, not the label | A prefilled sign that lands in the wrong field wastes a cycle | S |
| 1.3 | Record one full cycle with `/bzassist debug` on and turn it into a fixture the parser is tested against | Screen fixtures catch a Hypixel UI change before a player does | M |

## 2. Market model accuracy

The engine's weakest number is not the margin, it is **how long an order takes to fill**. That single
assumption drives profit per hour, the limit burn rate and the ranking.

| # | Item | Why | Effort |
| --- | --- | --- | --- |
| 2.1 | Record actual fill times: when a buy order is placed, when it filled, how much of it, and how many relists it took; keep a rolling per-product estimate | Weekly volume is an average over everyone; your own fills are the only data about *your* queue position. This is the single biggest accuracy win available | M |
| 2.2 | Use that history to calibrate the fill-time model per product instead of `queue / flow` for every book | Deep, thin books and fast, thick books behave nothing alike and currently share one formula | M |
| 2.3 | Sample the book every poll and store a short price history (say 24 h at 5-minute resolution) | Enables "is this spread normal for this item?" and stops chasing one-off stale orders that look like 200% margins | M |
| 2.4 | Track the daily limit precisely from the confirm screens for every placement, including relists | The limit is already modelled per placement, but the running total is inferred; a screen-read total would make the "cap lasts" figure exact | S |
| 2.5 | Portfolio mode: choose the set of orders across your 14/21/28 slots that maximises profit per hour *for the limit you have left*, instead of ranking items independently | Ten good flips competing for one cap is a knapsack problem; ranking each in isolation ignores that they share the budget | L |
| 2.6 | Model tax tier per item (some items are taxed differently) rather than one global percentage | A 0.25% error is small per cycle and large over a 15B day | S |

## 3. Guide and session behaviour

| # | Item | Why | Effort |
| --- | --- | --- | --- |
| 3.1 | A "why" line on demand: `/bzassist why` prints the numbers behind the current step (queue ahead, expected fill, margin after tax, limit cost) | When a step looks wrong, the answer is one command away instead of a code read | S |
| 3.2 | Pause and resume (F7, unbound by default): stop issuing steps without clearing the plan | You will want to answer a trade or check a chest without the guide fighting you | S |
| 3.3 | Step timeout: if a step has not been followed for N seconds, dim the card and say so | Stale instructions are worse than none | S |
| 3.4 | Undo/back: return to the previous step when a mis-click lands you on the wrong screen | Right now the only recovery is Esc and re-opening the product | M |
| 3.5 | Sound or chat ping when a plan completes a cycle or the session needs input while you are in another window | The whole point is turning your brain off; it should call you back | S |
| 3.6 | Queue several items: plan A, B and C and have the session rotate between them as orders fill | One product rarely keeps 14 slots busy | L |
| 3.7 | Remember the plan across a reconnect or world change | Losing a plan to a lobby switch is annoying and easy to fix with a small persisted state file | S |

## 4. HUD and presentation

| # | Item | Why | Effort |
| --- | --- | --- | --- |
| 4.1 | Position and scale options (`hud_position`, `hud_scale`): top centre by default, movable to a corner | Top centre collides with boss bars and some servers' scoreboards | S |
| 4.2 | A compact mode that shows only the instruction and profit, for players who find the grid noisy | The grid exists because numbers matter; not everyone wants six of them | S |
| 4.3 | Item icon next to the instruction | One glance confirms the guide is on the product you think it is | S |
| 4.4 | Respect Minecraft's own "Text Background Opacity" setting for the panel | Players who run a translucent chat expect the same from every overlay | S |
| 4.5 | Check the panel at GUI scale 1 and 3 on a 1080p and a 1440p screen | The layout is built on even pixel steps, but nothing beats looking | S |
| 4.6 | Optional session sparkline: profit per minute over the last hour | Shows whether a slow patch is the market or the plan | M |

## 5. Features worth building

| # | Item | Why | Effort |
| --- | --- | --- | --- |
| 5.1 | "Best right now" hotkey that re-ranks and offers to switch the plan mid-session | Plans go stale as the book moves | S |
| 5.2 | Undercut alert: chat message when your listed order stops being the best price | Faster reaction without staring at the orders screen | M |
| 5.3 | Session report on `end`: cycles, coins earned, limit spent, profit per limit coin, best and worst product | The only way to know whether yesterday's settings were better | S |
| 5.4 | Persistent lifetime stats in `config/bzassist/stats.properties` | Progress you can see is progress you keep | S |
| 5.5 | Import/export of plans as a short code | Sharing a good flip with a friend, or restarting a session identically | M |
| 5.6 | ModMenu integration with a small config screen (poll interval, tax, slots, HUD position) | Editing a properties file is fine until it is not | M |
| 5.7 | Craft-flip hints: products whose buy price is below the craft cost of their inputs | A second, independent source of margin that the order book alone will not show | L |

## 6. Robustness and performance

| # | Item | Why | Effort |
| --- | --- | --- | --- |
| 6.1 | Cache HUD layout: text widths are measured every frame today | Measuring a handful of strings per frame is cheap but pointless; a layout cache keyed on the strings removes it entirely | S |
| 6.2 | Reuse parsed snapshots instead of rebuilding 2,207 product records on every update | Parsing is off-thread, but a diff-based update would cut allocation sharply | M |
| 6.3 | Wrap every screen read in defensive null/format checks (the orders parser already is) and add a per-screen failure counter that disables a broken parser instead of throwing every tick | Hypixel changes its UI; the mod should degrade, not break | S |
| 6.4 | `/bzassist selftest` in CI: run the same checks as a plain Java test so a parsing regression fails a build | Already a command; making it a test gives it teeth | S |
| 6.5 | Structured error log (`config/bzassist/errors.log`) the player can attach to a bug report | "It does not work" is unfixable; one log file is not | S |

## 7. Distribution and community

| # | Item | Why | Effort |
| --- | --- | --- | --- |
| 7.1 | Fill in `contact` in `fabric.mod.json` (homepage, sources, issues) and add screenshots | Modrinth and CurseForge both read these fields | S |
| 7.2 | Publish on Modrinth (primary) and CurseForge with the jar from the release workflow | Modrinth is where Fabric players look; the release workflow already builds the jar on a tag | S |
| 7.3 | A short GIF of one full cycle | People install what they can see working | S |
| 7.4 | A one-line update check against Modrinth's API, shown once per session in chat | Users run stale jars for months otherwise | S |
| 7.5 | A "report a problem" command that bundles version, config and the last 200 log lines | Turns a vague complaint into a reproducible issue | S |

## 8. Deliberately not doing

- **Any automation.** No auto-clicking, no packet sending, no "place the order for me". Hypixel bans for
  it, and a guide that only highlights is both safer and more useful: you keep the judgement.
- **API keys.** The bazaar endpoint needs none; asking for one would add a secret to leak for no gain.
- **A second font.** One semi-bold weight at two sizes is a design decision, not a gap.

## A suggested order

1. The verification pass (1.1–1.2) — nothing else is trustworthy until the screens are pinned.
2. Fill-time history (2.1) and HUD options (4.1) — the two changes players would feel immediately.
3. Session report and persistent stats (5.3–5.4) — cheap, and they make every later tuning measurable.
4. Publishing (7.1–7.2) once the above is in.
