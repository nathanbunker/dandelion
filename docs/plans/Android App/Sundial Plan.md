## Plan: Dandelion Sundial (deliverable 1)

Status: **Step 4 done (2026-11-12): capture, Done & next, Block, Edit.** Server 5.16.1 deployed to
production and app 0.4.0 installed on the S23 the same day. Next: step 5 (the Now card).

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

**S23 Ultra (Android 17), 2026-11-11.** Installing needed Samsung **Auto Blocker** turned off
(it blocks USB debugging and sideloading).

| Question | Result |
|---|---|
| Card visible on the lock screen? Collapsed, expanded, or icon only? | **Depends on a phone setting.** With Samsung's default lock-screen style (*Icons only*), only the sun icon shows in the status bar. With Settings → Lock screen and AOD → Notifications → **Details**, the card appears as one card in the list of notifications, collapsed, and expands to the full card. Sundial setup must tell the user to pick *Details* (and show silent notifications). |
| ✔ from the lock screen: runs without unlocking, asks to unlock, or doesn't respond? | **Runs without unlocking.** Log: `✔ ran: "Mow the lawn" · locked=true`, and the card updated. |
| Capture (inline reply) from the lock screen? | **Asks to unlock first**, then works (`Capture ran … locked=false`). Nathan: acceptable. |
| Do 5 rows fit when expanded? Is 3 better? | 5 rows fit in the expanded card. |
| Placement: silent section vs top (no sound). Which keeps it "under the clock"? | **Top (default importance, no sound)** sits at the top of the shade. Nathan saw no difference on the lock screen between the two. Use top. |
| Custom rows vs plain fallback: which reads better on Samsung? | Plain is cleaner; **Nathan prefers custom** (✔ per row). Keep plain as the fallback. |
| Check alert: sounds once, then the card goes quiet again on the next update? | Sounds once (the phone's regular notification sound). **Nathan wants Sundial's own alert sound** so he recognizes it: ship a distinct sound on the checks channel (still a notification, not an alarm, P-25a). |
| 1-minute alarms with the phone locked: how late? | **0 s late**, check and switch both (locked, not dozing). The silent switch changed the card with no sound. |
| Overnight test (alarm every 30 min): any missed or late alarms by morning? | **None missed, at most 1 s late.** 21 alarms from 18:45 to 04:45 (2026-11-11/12), 13 of them while the phone was dozing. Battery set to Unrestricted. Exact alarms are reliable on the S23. |
| Swiping the card away: allowed? | **Yes**, it can be swiped away even though it's ongoing (Android 14+). The next boundary should re-post it; in the spike, a switch after a swipe didn't (the swipe cleared `cardOn`). The real app re-posts at every boundary and app open. |

### Step 1 results (server, 5.16.0)

Built in `org.dandeliondaily.personal.service` plus two Jersey resources:

- **Schema `v5.16.sql`:** `web_api_client` gets `client_type` (AGENT/DEVICE), `device_status`
  (PENDING/APPROVED/REVOKED), `device_name`, `approved_date`, `revoked_date`. A device key is stored
  only as `sha256:<hex>` in `api_key`; agent keys are unchanged.
- **Endpoints** (`/api/v1`): `POST devices/sign-in {username, password, deviceName}` (public;
  returns `deviceKey` once; 5 failures per username in 15 minutes locks it out), `GET`/`DELETE
  devices/me`, `GET me` (`today`, `timeZone`, `apiLevel` 1), `GET personal/day?date=` (`overdue`,
  `parts.{WAKE,MORNING,AFTERNOON,EVENING}`; each action has `templateActionNextId`,
  `missedActionBehavior` and `expires`), `GET personal/actions/{id}` (adds notes; 404 for anything
  not the user's personal action).
- **Device rules** (`DeviceAccessRules`, in `ApiKeyAuthFilter`): pending devices may only call
  `devices/me` (403 `device_pending`); revoked get 401 `device_revoked`; approved devices may only
  call `me`, `devices/me` and `personal/*`. The agent endpoints, which include work, refuse them
  (403 `device_scope`).
- **Work never reaches the phone:** `PersonalDayService.organize` drops billable actions;
  `ActionAccessService` answers "not found" for them.
- **Devices page** (`DevicesServlet`, linked from Settings): approve and revoke. Settings' API key list
  no longer shows phones.
- **`/m/todo` now uses `PersonalDayService`** (logic moved from `TodoServlet`; characterization
  tests in `PersonalDayServiceTest`).
- **Not built yet** (comes with later steps): email-link sign-in, email notice of a new device,
  Waiting and Projects endpoints, `ProjectServlet` on the service.

**Verified locally, 2026-11-12:** 478 unit tests pass (26 new). `/m/todo` HTML for seven dates is
byte-identical before and after. A curl device signed in (stored hashed), was refused while pending,
approved on the Devices page, read the day, and was refused after revoking. The day JSON had the
same actions in the same order as `/m/todo` for today and tomorrow, none billable; 16 of today's 19
routines were marked `expires`. A work action and someone else's action both returned 404, and
existing agent keys still work.

### Step 2 results (app, 0.2.0)

Built in `android/` as `org.dandeliondaily.sundial` (the step 0 spike stays in `.spike`, no longer the
launcher; `dev.ps1 spike` opens it):

- **Stack:** Kotlin (built into AGP 9, 2.2.10), Compose + Material 3, one activity, one
  `AppViewModel`. HTTP is `HttpURLConnection` + `org.json`, not Retrofit: a handful of small calls.
- **Sign-in:** username, password and a device name; debug builds choose Local
  (`http://localhost:8089/dandelion/` over `adb reverse`) or Production, release builds only
  production. Plain HTTP is allowed only for `localhost`/`10.0.2.2`, debug only. The device key is
  stored encrypted with an Android Keystore AES-GCM key; no backup. "Waiting for approval" polls
  `devices/me` every 4 s. A 401 anywhere signs the phone out. ⋮ → "Sign out of this phone" revokes
  it on the server.
- **Today:** opens on the current part of the day (Wake 4, Morning 8, Afternoon 12, Evening 20,
  hard-coded until step 6's settings); before 4:00 it is still yesterday's Evening. Earlier parts
  read "✓ Wake · done" or "Wake · 2 left", later ones are collapsed with counts. Overdue sits at the
  top, collapsed, with days late; Auto Cancel routines never show there. Other days open every part
  that has items.
- **Day navigation:** ‹ › and a sideways swipe; titles follow `TodoServlet` (Today, Yesterday, the
  weekday for the next 7 days, "Next <weekday>" for the 7 after, then the date); a Today button.
- **Detail:** read-only page (project, date and part, deadline, estimate, type, routine and whether
  it expires, link, notes).
- **Refresh and offline:** ↻, refresh on every return to the app (jumping to the new day if it rolled
  over), red "Can't reach Dandelion · retrying" strip with a retry every 15 s.
- **Tests:** 12 JVM unit tests (`DayLogicTest`, `JsonTest`): part boundaries, before-Wake, section
  labels, default expansion, titles, overdue filtering, JSON parsing, error codes.
- **Not built:** email-link sign-in, Waiting and Projects (no server endpoints yet), the notification
  permission and reminder-schedule steps of first run (they come with steps 5 and 6).

**Verified on the S23, 2026-11-12:** Nathan signed in to local Dandelion, approved "Sundial on
SM-S918U" on the Devices page (the app moved on by itself about a second later), and looked through
today, other days and action details. No problems found.

**Known gap:** between midnight and 4:00 the app shows yesterday's Evening, but the server treats that
date as not today, so Overdue doesn't appear then. Fix with the card in step 5 (the card's Evening
shows all of today's open items anyway).

### Step 3 results (server 5.16.0, app 0.3.0)

- **`ActionQuickChangeService`** (`org.dandeliondaily.personal.service`): complete and cancel go through
  `ActionCompletionService.applyCompletion` (siblings, unblocking, and on complete an action-taken
  row with the action's description, as the dashboard and MCP do); move sets a calendar date and
  optional part. Each change writes `action_change_log` (actor = device name or username, source
  `sundial` or `mobile_web`) and returns a `Change` with every touched row's before/after state.
- **Undo receipts** (`ActionUndoService`, table `action_undo`): 5 minutes, same client only, all or
  nothing; refused with 409 `changed_elsewhere` if any row's status, date, part or blocker changed
  since. Undo also deletes the action-taken rows the change created.
- **Idempotency** (`IdempotencyService`, table `api_idempotency`): every phone write needs an
  `Idempotency-Key`; the response (success or refusal) is stored for a day, and a repeat returns it
  with `Idempotent-Replay: true`. The app retries a dropped connection once with the same key.
- **Endpoints** (`/api/v1/personal`, API level 2): `actions/{id}/complete`, `/cancel`, `/move {date,
  part}`, `/notes {text}`, `undo/{token}`. Refusals: 409 `not_open` (already done; the app just
  refreshes), 409 `routine_expires` (Auto Cancel routine to another day), 400 for the past.
- **`/m/` pages switched over.** Characterized before and after on four real actions: cancel and
  → Tomorrow unchanged; complete now also records the action taken and change log (intended);
  reschedule now saves the date asked for. **Bug fixed:** on the local server (JVM on Mountain
  time) `/m/` reschedule saved the day before (asked Nov 14, saved Nov 13) because it parsed the
  date as UTC midnight.
- **App:** tap the circle to complete; the action sheet has ✔ Done, ✖ Cancel, move chips
  (`MoveTargets`: Now, later parts, Tomorrow, Tmrw morning, next four weekdays, Next week, +2 wks;
  routines only within their day), Pick a date… with part chips, notes with Add, the link; an
  8-second Undo snackbar.
- **Tests:** 484 server tests (6 new), 17 app tests (5 new).
- **Not built yet:** *All to tomorrow* (batch move, with the card in step 5), Done & next, Block on
  new action, Edit details (step 4).

**Verified on the S23, 2026-11-12** against local data: Nathan checked off, undid, moved and used the
sheet. "Works great. No problem using it."

### Step 4 results (server 5.16.1, app 0.4.0)

- **`PersonalProjectService`:** open projects whose bill code isn't billable (the `/m/project` rule),
  most recently used first (latest `nextChangeDate` of the user's actions). `GET /personal/projects`.
- **`ActionCaptureService`** (caller's transaction; refuses before writing):
  - **Capture** (`POST /personal/capture {sentence, projectId?, date?, part?}`, 201): the web's sentence
    parser (`ActionSentenceImportService.buildActionFromSentence`) with only personal projects in its
    list, so `cars: rotate tires` works and a work project's name doesn't. A project chip or prefix is
    required (`project_required`). Date/part chips override the sentence; the parser's moment is
    turned into the user's calendar day. Linked-project prefixes (`a/b: …`) are refused for now.
  - **Done & next** (`actions/{id}/complete-next {sentence, date?, part?}`): creates the next action in
    the same project (unless the sentence names another), then completes through
    `ActionQuickChangeService`. A bare verb only completes, like `/m/action`.
  - **Block on new action** (`actions/{id}/block {description}`): the new action is for today in the same
    project and part; this one gets `blocked_by` and no date, and comes back when that one is done
    (`ProjectActionBlockerManager`).
  - **Edit details** (`actions/{id}/edit`, POST because Android's HttpURLConnection can't PATCH):
    description, type (the everyday types), date or clear date, part, link, personal project (priority
    copied from the project). Auto Cancel routines can't change day here either (`routine_expires`).
  - Every create and edit is written to `action_change_log` (source `sundial`).
- **Undo** now also cancels actions the change created (Done & next, Block), if they're still open.
- **API level 3.** The app hides capture and the new sheet tools when the server is older, and re-reads
  `/me` each time it opens, so a server deploy turns them on without signing in again.
- **App:** **+** button → capture sheet (sentence, When chips Now/later parts/Tonight/Tmrw…, four recent
  project chips plus More…, or a `project:` prefix); snackbar `Saved to House · Edit`; **Just added**
  at the top of Today for 10 minutes. The action sheet gains Done & next… (verb chips, sentence, When
  chips), ⛓ Block on new action, and ✎ Edit details (full screen). Fixed: when completing something
  unblocked another action, a second message pushed the Undo away; now it's one message.
- **Verified locally** with a curl device, then all test rows deleted: capture with a chip, with
  "tuesday" (→ Nov 17), with `cars:` plus a date chip; refusals for a work project id, a work-project
  prefix, no project, a past date; Done & next and its Undo (completed one reopened, new one
  cancelled); Block, then completing the blocker brought the original back to today; Edit of every
  field; refusals for a work project, a past date, an unknown type, an empty description, a routine to
  another day, a work action (404). 487 server tests, 19 app tests.
- **Not done:** `/m/action`'s Schedule & Block and Complete & Next were left as they are (they work, and
  the app replaces them; switching would change which projects their sentences can name). Assignee
  in Edit (needs a contacts endpoint). Waiting tab and its Follow up. Capture from the card, tile and
  shortcut (step 5).

### Open questions carried from Seedclock §11

- Whether weekends need different times from the 4/8/12/20 default.
- Answer the rest during step 0 and step 6, with the phone in hand.
