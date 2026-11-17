# Overnight Bot – Android app (v0.2)

The PLUG overnight strategy as a phone app, without n8n. The app talks to Alpaca directly. It can run the PLUG bot, the RIOT bot, or both with a split of the money.

Status: first version. The trading logic is tested against a simulated Alpaca account (17 tests). The app itself has not been built or run on a phone yet. Test in paper mode first, then live with a small amount.

## What the app does

| When (New York time) | Live mode | Paper mode |
| --- | --- | --- |
| 20 min before the close (15:40) | Checks each active bot. Green day: market-on-close buy (`cls`). | – |
| 5 min before the close (15:55) | – | Same check, normal market order (the paper account does not fill auction orders). |
| 30 min after the close (16:30) | Reads the buy fill, sends the daily report. | Same. |
| 19:10 | Queues the sell for the next opening auction (`opg`). | – |
| 45 min before the open (08:45) | Fallback: sends the opening sell if it is not there yet. | – |
| Just after the open (09:31/09:32) | Reads the sell fill, books profit/loss per bot. | Sells with a market order, then books profit/loss. |

Early-close days and holidays come from the Alpaca calendar, so the times move with the session.

Rules, same as the n8n workflows:

- **PLUG**: buy at the close when the price is above today's open. Sell at the next open.
- **RIOT**: same, but the price must be more than 1% above today's open (the setting from the backtest).
- Skips when the account is blocked, an order for the symbol is already open, or there is not enough cash for one share.
- Alert when a position is still there at the close (the morning sell did not fill).

Different from n8n:

- The opening sell is sent the evening before (Alpaca accepts `opg` orders from 19:00). The phone then has about 14 hours to get the order in, not one moment at 09:00.
- Log and daily report live in the app (Log tab, CSV export). No Google Sheet.
- Phone notifications replace Telegram. No Telegram bot is needed.

## Money: PLUG, RIOT or both

Settings → "Which bot gets the money": PLUG only, RIOT only, or Both with a slider (5% steps).

- **Fixed amount**: one amount per night for both bots together, split by the slider. Example: 200 USD at 50/50 means up to 100 USD each.
- **Compounding**: one starting capital, split by the slider. Each bot then reinvests its own profits and carries its own losses. The Bots tab shows the capital and realized profit/loss per bot.
- Moving the slider later redistributes the combined capital. Changing the amount or switching paper/live starts fresh.
- Cash buffer (default 5%) stays uninvested. When both bots buy on the same night, the second one only gets the cash the first one left.

Both bots share one Alpaca account. Keep nothing else in PLUG or RIOT in that account: the app sells the whole position of an active bot at the open (same as the n8n workflow).

## Build the APK

### Option A – GitHub builds it (nothing to install)

1. Create a **private** repository on GitHub and push this folder.
2. Open the repository → Releases → latest build → download `OvernightBot.apk`.
3. Copy `OvernightBot.apk` to the phone, open it, allow installing from this source.

### Option B – Android Studio

1. Install Android Studio, open this folder, wait for the sync.
2. Build → Build App Bundle(s) / APK(s) → Build APK(s). Or connect the phone by USB and press Run.

If the build stops with an error, send me the error text.

## Set up the phone

1. Open the app → Settings → choose Paper → enter the paper API keys → "Save keys and test".
2. Choose the bots, the amount, fixed or compounding → "Save settings".
3. Bots tab → switch the bot ON.
4. Do what the "Phone setup needed" card asks: allow background running (no battery restrictions), exact alarms, notifications.
5. Samsung, Xiaomi, Oppo, Huawei: also add the app to the "never sleeping" / "autostart" list in the phone's battery settings. These phones stop background apps more aggressively than Android itself.

The phone has to be switched on and online about 20 minutes before the US close. If a close run is missed, the app says so after the close.

## Test plan

1. Paper mode, "Signals only" off, both bots, small fixed amount. Watch one full cycle: buy at 15:55, sell at 09:31, profit/loss booked.
2. Leave the phone untouched (screen off, not charging) through one close. Check that the run still happened. This is the test that matters most.
3. Live mode with a small amount for at least a week before trusting it.

## Where things are

| Path | Content |
| --- | --- |
| `app/src/main/java/.../core/` | Trading logic without Android: `Engine.kt` (rules, split, schedule), `Runner.kt` (one run), `Alpaca.kt` (API) |
| `app/src/main/java/.../work/Scheduler.kt` | Alarms and background runs |
| `app/src/main/java/.../data/Creds.kt` | API keys, encrypted with the Android Keystore |
| `app/src/main/java/.../ui/` | The three screens |
| `app/src/test/.../CoreTest.kt` | Tests with a simulated Alpaca account |
| `keystore/overnightbot.jks` | Signing key so updates install over the old version. Replace it with a private key before giving the app to buyers. |

## Open points

- Not yet run on a real phone or against the real Alpaca API.
- Whether Alpaca accepts an `opg` order on Friday evening for Monday's open is assumed from the docs ("after 19:00"). The 08:45 fallback on Monday covers it if not.
- A missed opening auction is not sold at market automatically. The app alerts and tries again for the next open.
- Selling to buyers: the Play Store has extra rules for trading apps. An APK next to the current package is the simpler route.

Not financial advice. Historical results do not predict future results.
