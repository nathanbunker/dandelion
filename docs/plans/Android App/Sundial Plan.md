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
8. **The four parts of the day mean what Nathan uses them for** (`Personal App.md`, "What the four
   parts of the day mean", and P-25). The proposals all assumed clock-time meanings: Seedclock's
   default weekday profile ends Morning at 12:00 and starts Evening at 17:00. That's wrong for
   Nathan. **Morning** is "during the working day" (before businesses close), **Afternoon** is after
   he clocks out at about 3pm, and **Evening** is getting ready for bed.
   - The start times are user settings on the phone, as in Seedclock's profiles. Nothing about
     their meaning is hard-coded. Labels stay Dandelion's names.
   - **Nathan's times** (the default profile, every day, editable): **Wake 4:00, Morning 8:00,
     Afternoon 12:00, Evening 20:00.** Evening lasts until Wake at 4:00 the next day. Weekday
     profiles stay available but aren't needed to start.
   - **The one place work and phone overlap.** The desktop dashboard shows personal Morning
     actions during the work day, so Nathan may complete them on the computer while the card is
     still showing them. The "I don't use both at once" assumption doesn't hold for Morning. So:
     **refresh before any alert fires**, so a nudge never lists something already done on the
     desktop; and a ✔ on something already done just refreshes the card (Seedclock already does
     this). Completing through `ActionCompletionService` keeps the two in step.
9. **The card changes; it doesn't ring.** This replaces Seedclock's reminder behavior (§3.1).
   - **Switching parts of the day is silent.** At 4:00, 8:00, 12:00 and 20:00 the card quietly
     changes to the new part, so whenever Nathan looks at his phone it's in the right mode and
     shows what to do. No start-of-period prompts (P-22 is dropped).
   - **One check, an hour before each switch:** at **7:00** (Wake), **11:00** (Morning) and
     **19:00** (Afternoon). If anything in the current part of the day is still open, Sundial
     alerts once. If it's all cleared, meaning completed, cancelled or moved, it stays silent. The
     point is the habit: clear or move items before 7, 11 and 7. **Morning's matters most:**
     Morning items have to be done by noon.
   - **Never for Evening.** No check before 4:00, and no wake-up alert about last night's Evening
     items.
   - So there are **at most three alerts a day**, and none on a day he keeps up.
   - Seedclock's rules (Nudge / Quiet / Hidden by project and action type) still decide which
     actions count toward a check.
   - **Settings for each part of the day:** its start time (the order Wake → Morning → Afternoon →
     Evening is fixed), whether it has an end check, and when that check fires. The times above
     are Nathan's starting values, all changeable on the phone.
   - **The 7:00 Wake check is "record your routine",** not a wake-up call. By 7 the morning
     routine should be done and checked off. Sundial is not an alarm clock: checks are ordinary
     notifications, never alarm sounds or full-screen alerts, and Do Not Disturb applies.
   - **A part of the day starting doesn't mean its items start then.** At noon the card shows
     Afternoon, but Afternoon items are "sometime later in the day", not due at noon.
10. **What the card carries over.** Leftovers (Seedclock's leftovers state) work per part of the
    day:

    | Card showing | Shows |
    |---|---|
    | Wake (4–8) | Wake items |
    | Morning (8–12) | Morning items |
    | Afternoon (12–20) | **Morning leftovers first** ("from Morning"), then Afternoon items |
    | Evening (20–4) | **Every open item from today** (Morning and Afternoon leftovers), then Evening items |

    **Wake items never carry over.** Once Morning starts, unfinished Wake items leave the card.
    They're still in the app's Today list under Wake.
11. **Templated (recurring) actions are handled differently.** Wake and Evening are almost always
    routines generated from Plan Ahead templates. Each template has a *missed action* setting
    (`ActionNextTemplateConfig.missedActionBehavior`): **Auto Cancel** (the default), Carry
    Forward or Ignore. `TemplateGenerationService.applyMissedActionBehavior` runs every hour (at
    :15) and cancels any Auto Cancel instance still open from an earlier day. So an Evening routine
    he doesn't check off is simply gone the next morning. That's intended, not a failure.
    - The API marks each action as a **template instance** (`templateActionNextId`) and includes its
      missed-action behavior, so the app can treat it accordingly.
    - **No "→ tomorrow" on an Auto Cancel instance,** and *All to tomorrow* skips them. Tomorrow
      already has its own instance; moving one would make a duplicate. Their row offers ✔ and
      ✖ (skip).
    - **Unfinished Auto Cancel instances never become overdue** on the card or in the app. They
      don't nag; they just expire.
    - One-off (custom) actions carry over and go overdue as usual. Carry Forward and Ignore
      instances behave like one-off actions.

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

### Step 0 results

The spike is in `android/` (package `org.dandeliondaily.sundial.spike`, throwaway). Build and
install with `.\android\dev.ps1 run`; read results with `.\android\dev.ps1 spikelog`. Every tap on
the card logs `locked=true/false`, so "did it run from the lock screen" is recorded, not guessed.

**Emulator (Pixel 7, Android 16), 2026-11-11:** the app builds and installs, and the card posts
with custom rows and a ✔ on each. A ✔ in the shade completes the row and the card updates. The
card re-posts itself after an app update. Finding: on a silent (low-importance) channel, stock
Android files the card under **Silent at the bottom of the shade**. The spike can also put the
card on a default-importance channel with no sound ("Placement: top") to compare.

**S23 Ultra** (to fill in):

| Question | Result |
|---|---|
| Card visible on the lock screen? Collapsed, expanded, or icon only? | |
| ✔ from the lock screen: runs without unlocking, asks to unlock, or doesn't respond? | |
| Capture (inline reply) from the lock screen? | |
| Do 5 rows fit when expanded? Is 3 better? | |
| Placement: silent section vs top (no sound). Which keeps it "under the clock"? | |
| Custom rows vs plain fallback: which reads better on Samsung? | |
| Check alert: sounds once, then the card goes quiet again on the next update? | |
| 1-minute alarms with the phone locked: how late? | |
| Overnight test (alarm every 30 min): any missed or late alarms by morning? | |
| Swiping the card away: allowed? | |

### Open questions carried from Seedclock §11

- Whether weekends need different times from the 4/8/12/20 default.
- Answer the rest during step 0 and step 6, with the phone in hand.
