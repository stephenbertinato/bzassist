# Testing checklist

A pass through everything the mod does, in the order that finds problems fastest. Each step says what
should happen and what to capture if it does not. Tick them off as you go.

Before you start: Minecraft 26.2 with Fabric Loader, `fabric-api-0.161.0+26.2.jar` and
`bzassist-1.1.0.jar` in `mods`, nothing else in there from an older version.

## 0. It loaded

- [ ] Launch the game. `logs/latest.log` contains `BzAssist 1.1.0 loading`.
- [ ] Join Hypixel and go to SkyBlock (any island).
- [ ] **No HUD card is visible anywhere.** Not at the top, not after opening chat.

## 1. Silence until started

- [ ] Press `T` to open chat: the box is **empty**, no `/bzassist best` typed for you. Press Esc.
- [ ] `/bzassist plan Smoldering` → the card fades in at the top centre with a step.

## 2. Commands

- [ ] `/bzassist selftest` → `result  all checks passed`.
- [ ] `/bzassist profile` → purse, inventory, tax, order slots, limit left, data age (a few seconds) and
      name coverage. Try it while the bazaar is open too; tax and order slots are read from the GUI.
- [ ] `/bzassist best 10` → ten lines. Sanity: profit/h falls as you go down, `limit/h` and `cap lasts`
      are plausible, and nothing has a spread above 500%.
- [ ] `/bzassist price Smoldering VI` → both sides, weekly volume, order counts, and the `limit` line
      showing the per-placement arithmetic.
- [ ] `/bzassist daily` → spent, left, usable, reset time (should read 19:00 your time, 7pm EST).

## 3. HUD behaviour

- [ ] With a plan active, the card is at the top centre, rounded, with the six columns.
- [ ] Follow one step: the instruction cross-fades out and the next fades in (old drifts up, new rises).
- [ ] The card's width eases between steps instead of jumping.
- [ ] The highlighted slot has a breathing outline; amber when the step is a right-click.
- [ ] `/bzassist stop` → the plan clears and the card fades out.
- [ ] `/bzassist off` then `/bzassist on` (same command toggles) hides and restores it.
- [ ] Try GUI scale 2 and 3 in Options → Video: text stays crisp, nothing overlaps.

## 4. The guide, one full buy cycle

Use something cheap you do not mind owning.

- [ ] `/bzassist plan <cheap item>`. Press `T`: the chat box is pre-filled with `/bz <name>`; press Enter.
- [ ] The search results page appears; the correct product slot is outlined (and the term is a substring,
      so enchantments work: "Smoldering", not "Smoldering 6").
- [ ] Click the product: `Create Buy Order` is highlighted.
- [ ] Quantity step: either a preset is highlighted, or `Custom Amount` is highlighted **and the sign
      opens with the number already typed**. Only press Done.
- [ ] Price step: `Top Order +0.1` is highlighted.
- [ ] Confirm step: `Buy Order` is highlighted. **Before clicking, `/bzassist daily` shows the value as
      pending, not spent.**
- [ ] Click it. Chat says the order was created. Now `/bzassist daily` **does** count it as spent.

## 5. The limit only counts real orders

- [ ] Open a confirm screen again (place an order but press Esc instead of confirming).
- [ ] `/bzassist daily` → spent has **not** moved. Wait a minute; still not moved.

## 6. Persistence across restarts

- [ ] Note the `spent` figure from `/bzassist daily`. Quit the game completely and relaunch.
- [ ] `/bzassist daily` → the same `spent` figure, same day. It should survive a restart.
- [ ] `/bzassist daily reset` → spent goes to 0. (Use it only when you want the cap back.)

## 7. A real session

- [ ] Have at least one buy order older than a few minutes that has been undercut.
- [ ] Press `F6`. The card fades in: it should ask for `/managebazaarorders` or open your orders.
- [ ] The scan reads your existing orders **including ones placed in earlier sessions**.
- [ ] If one is behind the book it is offered for cancel → the order row, then the Cancel button.
      `/bzassist session` names a report file; in it, `[relist]` explains each decision.
- [ ] Stash step: run `/viewstash item`. If you get "Your item stash is empty!" the card should move on to
      the material stash within a second or two — it must not sit there forever.
- [ ] Sell step: a filled product offers `Create Sell Offer` then `Best Offer -0.1`.
- [ ] Place step: with free order slots, a new item is proposed with a full click chain.
- [ ] Press `F6` again to stop.

## 8. The summary screen

- [ ] `/bzassist dev summary` → the example summary opens: four stat cards, the profit chart, best and
      worst item with real item icons, key stats. It fades in, and Esc closes it.
- [ ] After a session that made money, stopping with `F6` opens the real summary by itself.
- [ ] `/bzassist summary` reopens the last one.
- [ ] A session that lost money does **not** open anything.

## 9. The limit wall (optional, burns the cap)

- [ ] Keep placing orders until Hypixel refuses one.
- [ ] The session stops by itself, the plan clears, and the HUD reads `Daily limit reached`.
- [ ] `/bzassist daily reset` brings it back.

## 10. The report file

- [ ] `/bzassist session` → prints a path under `config/bzassist/sessions/`.
- [ ] Open it. It should contain `[session]`, `[config]`, `[profile]`, `[phase]`, `[step]`, `[screen]`,
      `[chat]`, `[api]`, `[state]` lines, and a `SUMMARY` block at the end.

## If something fails

Collect, in this order:

1. What you did and what happened instead (one line is enough).
2. The session report file for that session — it has every step, screen and chat line the mod saw.
3. `/bzassist dump` output if a slot was highlighted wrongly.
4. `/bzassist profile` output.

That is enough to fix almost anything without reproducing it.
