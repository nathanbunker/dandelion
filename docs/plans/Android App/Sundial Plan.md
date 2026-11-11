## Plan: Dandelion Sundial (deliverable 1)

Status: **Step 4 done, design chosen (2026-11-11).** Next: step 0, the lock-screen spike.

**Dandelion Sundial** is the Android app for Dandelion. Nathan chose it after the contest
(`Contest.md`, judge report in `contest/judging/`).

**Seedclock's proposal is the base spec:** `contest/claude-2/proposal.md`. Build what it describes,
with the changes below. Where this doc and the proposal disagree, this doc wins. The name comes
from Sundial (`contest/claude-1/`); Seedclock's design is renamed, not merged with Sundial's.

### What Nathan liked, in his words

> I don't need a system that tells me 50 things to do today, I need one that tells me the three
> things I shouldn't forget right now. Then allow me to go in and get more details. I love that it
> will sit on my phone under the clock.

"Three" here describes how few personal items he has in a day, not a limit (change 2).

### Changes to the Seedclock proposal

1. **Name: Dandelion Sundial.** Replace "Seedclock" everywhere: app label "Sundial" (dev build
   "Sundial Dev"), application IDs `org.dandeliondaily.sundial` and `org.dandeliondaily.sundial.dev`,
   Kotlin package `org.dandeliondaily.sundial`, log tag `Sundial`, release key `sundial-release.jks`,
   the device name on the approval page ("Sundial on Nathan's S23"). The card header reads
   `Sundial · Morning`. Server-side names (`org.dandeliondaily.personal.service`, `/api/v1/personal/*`)
   stay as proposed.
2. **No cap and no ranking on the card.** Keep Seedclock's rule: up to five rows for the current part
   of the day, in the same order as the app, then "+N more · Open". Don't add a top-N, a priority
   score or any "most important" logic. Nathan doesn't rank personal actions, deliberately. A
   typical personal part of the day has only a few items.
3. **Personal only, never work.** Every Dandelion action is work (`ActionNext.billable = true`) or
   personal (`billable = false`). The app never shows, counts, alerts on or captures work actions,
   in any view, setting or rule. This is stricter than "not in the MVP": showing work would be a
   separate feature, designed separately if it ever happens. Personal actions may appear on the
   desktop during work, but work never comes to the phone. The server enforces it: every
   `/personal/*` read and write rejects or excludes billable actions, so a bug in the app can't
   leak or change one. Capture always creates personal actions.
   (In student mode `billable` means School vs Chores. That's a different meaning, and student
   mode handles it separately.)
4. **Moving actions: labeled buttons, not the Dial.** Keep Seedclock's action sheet (§2.3): move
   chips for later today, tomorrow, the next weekdays by name, next week, +2 weeks, and *Pick a
   date…* with a month calendar and Wake/Morning/Afternoon/Evening chips. Moves more than 7 days
   ahead are common, so the far-out targets must stay one tap away. Sundial's press-and-drag Dial
   isn't built. Nathan found it too fiddly (telling Saturday from Sunday on a ring). After a few
   weeks of use, look at which far-out moves he actually makes and adjust the chips to match.
5. **The lock screen: try, and accept what Samsung allows.** If the S23 requires an unlock before a
   lock-screen ✔ runs, that's acceptable: the card still reminds him and gets him engaged. Even a
   card that only shows what's waiting, so he unlocks and acts, is worth having. Step 0 finds out
   what works. The fallbacks in Seedclock §11 apply.
6. **Refresh as proposed.** The card may be up to ~15 minutes behind desktop changes. That's
   acceptable: Nathan works on the computer during the day and does personal items on the phone
   after work. Keep ↻ on the card and in the app, plus refreshing on app open, after each change and
   at each boundary. No push notifications.
7. **Reminders use exact alarms** (Seedclock §3.4), not inexact WorkManager timing.

### Taken from Relay (`contest/openai/`)

- **Idempotency keys** on every write. A flaky connection retrying a tap must not complete, move or
  capture twice.
- **Reuse `ActionCompletionService`** (`org.dandeliondaily.shared.service`). The desktop dashboard
  and the MCP already complete actions through it. `ActionQuickChangeService` (Seedclock §7.1) must
  call it for completion rather than repeating the `MobileBaseServlet` logic, so the phone, the
  dashboard and MCP complete actions the same way (G-13). Seedclock and Sundial both missed it.
- **Characterization tests** before moving servlet logic into services. Pin down what `/m/*` does
  today (day lists, complete, cancel, move, Schedule & Block, Complete & Next), then refactor.

**Not taken:** Relay's fake-data prototype. Development uses real data (next section).

### Data during development

- **Local first:** the local Dandelion (`localhost:8089`) runs on a copy of production data, so it's
  real data that's safe to break. Server work and new endpoints are tested there.
- **Then production:** `deploy\deploy.ps1` deploys in a couple of minutes, and Nathan is happy to
  test on production. He and his daughter are the only users.
- The main risk is a bug that **writes**: completing the wrong action, a "move all" that moves too
  much, a retry applied twice. Idempotency keys, the server-side work/personal check, undo receipts
  and the characterization tests are the guard rails.
- Step 0's throwaway card uses canned rows. That's a phone test, not a fake server, and is fine.

### Build order

Seedclock §9, unchanged except:

- **Step 0 (spike):** the one-day lock-screen test on the S23, named Sundial. Record what Samsung
  allows (✔ without unlock, five rows, alert once on a channel change, survival under battery
  management) in this file before step 1.
- **Step 1:** add the characterization tests first, and the billable check in `PersonalDayService`
  and `ActionAccessService`.
- **Step 3:** `ActionQuickChangeService` uses `ActionCompletionService`; writes take an idempotency
  key.

### Open questions carried from Seedclock §11

- Default clock times for the weekday, Saturday and Sunday profiles. Could Dandelion's day
  availability seed them?
- Answer the rest during step 0 and step 6, with the phone in hand.
