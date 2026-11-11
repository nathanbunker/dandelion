# Seedclock: proposal

*Your day lives on your lock screen, one part at a time.*

Contestant `claude-2`. Covers `Proposal Brief.md` sections 1 to 11, plus section 12, "Why this one".

A dandelion seed head is called a "clock": children blow on it to tell the time. Dandelion already
splits the day into Wake, Morning, Afternoon and Evening. Seedclock tells that time.

---

### What changed since the pitch

The pitch had two parts: the **Now card** (one living notification) and **the Sweep** (a
full-screen run through leftovers at the end of each part of the day). After reading the other
pitches:

- **The Sweep is gone.** Sundial (`claude-1`) built its whole entry around an end-of-pass ritual
  that it also calls a Sweep. We came up with it independently, but it is Sundial's signature, not
  mine, and the contest asks for a singular idea. It was also the weaker half of my pitch: it
  sends Nathan *into* the app at the very moment Seedclock promises he won't need to go there.
- **Leftovers are now handled on the card itself.** When a part of the day ends, the unfinished
  items stay on the Now card, marked *from Morning*, each with ✔ and *Tomorrow* buttons, plus one
  *All to tomorrow* button. The leftovers also stay in the app's Today view, where every move
  is available. There's no separate ritual to learn.
- **Added "pin a project to the card"** (P-18 from the lock screen) and **inline capture typed
  straight into the card** (P-17), so capture also works without opening the app.
- **Waiting follow-ups reuse Schedule & Block** (P-15), so a follow-up brings the waiting item back
  when it's done.
- **I'm more careful about the lock screen.** Whether Samsung lets a ✔ on a lock-screen
  notification run without unlocking first is something to verify on the S23 in step 0. Section
  11 has the fallback.

The bet is the same, and now it's the whole idea: **the notification is the main screen.**

---

## 1. Summary

**The idea.** Most to-do apps wait to be opened. Seedclock comes to Nathan as **one ongoing
notification, the Now card**, which always shows the current part of the day ("Morning · 3 left")
with a ✔ beside each action. He checks things off from the notification shade or the lock screen,
over whatever app he's in. When the part of the day changes, the card **rolls over** to the next
one instead of a new notification arriving. Reminders aren't separate pings. They are the same
card **speaking up once** at the right moment: at the start of a part of the day (P-22), and at
its end if Nathan-chosen kinds of items are still undone (P-21). Unfinished items stay on the card,
marked as leftovers, until he ticks them, moves them, or the day ends.

The **app** is the full-control companion: Today (by time of day, with overdue), other days,
Waiting, Projects, the action sheet (notes, link, edit, every kind of move, Schedule & Block,
Complete & Next), and capture. Every change is immediate, with Undo and no confirmations.

**Key choices**

| Choice | Decision | Main alternative rejected |
|---|---|---|
| Main surface | One ongoing notification (the Now card) plus a companion app | A home-screen widget: it's only seen from the home screen, not on the lock screen or over other apps |
| Reminders | The card changes state and alerts at most once per period boundary; rules by project and action type; three weekday profiles | Separate notifications for each prompt or each item: they pile up in the shade and train Nathan to ignore them |
| Language and UI | Kotlin, Jetpack Compose, Material 3 | XML Views (older and more verbose); Flutter or React Native (not native, G-2) |
| Libraries | Retrofit, OkHttp, kotlinx.serialization, WorkManager, DataStore, manual DI | Hilt and Room: they add build complexity, and online-only (G-9) means no local database |
| Code location | `android/` folder in this repository | A separate repository: API and app changes belong in one commit |
| Minimum Android | minSdk 28 (Android 9), targetSdk 36 | minSdk 31+: it would drop the Note8 and buys nothing Seedclock needs (section 5) |
| Auth | Per-device token on the existing `WebApiClient` table, with a pending → approved → revoked lifecycle | OAuth/JWT with refresh tokens: more moving parts, and every token rotation is another way to get logged out (G-4) |
| Server | New `/api/v1` endpoints over new shared services that the `/m/*` servlets also switch to (G-13) | App-only endpoints that copy servlet logic: behavior would drift |
| Undo | Server-side undo receipts that restore exactly what changed, including actions that were unblocked | Client-side "reverse the change": it can't re-block the actions that a completion unblocked |
| Refresh | Refresh after every change, at each period boundary, on app open, and every 15 minutes in the background | Firebase push: a Google project, server credentials and a new failure mode. It's an option later (section 11). |

**Why it fits Nathan.** The requirements say the functions of the mobile pages are right but the
interactions are clunky. The clunkiest step in any phone to-do list is the first one: unlock, find
the app, wait, find the period, tap. The Now card removes that step for the most common action of
all, which is checking off the current thing. Everything else gets a clean native screen, two taps
deep at most.

---

## 2. Interaction design for personal mode

### 2.1 The Now card (the notification)

There's always exactly one Seedclock notification while a day is in progress. It has four states.

**Collapsed** (lock screen, top of the shade):

```
 ✿ Seedclock · Morning                        9:41
 3 left · next: Call the plumber
```

**Expanded** (pull down, or open by default on the lock screen):

```
 ✿ Seedclock · Morning · 3 left               9:41
 ───────────────────────────────────────────────
  Call the plumber about the leak          [ ✔ ]
  Pay the water bill               Home    [ ✔ ]
  Email Sister Lee the agenda    Church    [ ✔ ]
 ───────────────────────────────────────────────
  [ Capture ]   [ Open ]   [ ↻ ]
```

- **Rows:** up to five actions for the current part of the day, in the same order as the app.
  Beyond five: "+2 more · Open".
- **✔** completes the action (P-6). The row turns into `✓ Call the plumber · Undo` and stays
  until the next change to the card or for 5 minutes, whichever comes first (P-10).
- **Capture** opens a text box inside the notification (Android's inline reply). Nathan types or
  dictates "call Bob thursday evening" and it's saved through the same sentence parser the web
  uses. More in 2.6.
- **Open** opens the app on Today. **↻** refreshes now.
- Tapping a row opens the app's **action sheet** for that action.

**Leftovers state** (after a part of the day ends with items still open):

```
 ✿ Seedclock · Afternoon · 2 left · 1 from Morning
 ───────────────────────────────────────────────
  from Morning
  Pay the water bill                 [ ✔ ] [ → ]
  Afternoon
  Pick up the dry cleaning                 [ ✔ ]
  Return library books                     [ ✔ ]
 ───────────────────────────────────────────────
  [ Capture ]  [ All to tomorrow ]  [ Open ]
```

Leftover rows add **→** (move to tomorrow, same time of day). **All to tomorrow** moves every
leftover at once, with a single Undo. Anything subtler (another time of day, a date three weeks
out) is one tap away in the app's action sheet.

**Day done state:** when Evening ends, the card says *Day done · 9 completed, 2 moved*. It turns
silent and removes itself the next time Nathan opens the shade, or after 30 minutes. The next
morning's Wake card appears at Wake time.

**Pinned project.** A project screen in the app has **Pin to card**. While a project is pinned, the
card's header says `Capturing to Church`, and Capture adds to that project (P-18). The pin is
cleared automatically when the part of the day ends, so it can't silently capture to the wrong
project the next day.

**Dismissing it.** Since Android 14, users can swipe away ongoing notifications. Seedclock treats
that as *hide until the next part of the day*. It's a feature, not a bug: swiping it away is the
quickest way to silence it.

### 2.2 Today in the app (P-1, P-2, P-3)

```
 ┌──────────────────────────────────────────┐
 │  ‹   Wednesday, Nov 11   ›      [Today]  │
 │  ────────────────────────────────────    │
 │  ▸ Overdue · 2                           │
 │  ✓ Wake · done                           │
 │  ▾ Morning · 3 left          (now)       │
 │    ○  Call the plumber about the leak    │
 │    ○  Pay the water bill          Home ⓘ │
 │    ○  Email Sister Lee the agenda  ⛪    │
 │  ▸ Afternoon · 4                         │
 │  ▸ Evening · 2                           │
 │                                          │
 │                           ( + Capture )  │
 │  ─────────────────────────────────────── │
 │   Today      Waiting · 5      Projects   │
 └──────────────────────────────────────────┘
```

- It **opens on the current part of the day**, expanded and scrolled to the top. Earlier parts
  collapse to one line: "✓ Wake · done" or "Morning · 1 left". Later parts are collapsed with
  counts, and one tap opens them. Any section can be expanded at any time.
- **Overdue** sits at the top, collapsed to a count so it doesn't swamp today. Tapping it opens
  the list. Each row shows how many days late it is.
- **Each row:** a large circle on the left (tap = complete), the description, then the project icon
  or a short project name. Tapping the row opens the action sheet. There are no hidden gestures;
  everything is a visible tap target sized for a thumb.
- **Other days:** ‹ and › (or a horizontal swipe on the header) step one day. Titles follow the
  web's wording: "Thursday", "Next Thursday", or the date. Other days show only that day's
  actions, grouped the same way, like `TodoServlet` does today. Seeing tomorrow's item and wanting
  it now is a single move to *Now* (2.3).
- **Undo:** every change shows a snackbar for 8 seconds: `Completed "Call the plumber" · Undo`.
  A new change replaces the snackbar, but the earlier change can still be undone from the action
  sheet's history line for 5 minutes.
- **Can't reach Dandelion** (G-9): a red strip under the header, `Can't reach Dandelion · retrying`,
  and every change button is disabled until the connection is back. Nothing is queued.

### 2.3 The action sheet (P-8 to P-16)

Tapping an action anywhere opens one bottom sheet. It is the **same component** everywhere (Today,
Waiting, Project, the card's row tap).

```
 ┌──────────────────────────────────────────┐
 │  ─────                                   │
 │  Call the plumber about the leak         │
 │  Home · will · Today, Morning            │
 │                                          │
 │  [ ✔ Done ]  [ Done & next… ]  [ ✖ ]     │
 │                                          │
 │  Move                                    │
 │  (Now) (Afternoon) (Evening)             │
 │  (Tomorrow) (Tmrw morning) (Fri) (Sat)   │
 │  (Next week) (+2 wks) (Pick a date…)     │
 │                                          │
 │  Notes                                   │
 │  Called Monday; he said Thursday.        │
 │  [ Add to notes…                       ] │
 │                                          │
 │  🔗 Open link     ⛓ Block on new action  │
 │  ✎ Edit details                          │
 │  Last change: moved from Mon · Undo      │
 └──────────────────────────────────────────┘
```

- **Complete** (P-6), **cancel ✖** (P-7): immediate, then the sheet closes and the snackbar offers
  Undo (P-10).
- **Move chips** (P-8): later parts of today; *Now* (the current part of today, for pulling a
  future item forward); tomorrow at the same time of day; *Tmrw morning*; the next four
  weekdays by name; *Next week* (same weekday); *+2 wks*. One tap moves it and closes the sheet.
- **Pick a date…** (P-9) opens a compact month calendar with **Wake / Morning / Afternoon /
  Evening** chips under it and jumps for *+1 week, +1 month*. Two taps cover a date a month away.
- **Notes** (P-11, P-12): the notes are shown read-only. *Add to notes* appends through
  `ActionNotesService.append`, the same as the web.
- **Open link** (P-13) opens the URL in the browser.
- **Edit details** (P-14) opens a full-screen form: project, description, action type, date, time
  of day, link, assignee (from the project's contacts). It saves with one button and offers Undo.
- **Block on new action** (P-15): type what has to happen first. That creates it for today on the
  same project and parks this action until it's done, exactly like today's "Schedule & Block".
- **Done & next…** (P-16): the verb chips from today's page (*I will, I might, I am waiting, …*)
  and one sentence box: "I will call him back Thursday morning". Submitting completes this action
  and creates the next one through `ActionSentenceImportService`. The snackbar's Undo reverses
  both.

### 2.4 Waiting (P-4)

The **Waiting** tab lists every open *waiting* action, oldest first, with how long it has been
waiting:

```
  Waiting · 5
  ─────────────────────────────────────────
  Bob's quote for the fence       9 days  ›
     [ Follow up ]  [ Got it ✔ ]  [ Move ]
  Library hold for Anna           3 days  ›
```

- **Follow up** opens the capture box pre-filled with "I will follow up with Bob about the fence"
  for tomorrow. Saving makes it a **blocking action** (P-15) for the waiting item, so when Nathan
  completes the follow-up, the waiting item comes back on its own.
- **Got it ✔** completes the waiting item. **Move** opens the move chips.
- The tab's badge shows the count. To bring waiting items to him, the **Waiting check** (section
  3) puts the oldest ones on the Now card a couple of times a week.

### 2.5 Projects (P-5)

A list of open personal projects, alphabetical, each with its icon and today's count, plus a
search box at the top. Opening a project shows **Overdue, Due today, Due later, Unscheduled** (the
same groups as `ProjectServlet`), with the same rows and action sheet. The project screen's capture
button reads **+ Church**, and the screen has **Pin to card** (2.1).

### 2.6 Quick capture (P-17 to P-20)

Capture is reachable from five places: the **+** button in the app, the **Capture** button on the
Now card, the app icon's long-press shortcut, a **Quick Settings tile**, and **Follow up** on a
waiting item.

In the app it's a short sheet:

```
 ┌──────────────────────────────────────────┐
 │  What and when?                          │
 │  [ call the clerk about dinner tuesday ] │
 │  When: (Today) (Tonight) (Tomorrow) (…)  │
 │  Project: (Church ✓) (Home) (Family) (…) │
 │                                [ Save ]  │
 └──────────────────────────────────────────┘
```

- **What and when** (P-17): one sentence, typed or dictated with the keyboard's microphone. The
  server's sentence parser reads dates and times of day ("tuesday", "tomorrow evening"), exactly as
  on the web. A *When* chip overrides whatever the parser found. With no date at all, it defaults
  to today.
- **Project from context** (P-18): when capture starts from a project screen, or a project is
  pinned to the card, that project is pre-selected and the chips stay hidden.
- **Pick a project** (P-19): otherwise the chips show the four most recently used projects plus
  *…* (searchable list). The `church: ...` prefix that the web's quick capture already accepts
  works too.
- **Details later** (P-20): after saving, the snackbar says `Saved to Church · Add details`.
  Tapping it opens the action sheet with **Edit details**. The new action also appears as
  **Just added** at the top of Today for 10 minutes, so it's easy to find a minute later.

From the **Now card**, capture is the inline text box. The project is the pinned project, or a
`project:` prefix, or otherwise his **default capture project** (a setting). The card then shows
`Saved to Home · [Church] [Family] [Edit]` for a minute, so the project can be changed with one
tap (P-19). *Edit* opens the app's action sheet (P-20).

### 2.7 Signing in and settings

- **First run:** server (Production; debug builds also offer Local) → username and password, or
  *Email me a link* → "Waiting for approval" (with what to do on the desktop) → notification
  permission → reminder schedule (pre-filled defaults) → Today.
- **Settings:** reminder schedule and rules (section 3), default capture project, this device's
  name, "Sign out of this phone", and the app version.

---

## 3. Reminders

### 3.1 How they behave

Seedclock has **one notification** and **four kinds of moments** when it may speak up. "Speak up"
means it alerts once, with sound or vibration as Nathan sets it in Android's channel settings, then
goes quiet. Each kind can be *Alert*, *Card only* (the card changes silently) or *Off*, separately
for each part of the day and each day-of-week profile.

| Moment | What the card does | Default |
|---|---|---|
| **Start of a part of the day** (P-22) | Rolls over to the new part, e.g. *Evening · 3 · Time to be done with work.* The message is editable for each period. | Alert at Morning and Evening; card only at Wake and Afternoon |
| **End of a part of the day** (P-21) | If *Nudge*-class items are still open: *Morning ended · 2 left*, and they become leftovers (2.1) | Alert at the end of Morning, Afternoon, Evening; off for Wake |
| **Waiting check** (P-4) | Adds a *Waiting* section with the oldest waiting items (7+ days) and *Follow up* buttons | Monday and Thursday, at the start of Evening |
| **Day done** | *Day done · 9 completed, 2 moved*, silent | Card only |

Rules that keep it from nagging:

- **One alert per moment, never repeated.** If he ignores an end nudge, the leftovers stay on the
  card silently. There are no escalations and no re-alerts.
- **Nothing to say, no alert.** An end nudge fires only if *Nudge*-class items are left. A start
  prompt for an empty part of the day changes the card silently.
- **Snooze 30 min** is a button on an alerting card. It re-alerts once.
- **Quiet today** (in Today's ⋮ menu and in Settings) keeps the card but stops every
  alert until tomorrow.
- **Do Not Disturb** and Samsung's modes are respected, because these are ordinary notifications.
- The Waiting check rides along with the Evening start prompt, so it never adds an alert of its own.
- With the defaults, an ordinary weekday has **at most 5 alerts** (plus any he snoozes), and a day where he keeps up has
  **2** (the Morning and Evening start prompts).

### 3.2 Configuration (P-23, P-24)

**Schedule.** Dandelion supplies the four parts of the day and which actions are in each (P-24).
The phone supplies the clock times:

```
  Profile     Wake   Morning  Afternoon  Evening  Day ends   Days
  Weekdays    6:00   8:30     12:00      17:00    21:30      Mon–Fri
  Saturday    7:00   9:00     12:00      17:00    22:00      Sat
  Sunday      7:00   9:00     13:00      17:00    21:30      Sun
```

There are three profiles by default. Each weekday can be assigned to any profile, and he can add
profiles. The end of one part of the day is the start of the next. An end nudge can be moved
earlier ("15 minutes before Morning ends") so there's still time to act.

**Rules (groups and classes of actions).** An ordered list. The first matching rule decides how an
action is treated:

| Rule | Treatment |
|---|---|
| Action type *might*, *would like to*, *goal* | **Quiet**: on the card and in counts, but never causes an alert |
| Action type *waiting* | **Waiting check only** |
| Project *Garden*, on profile *Sunday* | **Hidden** from the card (still in the app) |
| Everything else | **Nudge** |

A rule matches one or more projects and/or action types, optionally limited to profiles. The
treatments are **Nudge**, **Quiet** and **Hidden**. That's the "groups or classes of actions" of
P-23, without any per-action settings. None of this changes anything in Dandelion (P-24).

### 3.3 What the alert lets him do directly

From the card, without opening the app: **complete** any listed item, **undo** that, **move a
leftover to tomorrow**, **move all leftovers to tomorrow**, **capture** a new action (inline
text), **change a just-captured action's project**, **follow up** on a waiting item (opens a short
capture with the text pre-filled), **snooze**, **refresh**, and open anything in the app.

### 3.4 How it works technically

**The card is a pure function.** A plain Kotlin module (`core`, no Android dependencies) computes:

```
CardState = buildCard(now, schedule, rules, day /* from the server */, pins, snoozes, recentChanges)
NextWakeups = planWakeups(now, schedule, rules, day)
```

All of the reminder logic lives there, and it is tested on the JVM with a fake clock. The Android
side only draws the `CardState` and schedules the `NextWakeups`.

**Drawing the card.**

- `NotificationCompat` with `DecoratedCustomViewStyle` and `RemoteViews` rows. Each row has its own
  ✔ (and → for leftovers) `PendingIntent` (`FLAG_IMMUTABLE`, explicit) to a `BroadcastReceiver`.
  The expanded custom view has about 256 dp of room, enough for a header and five rows.
- Two channels: **Now** (low importance, silent, public on the lock screen) and **Prompts**
  (default importance). A "speak up" re-posts the same notification ID on the Prompts channel with
  `setOnlyAlertOnce(false)`. The next ordinary update re-posts it on Now, silently. If the S23
  doesn't handle the channel switch cleanly (step 0 checks this), the fallback is a separate
  short-lived prompt notification with `setTimeoutAfter`, grouped with the card.
- `setOngoing(true)`, `VISIBILITY_PUBLIC`, and a `deleteIntent` that records "hidden until the next
  part of the day".
- Inline capture uses `RemoteInput`, the same mechanism as messaging apps' inline reply.

**Doing the work from a button.** The receiver calls `goAsync()`, makes the API call (10-second
timeout), and re-posts the card. On failure, the row says `Not saved · can't reach Dandelion` and
nothing is changed or queued (G-9). The card updates optimistically and rolls back if the server
says no. A tick on an action that was already completed elsewhere just refreshes the card.

**Waking up at the right time.**

- **Period boundaries** (at most about 10 a day) use `AlarmManager.setExactAndAllowWhileIdle`. On
  Android 13+ the app declares `USE_EXACT_ALARM`. That permission is granted at install, and the
  Play Store policy that limits it to alarm and calendar apps doesn't apply to a sideloaded app.
  Android 9 to 12 don't need a permission for this (12's `SCHEDULE_EXACT_ALARM` is granted by
  default there). Only the next wake-up is scheduled; each alarm schedules the next.
- **Rescheduling** on `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED` and
  `MY_PACKAGE_REPLACED`.
- **Freshness:** a WorkManager periodic job (15 minutes, the Android minimum, network required)
  re-fetches today. The card also refreshes at every boundary, after every change, whenever the app
  opens, and when **↻** is tapped. A change made on the desktop reaches the card within about 15
  minutes while the phone is in use. The status line shows "as of 9:41" when it's older than that.
- **Samsung's battery management** kills background work aggressively. Onboarding asks Nathan to put
  Seedclock in *Settings → Battery → Background usage limits → Never sleeping apps*, once, and
  checks `isIgnoringBatteryOptimizations`.
- **Last known day** is stored in DataStore, so after a reboot the card appears immediately, with
  "as of …", until the first refresh.

---

## 4. App architecture

**Language and toolkit:** Kotlin 2.x, Jetpack Compose with Material 3, single activity, Navigation
Compose. This is Google's current default stack, and the one agents have seen the most code for.

**Libraries** (all AndroidX or Square, versions pinned in `gradle/libs.versions.toml`):

| Need | Library |
|---|---|
| UI | Compose BOM, Material 3, Navigation Compose, Lifecycle ViewModel |
| Async | kotlinx.coroutines (StateFlow in ViewModels) |
| HTTP and JSON | Retrofit, OkHttp, kotlinx.serialization |
| Local settings | DataStore (preferences + one JSON blob for rules) |
| Background | WorkManager (refresh), AlarmManager (boundaries) |
| Notifications | `androidx.core` NotificationCompat |
| Secrets | Android Keystore (AES key) that encrypts the device token in DataStore |
| Tests | JUnit 4, kotlinx-coroutines-test, OkHttp MockWebServer, Robolectric, Compose UI test |

No dependency-injection framework: one small `AppContainer` object wires things together by hand.
No local database: the app is online-only (G-9).

**Where the code lives:** an `android/` folder in this repository, with its own Gradle wrapper. The
server and the app change together when an endpoint changes, agents see both sides in one
checkout, and `CLAUDE.md` can document both. The Maven build ignores `android/`.

```
android/
  settings.gradle.kts  gradle/libs.versions.toml  gradlew  gradlew.bat
  core/                 pure Kotlin (JVM), no Android
    model/              Action, Day, TimeSlot, Project, Waiting…
    schedule/           profiles, rules, buildCard(), planWakeups()
  app/
    src/main/kotlin/org/dandeliondaily/seedclock/
      AppContainer.kt  MainActivity.kt
      api/              Retrofit interface, DTOs, auth interceptor
      auth/             sign-in, approval wait, token store
      shared/           theme, day navigation, ActionSheet, CaptureSheet, undo snackbar, offline strip
      personal/         Today, Waiting, Projects, Edit, Settings
      now/              NowCardRenderer, CardActionReceiver, AlarmScheduler, RefreshWorker, QS tile
      student/          (deliverable 2) School, Store, Bank
    src/debug/          network config for local cleartext, debug time-travel receiver
  scripts/              dev.ps1 (build, install, launch, logs, screenshot)
```

**One app, two modes (G-1).** After sign-in, `GET /api/v1/me` returns the account's
`workflowType`. `MainActivity` picks the **personal** or **student** navigation graph from it.
Both use the shared sign-in, device, API, theme, day navigation, offline strip and undo snackbar.
Only personal mode starts the `now/` package: the Now card, alarms, the refresh worker and the
notification permission. Student mode never asks for notification permission. Adding student mode
means adding `student/` screens and endpoints; nothing in personal mode changes.

**Build types.** `debug` installs as `org.dandeliondaily.seedclock.dev` ("Seedclock Dev", orange
icon) and can point at Local or Production. `release` installs as `org.dandeliondaily.seedclock`
and points only at production. Both can be installed side by side on the S23, so development never
disturbs the copy Nathan relies on.

---

## 5. Minimum Android version (G-12)

**minSdk 28 (Android 9), targetSdk 36, compileSdk 36.**

- The S23 Ultra runs Android 16 (One UI 8) and gets everything: the notification permission flow,
  `USE_EXACT_ALARM`, inline reply, Quick Settings tile, and the system's per-app language and
  theme support.
- Seedclock's signature features work on Android 9 as well: `DecoratedCustomViewStyle`,
  `RemoteInput` and exact alarms all exist there. Nothing in personal mode needs a newer minimum.
- **What it means for the Note8:** student mode installs on it directly, with no second build and
  no different approach. Compose and current AndroidX libraries support Android 9.
- **Cost:** a few `Build.VERSION.SDK_INT` checks (notification permission at 33+, exact-alarm
  permission at 31+, and the Quick Settings tile's `requestAddTileService` at 33+). These are
  small, and the code above covers each one.

A newer minimum would buy nothing Nathan would notice, and would cost the Note8.

---

## 6. Authentication and devices (G-3 to G-7)

**Building on `WebApiClient`.** The REST API already authenticates every request with an
`X-Api-Key` that maps to a `WebApiClient` row (user, workspace, enabled). Seedclock makes each
installed app a `WebApiClient` of a new type, `DEVICE`, so `ApiKeyAuthFilter` and every existing
resource keep working the same way.

**Schema (`src/db/v5.15.sql`)**, with `WebApiClient.java`, `WebApiClient.hbm.xml` and
`hibernate.cfg.xml` updated:

- `web_api_client`: add `client_type` (`AGENT` default, `DEVICE`), `device_status` (`UNVERIFIED`,
  `PENDING`, `APPROVED`, `REVOKED`), `device_name`, `api_key_hash` (SHA-256), `approved_date`,
  `approved_by`, `revoked_date`. Existing agent keys are untouched.
- New table `action_undo` (see section 7).

**Signing in (G-3).**

1. *Password:* `POST /api/v1/devices/sign-in {username, password, deviceName}`. The server checks
   the credentials the same way `LoginServlet` does (through a shared `LoginCredentialService`
   extracted from it), creates a `DEVICE` client with status `PENDING`, and returns a random
   256-bit device token once. Only its hash is stored.
2. *Email link:* `POST /api/v1/devices/sign-in-link {email, deviceName}` creates an `UNVERIFIED`
   client, returns the token, and emails a link through the existing magic-link mail code. The link
   opens a small web page, "Sign in Seedclock on *Nathan's S23*?", whose button marks the device
   verified, which makes it `PENDING`. No deep links or App Links are needed: the app is polling
   `GET /api/v1/devices/me` and moves on by itself.

**Approval (G-5).** A `PENDING` device gets `403 device_pending` from every endpoint except
`/devices/me`. Nathan is emailed "New device waiting: Seedclock on Nathan's S23", with a link to a
new desktop **Devices** page (under Settings). The page lists devices with name, user, status,
created and last-used dates, and has **Approve** and **Revoke** buttons. The app shows "Waiting for
approval on the desktop" and continues on its own once approved. For deliverable 2, the same page
lists the student's devices, because Nathan is the guardian.

**Staying signed in (G-4).** The device token doesn't expire. It's stored in DataStore, encrypted
with an Android Keystore key, and `android:allowBackup="false"` keeps it from being copied to
another phone. Nothing on the server rotates or times it out. This is the direct fix for the
daughter's complaint.

**Revoking (G-6).** Revoke sets `REVOKED`. The next request gets `401 device_revoked`. The app
clears the token, cancels the card and alarms, and shows "This phone's access was revoked." *Sign
out of this phone* in the app revokes its own token through `DELETE /api/v1/devices/me`.

**One account per device (G-7).** One token per install. Signing in as someone else means signing
out first.

**Own data only (G-8).** Each device client stores the user and the workspace that was active at
sign-in. Every new service takes the signed-in `WebUser` and workspace from `ApiRequestContext` and
checks ownership (section 7) before reading or changing an action.

---

## 7. Server-side changes

### 7.1 Shared services (G-13)

Today the logic sits in the servlets: `MobileBaseServlet` (`completeAction`, `cancelAction`,
`rescheduleAction`, `postponeToTomorrow`), `TodoServlet.fetchReadyActions`, `ProjectServlet`'s
grouping, and `ActionServlet`'s save, Schedule & Block and Complete & Next. These move into
services in a new package, **`org.dandeliondaily.personal.service`**. The servlets then call the
services instead, and only render HTML:

| Service | Responsibility | Moves from |
|---|---|---|
| `ActionAccessService` | `requireOwnAction(webUser, workspaceId, actionNextId)`: the action is in the workspace, and the user is the owner or assignee; otherwise "not found" | new (closes the ownership gap noted in `README.md` for both callers) |
| `PersonalDayService` | A day's actions (overdue + by time slot, missing slot = Afternoon, non-billable only), waiting actions, and a project's open actions in the four groups | `TodoServlet.fetchReadyActions`, `ProjectServlet` |
| `ActionQuickChangeService` | Complete, cancel, move (date + slot), postpone; unblocking through `ProjectActionBlockerManager`; linked-project siblings through `SharedActionSiblings`; writes a change log and an undo receipt | `MobileBaseServlet` |
| `ActionUndoService` | Applies an undo receipt if the actions haven't changed since | new |
| `ActionEditService` | Edit fields, with the project's priority copy, the standard action set for new actions, and `LinkedMeetingService.actionTypeError` validation | `ActionServlet` save |
| `ActionFlowService` | Block on a new action; complete and say what's next (through `ActionSentenceImportService`) | `ActionServlet` |
| existing `ActionNotesService` | Append notes | already shared |
| existing `ActionSentenceImportService` | Quick capture, including the `project:` prefix | already shared |
| `DeviceService`, `LoginCredentialService` | Device lifecycle; the password check | new; `LoginServlet` |

The DAOs these need go in `org.openimmunizationsoftware.pt.doa` (convention). Reading the
existing helpers as they are and calling them from one place is the main job. The behavior that
`/m/*` has today is the spec.

### 7.2 Undo receipts

`ActionQuickChangeService` returns a **receipt** for every change: the before-state of every row it
touched (status, date, slot, `blockedBy`, change date) for the action **and** for any actions it
unblocked or created. It's stored in `action_undo` (`undo_id`, `client_id`, `token`, `snapshot`
JSON, `created`, `expires` = 5 minutes) and returned to the caller as `undoToken`. `POST
/api/v1/undo/{token}` restores the snapshot if each row's change date still matches. Otherwise it
returns `409 changed_elsewhere`, and the app says "Couldn't undo: it was changed elsewhere." Expired
rows are deleted on the next write. A client can only redeem its own receipts.

### 7.3 New endpoints (`/api/v1`, Jersey, same filters)

| Method and path | Purpose |
|---|---|
| `POST /devices/sign-in`, `POST /devices/sign-in-link` | Sign in (public paths in `ApiKeyAuthFilter`) |
| `GET /devices/me`, `DELETE /devices/me` | Device status; sign out |
| `GET /me` | User name, `workflowType`, the user's "today", time zone, default capture project |
| `GET /personal/day?date=` | `{date, overdue[], slots{WAKE,MORNING,AFTERNOON,EVENING}, waitingCount}` |
| `GET /personal/waiting` | Open waiting actions with days waiting |
| `GET /personal/projects`, `GET /personal/projects/{id}` | Open personal projects with today's counts; one project's four groups |
| `GET /personal/projects/{id}/contacts` | Assignee choices |
| `GET /personal/actions/{id}` | Detail: notes, link, type, blocked-by, assignee |
| `POST /personal/actions/{id}/complete` and `/cancel` | Returns the updated action + `undoToken` |
| `POST /personal/actions/{id}/move {date, slot}` | Same |
| `POST /personal/actions/move {ids[], date, slot}` | *All to tomorrow*: one receipt for the batch |
| `PATCH /personal/actions/{id}` | Edit details |
| `POST /personal/actions/{id}/notes {text}` | Append to notes |
| `POST /personal/actions/{id}/block {description}` | Schedule & Block |
| `POST /personal/actions/{id}/complete-next {sentence}` | Complete & Next |
| `POST /personal/capture {sentence, projectId?, date?, slot?}` | Quick capture |
| `POST /undo/{token}` | Undo |

The `/personal/` prefix keeps these clearly separate from the existing agent endpoints (`/actions`,
`/projects`, `/sync`) and leaves room for `/student/` next. Every response uses the existing
`ApiErrorResponse` for errors. The existing rate limit (60 per minute per client) is plenty.

### 7.4 What else changes on the server

- **Devices page** (desktop servlet) and the email-link verification page.
- **Mobile servlets** switch to the services, one at a time, in the build steps that need them. The
  GET-based quick actions can stay as links. Moving them to POST is an easy follow-up but isn't
  needed for the app.
- **`pom.xml` version** bumped at each deployable server step, as usual.

---

## 8. Development setup and workflow (D-1 to D-5)

### 8.1 One-time setup (D-1)

Already installed: Android Studio, the SDK with `android-36.1`, emulators `Pixel_7` and
`Medium_Phone`, `adb`, Java 25, Maven. To add:

1. **`ANDROID_HOME`** = `%LOCALAPPDATA%\Android\Sdk` (user environment variable), and
   `%ANDROID_HOME%\platform-tools` plus `%ANDROID_HOME%\emulator` on `PATH`. An agent can do this
   with `setx`. Nathan opens a new terminal afterwards.
2. **A JDK for Gradle.** The Android Gradle Plugin may not support Java 25 yet. Point Gradle at
   Android Studio's bundled JDK 21 by adding one line to `%USERPROFILE%\.gradle\gradle.properties`:
   `org.gradle.java.home=C:\\Program Files\\Android\\Android Studio\\jbr`. Maven keeps using Java 25.
3. **Gradle itself:** nothing to install. The committed wrapper (`android\gradlew.bat`) downloads
   the right version on first use.
4. **Release signing key:** `keytool -genkeypair` (from the same JDK) creates
   `%USERPROFILE%\.android-keys\seedclock-release.jks`. The passwords go in the user
   `gradle.properties`, never in the repository. **Back it up** (for example, to
   `C:\brain\private`): updates must be signed with the same key.
5. **On the S23, once:** Settings → About phone → Software information → tap *Build number* 7 times
   → Developer options → *USB debugging* and *Wireless debugging* on. Set lock-screen notifications
   to *Details*. Put Seedclock in *Never sleeping apps* after the first install.

### 8.2 Emulator or phone (D-2)

| Use | When |
|---|---|
| **JVM unit tests** (no device) | The reminder engine, card building, ViewModels, API client. Run on every change; this is where most bugs are caught. |
| **Emulator `Pixel_7`** | UI work and Compose UI tests. It reaches the local server at `http://10.0.2.2:8089/dandelion/`. Agents can start it headless and test unattended. |
| **The S23 over USB** | Everything about notifications, the lock screen, alarms and Samsung battery behavior. The final check for every step. |
| **The S23 over wireless debugging** | Same as USB without a cable: pair once with `adb pair <ip>:<port> <code>`, then `adb connect`. |

**Reaching the local server from the phone:** `adb reverse tcp:8089 tcp:8089` makes the phone's
`localhost:8089` the dev machine's port 8089, over USB or Wi-Fi. That means no LAN IP, no Windows
firewall rule, and the same URL as on the desktop. The debug build allows plain HTTP only for
`localhost` and `10.0.2.2` (a debug-only `network_security_config.xml`). Release builds are HTTPS
only.

### 8.3 What an agent runs (D-5)

All from `android\`. `scripts\dev.ps1` wraps the common sequences.

```powershell
.\gradlew.bat :core:test testDebugUnitTest      # unit tests (JVM, no device)
.\gradlew.bat assembleDebug                     # build the debug APK
adb devices                                     # what's connected
adb reverse tcp:8089 tcp:8089                   # phone -> local Dandelion
.\gradlew.bat installDebug                      # install/update Seedclock Dev
adb shell am start -n org.dandeliondaily.seedclock.dev/org.dandeliondaily.seedclock.MainActivity
adb exec-out screencap -p > $env:TEMP\shot.png  # screenshot the agent can look at
adb logcat -s Seedclock:V                       # app logs
adb shell dumpsys alarm | Select-String seedclock    # check scheduled boundaries
emulator -avd Pixel_7 -no-window -no-snapshot-save  # (background) headless emulator
.\gradlew.bat connectedDebugAndroidTest         # Compose UI tests on the emulator
```

Nathan's hands are only needed to plug in or pair the phone, approve the USB-debugging prompt,
approve a new device on the Devices page, and look at the result.

### 8.4 Local and production servers (D-4)

The debug build has a server choice on the sign-in screen: **Local** (`http://localhost:8089/dandelion/`
on the phone, or `http://10.0.2.2:8089/dandelion/` on the emulator) or **Production**
(`https://dandelion-daily.org/`). Each install signs in separately and gets its own device token
from that server. The local database is a copy of production, so Nathan's account exists there,
and the dev device has to be approved on the *local* Devices page. The release build only knows
production.

### 8.5 Installing and updating on the S23 (D-3)

- **First install and every update:** with the phone connected (USB or wireless),
  `.\gradlew.bat installRelease`. Updates keep the sign-in and settings because the signing key
  is the same. The agent reports the installed `versionName` from
  `adb shell dumpsys package org.dandeliondaily.seedclock | Select-String versionName`.
- **Versioning:** `versionName` matches the app's own semantic version (`1.0.0`, …) and
  `versionCode` is derived from it. The app's Settings screen and the debug overlay show it.
- **Server first:** when a step needs new endpoints, the server WAR goes to production first
  (the usual `CLAUDE.md` flow, Nathan runs `sudo deploy-dandelion`), then the release app.
  `GET /me` returns the server's API level, and the app shows "Dandelion needs an update" instead
  of failing if the server is older than it expects.

### 8.6 Testing approach

- **`core` unit tests** (most of the value): `buildCard` and `planWakeups` for every profile,
  boundary, rule and snooze, around midnight, and across daylight-saving changes, with a fake clock.
  Each requirement P-21 to P-24 gets named test cases.
- **App unit tests:** ViewModels with a fake API; the Retrofit layer against MockWebServer with
  recorded JSON from the local server; Robolectric tests that the card's `RemoteViews` have the
  right rows and intents.
- **Compose UI tests on the emulator:** Today, the action sheet, capture, and undo, against
  MockWebServer.
- **Server:** JUnit tests for the new services next to the existing tests in `src/test/java`, and a
  `curl` smoke script for every new endpoint against `localhost:8089` (the local database is
  disposable).
- **Reminders on the real phone:** the debug build has **time travel**:
  `adb shell am broadcast -a org.dandeliondaily.seedclock.dev.DEBUG_NOW --es at "2026-11-12T11:59"`
  sets the app's clock and fires whatever is due, so a whole day of boundaries can be checked in a
  few minutes. A debug menu has "Fire next boundary now". `dumpsys alarm` confirms that the real
  alarms are scheduled.
- **Living with it:** after each step, Nathan uses the build for a day and keeps the `/m/*` pages as
  a fallback until step 6.

---

## 9. Build order

Every step ends with something Nathan can use or test on his S23.

| Step | What gets built | What Nathan can do at the end |
|---|---|---|
| **0. Spike** (≈1 day) | `android/` skeleton, wrapper, `dev.ps1`; a throwaway Now card with fake rows | Install it on the S23 and see the card on the lock screen and in the shade. Answers the open questions: can ✔ run from the lock screen without unlocking? Do 5 rows fit? Does switching channels alert once cleanly? Is the card reliable under Samsung's battery management? Decide on the fallback if needed. |
| **1. Server: devices and reading** | v5.15 schema, `DeviceService`, sign-in endpoints, Devices page, `ActionAccessService`, `PersonalDayService`, the read endpoints; `TodoServlet` and `ProjectServlet` switch to `PersonalDayService` | Approve and revoke test devices from the desktop; `curl` today's actions as JSON. The `/m/*` pages work as before. |
| **2. App: sign in and look** | Sign in (password and email link), approval wait, Today with periods and overdue, day navigation, Waiting tab, Projects, action detail (read-only), offline strip | Open the app on the phone, stay signed in, see today, other days, waiting items and projects. |
| **3. Act on actions** | `ActionQuickChangeService`, undo receipts, mutation endpoints (`MobileBaseServlet` switches over); in the app: complete, cancel, move chips, pick a date, undo, notes, open link | Run his day from the app: check off, cancel, move anywhere, undo. |
| **4. Capture and edit** | Capture endpoint and sheet (project context, chips, `project:` prefix), Just added, Edit details, Schedule & Block, Complete & Next, waiting Follow up (`ActionServlet` switches over) | Everything the `/m/*` pages do, plus waiting items. The app now replaces them on the phone. |
| **5. The Now card** | The `core` card builder, renderer, receiver actions (✔, undo, → tomorrow, all to tomorrow), inline capture with project chips, pin to card, boundary rollover (silent), refresh worker, boot handling, Quick Settings tile, shortcuts | The day lives on the lock screen; he checks things off without opening the app. No alerts yet. |
| **6. Reminders** | Profiles, rules, start/end alerts, leftovers, waiting check, snooze, Quiet today, day done, settings screens, time travel | The full Seedclock: helpful prompts at the right moments, configured by weekday and by kind of action. |
| **7. Release** | Release signing, production-only build, version check against the server, polish from a week of use | Install the release build for good, and retire the `/m/*` pages from his phone. |

Steps 1 to 4 are a normal, useful native to-do app on their own. If the Now card disappointed in
step 0, the plan would still deliver everything the requirements ask for, with ordinary
notifications instead (section 11).

**Deliverable 2 (student mode) after that:**

1. Server: extract `StudentSchoolService`, `StudentStoreService`, `StudentBankService` from the
   three student servlets (same pattern as section 7); add `/api/v1/student/...` endpoints
   (day, complete with undo receipt, offers, buy, return, transfer); switch the servlets over.
2. App: the `student/` navigation graph (School · Store · Bank), reusing sign-in, devices, day
   navigation, undo and the offline strip. No Now card and no notification permission.
3. Install on the Note8 (Android 9, within minSdk 28). Nathan approves it on the Devices page.

---

## 10. Requirements coverage

| ID | How Seedclock meets it |
|---|---|
| **G-1** One app | One APK. `GET /me`'s `workflowType` chooses the personal or student navigation graph (§4). |
| **G-2** Native | Kotlin + Jetpack Compose; no WebView screens. |
| **G-3** Dandelion credentials | Password or email link, through device endpoints that reuse the login and magic-link code; no API keys for the user (§6). |
| **G-4** Stay signed in | A non-expiring device token, encrypted with a Keystore key, never rotated (§6). |
| **G-5** Approve new devices | New devices are `PENDING` until approved on the desktop Devices page; Nathan gets an email (§6). |
| **G-6** Revoke devices | Revoke on the Devices page → `401 device_revoked` → the app clears itself; also "Sign out of this phone" (§6). |
| **G-7** One account per device | One token per install; sign out to switch. |
| **G-8** Own data only | `ActionAccessService` checks workspace and owner/assignee on every action; the user and workspace come from the device token (§6, §7). |
| **G-9** Online only | No offline queue; an offline strip in the app; card actions report "Not saved" (§2.2, §3.4). |
| **G-10** Undo, not confirmations | No "are you sure?" anywhere; snackbar and card Undo backed by server undo receipts (§7.2). |
| **G-11** Family devices only | Sideloaded with adb; no store listing (§8.5). |
| **G-12** Target devices | Tuned and tested on the S23; minSdk 28 keeps the Note8 supported (§5). |
| **G-13** Same behavior as the web | New services used by both the API and the `/m/*` servlets (§7.1). |
| **G-14** MVP on one phone | Only the S23 gets a release build in deliverable 1. |
| **P-1** Today first | The app opens on Today with Overdue; the Now card is today (§2.1, §2.2). |
| **P-2** Time-of-day view | Wake/Morning/Afternoon/Evening sections, opening on the current one; the card shows the current one (§2.2). |
| **P-3** Look ahead | ‹ › day by day, with the web's titles (§2.2). |
| **P-4** Waiting items | Waiting tab with days waiting, Follow up / Got it / Move; the Waiting check puts them on the card (§2.4, §3.1). |
| **P-5** Projects | Project list and a project's four groups (§2.5). |
| **P-6** Complete | Circle in the app, ✔ on the card; unblocking as today (§7.1). |
| **P-7** Cancel | ✖ in the action sheet. |
| **P-8** Move quickly | Move chips (later today, tomorrow, weekdays, next week, +2 weeks); → and All to tomorrow on the card (§2.3). |
| **P-9** Move to any date | Calendar with time-of-day chips and +1 week / +1 month jumps (§2.3). |
| **P-10** Undo | 8-second snackbar, card Undo, 5-minute undo from the action sheet's history line (§2.2, §7.2). |
| **P-11** Read notes | In the action sheet. |
| **P-12** Add to notes | *Add to notes* through `ActionNotesService.append`. |
| **P-13** Open link | *Open link* in the action sheet. |
| **P-14** Edit an action | *Edit details*: project, description, type, date, time of day, link, assignee. |
| **P-15** Block on a new action | *Block on new action* (Schedule & Block); also used by waiting Follow up. |
| **P-16** Complete and say what's next | *Done & next…* with verb chips and the sentence parser. |
| **P-17** Quick capture | One sentence and Save, from five places including inline on the card (§2.6). |
| **P-18** Project from context | From a project screen, or a project pinned to the card. |
| **P-19** Pick a project | Recent-project chips, searchable list, `project:` prefix; one-tap reassignment after capturing from the card. |
| **P-20** Add details later | "Saved · Add details" snackbar, *Just added* on Today, *Edit* on the card. |
| **P-21** End-of-period nudge | The card turns into the leftovers state and alerts once, if Nudge-class items remain (§3.1). |
| **P-22** Start-of-period prompt | The card rolls over with an editable message, alert or silent per period (§3.1). |
| **P-23** Configurable on the phone | Weekday profiles, per-period alert settings, and project/type rules (Nudge, Quiet, Hidden) (§3.2). |
| **P-24** Built on Dandelion's time blocks | Dandelion's four time slots plus clock times set on the phone; nothing changes on the desktop (§3.2). |
| **D-1** Toolchain | `ANDROID_HOME`, the Gradle JDK line, the wrapper, the signing key (§8.1). |
| **D-2** Build and test loop | JVM tests, emulator, S23 over USB or Wi-Fi, and when to use each (§8.2, §8.6). |
| **D-3** Install and update | `installRelease` over adb, with the same key so data is kept (§8.5). |
| **D-4** Local and production | Server choice in debug builds; `adb reverse` for the phone, `10.0.2.2` for the emulator (§8.4). |
| **D-5** Agent-friendly | Everything is a Gradle, adb or PowerShell command, including screenshots and time travel (§8.3). |
| **S-1 to S-16** | Deliverable 2 adds a `student/` graph (School · Store · Bank) and `/api/v1/student/*` endpoints over services extracted from the student servlets. It reuses sign-in, devices (G-4 fixes her complaint), day navigation and undo receipts (S-6), and runs on the Note8 thanks to minSdk 28. For **undo of purchases**, I suggest: buying is immediate, with a 10-second Undo that refunds it, and the guardian email is sent 1 minute after the purchase unless it was undone, so an undone purchase never reaches Nathan's inbox. |

**Requirements I meet only partly or would change:** none that I'd change. Two notes:

- **P-21/P-22 timing** depends on Android delivering the alarm. Exact alarms plus "Never sleeping
  apps" make this reliable in practice, but the step-0 spike and step 6 should confirm it on the
  S23 itself.
- **P-1 on the card** shows the current part of the day plus leftovers, not every overdue item. The
  full overdue list is in the app, one tap away (*Open*). Five rows is a deliberate limit, not a
  gap.

---

## 11. Risks and open questions

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| **Samsung asks for an unlock before a lock-screen ✔ runs**, or renders custom notification rows differently | Medium | Step 0 tests this first, before anything else is built. If an unlock is required, ticking still takes one fingerprint touch, with no app to open. If custom rows render badly, the fallback is a standard `InboxStyle` card with three buttons: *✔ first item*, *All to tomorrow*, *Open*. |
| **Samsung's battery management delays alarms or the refresh worker** | Medium | Exact alarms, "Never sleeping apps", `dumpsys alarm` checks, and boot/update rescheduling. The card says "as of …" when it's stale, and ↻ refreshes it. |
| **The card becomes wallpaper**: always there, so eventually unseen | Medium | Few alerts by default, the leftovers state looks different from the normal card, and Day done clears it in the evening. Nathan can tune it after a week of real use. |
| **The card is up to ~15 minutes behind desktop changes** | Certain, minor | Refresh on every boundary, every change, and every app open; ↻ on the card; a tick on something already done is handled gracefully. Push could be added later (below). |
| **Moving servlet logic into services changes `/m/*` behavior** | Low | One servlet at a time, with the current behavior as the spec, tested against the local copy of production data. `/m/*` stays the fallback until step 7. |
| **Losing the release signing key** | Low | Back it up in step 0. If it's lost anyway, uninstall, reinstall and approve the phone again (about 5 minutes; settings are lost). |
| **AGP and Java versions** | Low | Gradle runs on Android Studio's JDK 21 (§8.1); Maven is unaffected. |
| **Time zones** (travelling) | Low | Dates come from the server's "today" for the user (`GET /me`); clock times come from the phone. Using the phone's time zone when travelling is an open question. |

### Open questions for Nathan

1. **Default capture project.** Which project gets a card capture when nothing is pinned and there's
   no `project:` prefix? I suggest a personal "Inbox" project, or his most-used one.
2. **Default clock times** for the three profiles in §3.2. Could Dandelion's day availability seed
   them?
3. **Days waiting:** measured from the waiting action's date, or from its last change? I suggest
   the date.
4. **Push later?** If 15-minute freshness turns out to be annoying, Firebase Cloud Messaging could
   push "today changed" to the card. It needs a Firebase project and server credentials, and it's
   not in this plan.
5. **Settings on the server?** Reminder settings live only on the phone (P-24), so a reinstall loses
   them. An export/import of the settings file in the app is cheap; storing them on the server
   isn't needed for the MVP.
6. **Purchase undo** for student mode, as suggested in §10.

---

## 12. Why this one

All three pitches agree on the shape of the day: four parts, a prompt at the start, something to
close each part at the end, waiting items brought forward, and fast capture with the project taken
from context. That's what the requirements ask for, so it isn't a difference. The real difference
is **where the day lives**.

- **Sundial (`claude-1`)** stakes everything on **the Sweep**: a full-screen, swipe-per-item
  run through the leftovers when a pass ends, fed by reminders and a home-screen widget. Its
  strength is a satisfying ritual for clearing many items at once. Seedclock doesn't have a ritual.
  Leftovers simply stay on the card where Nathan already looks, with one tap per item, *All to
  tomorrow* for a batch, and the app's action sheet for anything finer. If Nathan prefers a
  deliberate routine four times a day, Sundial is the better fit. If he would rather the list
  come to him and never need a routine, Seedclock is.
- **Relay (`openai`)** builds a focused **Now screen** inside the app, with a deliberate handoff at
  each boundary and a "Waiting on" pulse. Its strength is a calm, well-organized app home screen.
  Seedclock puts "now" *outside* the app, in the one place Android shows on the lock screen, in the
  shade and over any app. For the most common action, checking off the current thing, that's zero
  navigation instead of a few steps.

What only Seedclock has: **one notification that is the to-do list**, with ✔ on every row, inline
capture, a pinnable project and leftovers on the card, and **reminders that are the same card
changing state** rather than new alerts. Under that, it is also carefully built for one developer:
the reminder logic is a pure, JVM-tested function; undo is exact because the server records what
changed; the risky part (Samsung's lock screen) is tested on day one with a fallback ready; and
steps 1 to 4 give Nathan a complete native app even before the card exists.

The judges may combine entries. Seedclock's Now card would work just as well on top of another
entry's app screens. As a base, though, it's the one built around Nathan not having to open the
app at all.
