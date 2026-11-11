# Sundial: proposal

Contestant `claude-1`. This is the full proposal behind `pitch.md`. It follows `Proposal Brief.md`,
sections 1–11, plus section 12, "Why this one".

**What changed since the pitch.** The pitch bet on the **Sweep**, a quick triage of leftovers at
the end of each time of day. Once the pitches were open, it turned out all three entries reached
for the same thing on their own: Seedclock has a Sweep, and Relay has an end-of-block "handoff".
When three blind entries converge like that, it's a sign the requirements (P-21) call for it. But
it no longer sets Sundial apart. So the Sweep stays as one feature, and Sundial now stakes its
design on what the pitch called "moving is the fastest gesture, because moving is the common
case". That idea is now **the Dial**: a single thumb gesture that picks both a day and a time of
day, and shows how full each day already is. The Dial is the one way Sundial asks "when", whether
he's moving, capturing, sweeping or scheduling a follow-up. The home-screen widget from the pitch
stays as Sundial's glanceable surface. Everything else in the pitch still holds.

---

## 1. Summary

**The idea.** Dandelion gives every action a day and a time of day (Wake, Morning, Afternoon,
Evening). On the phone, the thing Nathan does most after checking an item off is to change its
"when": later today, tomorrow, next Tuesday, three weeks out. Moves of more than seven days are
common (P-9), and today they take a pop-up, a date field and a submit. Sundial makes "when" a
single gesture:

> **Press and hold any action. The Dial opens under the thumb. Slide around the ring to pick the
> day (a haptic tick for each day, a stronger one for each week). Slide inward to pick the time
> of day. Lift to move it. Undo is in the snackbar.**

The ring shows a small density mark for each day, so he can see that next Tuesday is already
full and Wednesday is clear before he lets go. Every "when" in the app uses the same Dial:
moving an item, the Sweep, quick capture, Complete & Next and Schedule & Block. It's one shared
component, built once.

Around the Dial:

- **Today, shaped by passes.** The today view opens on the current pass (time of day), with the
  others folded into summary rows above and below it. Tap ✓ or swipe right to complete. Swipe left
  for a tray of one-tap moves. Press and hold for the Dial.
- **The Sweep at the end of a pass.** One grouped notification ("Morning closes · 3 left") opens a
  card-by-card review of the leftovers: done, next pass, tomorrow, or hold for the Dial.
- **A home-screen widget** that shows the current pass with checkboxes, plus a capture button.
- **One-sentence capture** from the app, a project, the widget, a Quick Settings tile, a launcher
  shortcut, or the Android share sheet. The sentence is parsed by Dandelion's existing
  `ActionSentenceImportService`, and a live preview shows what it understood before saving.
- **A Waiting tab**, plus up to three stale waiting items added to the Evening Sweep.

**Key choices.**

| Choice | Decision | Main alternative rejected |
|---|---|---|
| Language and UI | Kotlin, Jetpack Compose, Material 3 | Java with Views: more code, and agents write better Compose today |
| Where the code lives | `android/` in this repository | A separate repo: API and app changes couldn't land in one commit |
| Minimum Android | **9 (API 28)**, target 36 | API 31+: would cut off the Note8 for little gain (section 5) |
| Auth | A per-device key on the existing `WebApiClient`, issued by a new sign-in endpoint, **pending until Nathan approves it** | OAuth or a refresh-token server: too much machinery for one family |
| Undo | Server-side undo tokens holding a snapshot of what changed | Delaying the request on the phone: breaks the widget and notifications, and isn't really "immediate" |
| Reminders | Exact alarms at pass boundaries; fresh data fetched when each one fires | A server push (FCM): new infrastructure, and the phone owns reminder timing anyway |
| Shared logic | New services in `org.dandeliondaily.mobile.service`, used by both `/m/*` servlets and the new `/api/v1/app/*` endpoints | Endpoints that call the servlets' helper methods: the logic stays stuck in servlets |

---

## 2. Interaction design for personal mode

Three tabs at the bottom (**Today · Waiting · Projects**), one capture button (**+**), and a
settings gear on the Today header. Every screen uses the same row grammar:

| Gesture on an action row | Result |
|---|---|
| Tap the ○ circle, or swipe right | Complete (P-6). The row fades out, with a snackbar: "Done · Undo" |
| Swipe left | Opens the **move tray** under the row (P-8) |
| Press and hold | Opens **the Dial** under the thumb (P-9) |
| Tap the text | Opens the action sheet (detail, P-11 to P-16) |

Swiping right always means *done*, so nothing is lost by accident; cancel takes two steps (in the
move tray or the action sheet), and it can still be undone.

### 2.1 Today (P-1, P-2)

```
┌──────────────────────────────────┐
│ ‹  Thursday, Nov 12  ›      ⚙    │
│ ◔ Morning · 4 left · until 12:00 │  ← the pass ring: four arcs, filled as passes close
├──────────────────────────────────┤
│ ▸ Overdue · 2                    │  folded; tap to open
│ ▸ Wake · clean ✓                 │  past pass, folded
│ ─ MORNING ────────────────────── │  current pass, open
│ ○ 🏠 Call about furnace filter    │
│ ○ ⛪ Email agenda to bishopric    │
│ ○ 🌱 Order seed catalogue         │
│ ○ 🏠 Pay water bill        ↗ link │
│ ▸ Afternoon · 3                  │  later passes folded, with counts
│ ▸ Evening · 2                    │
│                                  │
│ [Just added: "Dentist Tue…"  ✎ ] │  P-20 chip, shown for 10 minutes
│                              (+) │
├──────────────────────────────────┤
│   Today      Waiting    Projects │
└──────────────────────────────────┘
```

- **The current pass is open and everything else is folded.** When the app opens, it scrolls to
  the current pass, so the items he needs are under his thumb. Tapping a folded row opens it in
  place; several can be open at once.
- **Overdue** is a folded row at the top that shows its count. Overdue items are also the first
  cards of the day's first Sweep, so they can't sit unnoticed.
- **Items with no time of day** count as Afternoon, as on the web today.
- **The pass ring** in the header is a small four-arc sundial. A finished pass fills in. A pass
  closed with leftovers moved on shows a notch. It's the at-a-glance "how is today going" mark,
  and the same ring appears on the widget.
- **Order within a pass** is the same as the web: priority, then last changed, then description
  (it comes from the shared service).
- **A completed item** stays for the undo window (6 seconds), struck through, then slides away.

### 2.2 Looking ahead (P-3)

The `‹ ›` arrows, or a horizontal swipe on the header, step one day at a time. The title uses
Dandelion's existing words: "Tomorrow", "Thursday", "Next Thursday", or a date. Other days show
only that day's actions, grouped by pass, with no overdue section, as on the web.

What makes look-ahead useful (finding things to do *now*): on any other day, the move tray's
first chip is **Now** (today, current pass). Seeing "Order seed catalogue" on Saturday, Nathan
swipes left and taps **Now**, and it's on today's list. Tapping **Today** in the header jumps
back.

### 2.3 Quick moves: the move tray (P-8)

Swiping left opens a strip of chips under the row:

```
│ ○ 🏠 Call about furnace filter                          │
│ [Next pass] [Tonight] [Tomorrow] [Sat] [Sun] [Mon] [◎] [✕ Cancel] │
```

- **Next pass** moves it to the next time of day today (from Evening, it goes to Wake tomorrow).
- **Tonight** moves it to Evening today.
- **Tomorrow** keeps the same time of day.
- The next three weekday chips move it to those days at the same time of day.
- **◎** opens the Dial, and **✕ Cancel** cancels the action (P-7).

The chip set is fixed so his thumb learns it. Every chip acts immediately, followed by the
snackbar "Moved to Tomorrow · Undo".

### 2.4 The Dial (P-9): Sundial's signature

```
                 today
              ·  ▲  ·
          ·   │  │  │   ·        outer ring: days, clockwise
       ·  ▪▪  ▪  ▪▪▪  ▪   ·      ▪ = how many actions already on that day
     ·  ▪                   ·
    · ▪      ╭────────╮       ·
   ·        ╱  Wake    ╲       ·    inner disc: four wedges
   ·  ▪    │Eve  ●  Morn│   ▪   ·   (the thumb's current position: ●)
   ·        ╲Afternoon ╱       ·
    ·        ╰────────╯       ·
     ·  ▪                   ·
       ·   ▪▪       ▪▪    ·
          ·    · · ·   ·
   ┌───────────────────────────────┐
   │  Tue Nov 24 · Evening · 12 days│  ← live readout above the thumb
   └───────────────────────────────┘
   [Tomorrow] [+1 wk] [+2 wk] [+1 mo] [📅]
```

**How it works.**

1. Press and hold an action. The Dial opens centered on the thumb, clamped so the whole ring
   stays on screen. On an S23 Ultra the ring is about 300 dp across.
2. **Day.** Move the thumb around the ring. Today is at twelve o'clock, and each notch clockwise is
   one more day: **one full turn is 28 days**. There is a light haptic tick for each day and a
   stronger one at each week boundary. Keep going round to continue into the second and third
   month: the readout and the notch labels move on, and a small "+1 turn" badge shows which lap
   he's on. Going counter-clockwise past today stops at today. Moving to the past isn't offered.
3. **Time of day.** Slide inward. The inner disc has four wedges (Wake, Morning, Afternoon,
   Evening). If he lifts from the outer ring, the action keeps its current time of day.
4. **Lift** to commit. The snackbar shows "Moved to Tue Nov 24 · Evening · Undo".
5. **Back out** by sliding off the Dial past its edge, or by returning the thumb to the center.
   Nothing changes.

**Density marks.** Each notch carries a small mark for the number of actions already on that
day (from a light endpoint, section 7). This answers the question behind most long moves: "when do
I actually have room for this?"

**Tap mode.** The Dial also opens from the ◎ chip, the action sheet, capture, and so on, as a
bottom sheet. There, he can tap a notch, then a wedge, then **Move**. A row of jump chips
(**Tomorrow, +1 wk, +2 wk, +1 mo**) sits under it, and **📅** opens the standard Material date
picker for anything far out. So the Dial is never the *only* way to reach a date (section 11
covers the risk that the gesture doesn't win Nathan over).

**Accessibility and precision.** Notches widen near the thumb. The readout always shows the exact
date in words, so he never has to read the ring itself. TalkBack gets the tap-mode sheet.

### 2.5 Undo (P-10, G-10)

- **Every change is immediate** and returns an undo token (section 7). The snackbar shows the most
  recent change for **6 seconds**. Tapping **Undo** reverses it on the server, including any actions
  the completion had unblocked.
- **No confirmations anywhere**, including cancel.
- **In the Sweep**, a **↶** button steps back one card at a time, through the whole Sweep.
- **From a notification**, completing an item turns the notification into "Done: Call about furnace
  filter · Undo" for 10 seconds.
- If the action has changed somewhere else since (on the desktop, say), undo refuses: "Changed
  elsewhere, can't undo". It never overwrites newer work.

### 2.6 The action sheet (P-11 to P-16)

Tapping a row slides up a sheet. Pulled up, it fills the screen.

```
┌──────────────────────────────────┐
│ ⛪ Ward Council            ⋯ Edit │
│ Email agenda to bishopric        │
│ [will] [Today · Morning ◎]        │  ← tap the time chip for the Dial
│ [↗ Open link]                     │
│ ── Notes ─────────────────────── │
│ 11/02 Ask about the youth item.  │
│ 11/09 Added budget line.         │
│ ┌──────────────────────────────┐ │
│ │ Add a note…              [➤] │ │  P-12 append only
│ └──────────────────────────────┘ │
│ [✓ Complete] [✓→ Complete & next] │
│ [⛓ Block on…]  [✕ Cancel]         │
└──────────────────────────────────┘
```

- **Notes (P-11, P-12).** Notes are read-only, newest at the bottom. Appended text goes through
  `ActionNotesService.append`, exactly as on the web.
- **Open link (P-13)** opens the browser, or another app if it handles the link.
- **Edit (P-14)** opens a full-screen form: project (searchable list, recent ones first),
  description, action type (the eleven types as chips), when (the Dial chip), link, and assignee
  (the project's contacts). It is the same form for new and existing actions, and saves through
  the same service as the web form, including its meeting-type validation.
- **Block on… (P-15)** asks "What has to happen first?" with a text field and a when chip
  (default: today). It creates the blocking action and parks this one, as "Schedule & Block" does
  today. When the blocker is completed, the original comes back.
- **Complete & next (P-16)** shows the web's verb chips (*I will, I might, I am waiting, I will
  meet, …*) above a sentence box. As he types, a preview line shows what Dandelion parsed:
  `→ I will go to the store · Tomorrow · Afternoon`. The **◎** chip lets him override the parsed
  date with the Dial. **Done** completes this action and creates the next one in a single request.

### 2.7 Quick capture (P-17 to P-20)

```
┌──────────────────────────────────┐
│ ┌──────────────────────────────┐ │
│ │ dentist call tuesday evening │ │
│ └──────────────────────────────┘ │
│ → "Dentist call" · Tue · Evening  │  live parse preview
│ Project: [⛪ Ward Council ✓] [🏠 Home] [🌱 Garden] [More…] │
│ When:    [Now] [Tonight] [Tomorrow] [◎]                │
│                          [ Add ]  │
└──────────────────────────────────┘
```

- **What and when (P-17).** He types (or dictates with the keyboard mic) one sentence. The preview
  shows the parsed description and date, using the server's `buildActionFromSentence...` methods
  without saving. If the sentence names no date, *when* defaults to **today, current pass**, so
  the minimum capture is just the words plus **Add**. The *when* chips and the Dial override
  whatever was parsed.
- **Project from context (P-18).** Capture opened from a project screen, or from an action sheet,
  has that project chosen and highlighted, so there's nothing to pick.
- **Pick a project (P-19).** Otherwise the three most recently used projects appear as chips, and
  **More…** opens a searchable list. A sentence that starts with a project token, which the
  sentence service already recognizes (`startsWithProjectToken`), chooses the project too.
- **Entry points:** the **+** button, the **+** on each project screen, the widget's **+**, a
  **Quick Settings tile**, a launcher shortcut (long-press the icon), and the **share sheet**.
  Sharing a web page from Chrome opens capture with the page title as the text and the URL as the
  link.
- **Details later (P-20).** After saving, the snackbar reads "Added · Details · Undo". For
  10 minutes afterwards, a **Just added** chip at the bottom of Today opens the item's Edit form.
  It works from any screen, so "a minute later" doesn't require finding the item.

### 2.8 Waiting (P-4)

The **Waiting** tab lists his open *waiting* actions on personal projects, **oldest first**, with
their age in big type ("9 days"). Each row has the standard gestures plus two waiting-specific
buttons:

- **Follow up** opens capture pre-filled with "I will follow up about <description>" on the same
  project, dated tomorrow morning. The waiting item stays as it is.
- **Got it** completes the waiting item (it arrived).

To make sure they're seen, the **Evening Sweep adds up to three waiting items** that are seven days
old or more (configurable), oldest first, each item at most once a week. That keeps them visible
without turning the evening into a backlog review. The Waiting tab's badge shows how many are
stale.

### 2.9 Projects (P-5)

**Projects** lists his open personal projects alphabetically, each with its icon and a "today"
count (as on the web), plus a search field. A project opens on four folded groups: **Overdue, Due
today, Due later, Unscheduled**. The first two start open. Rows use the standard gestures, and the
screen's **+** captures straight into the project (P-18).

### 2.10 The Sweep

When a pass closes with items still open, a notification offers a Sweep. Tapping it, or the
**Sweep** button on Today's current-pass header, opens a card stack:

```
┌──────────────────────────────────┐
│ Sweep · Morning        2 of 3  ↶ │
│ ┌──────────────────────────────┐ │
│ │ 🏠 Home                       │ │
│ │ Call about furnace filter    │ │
│ │ will · since Mon             │ │
│ └──────────────────────────────┘ │
│  ← Tomorrow   ↑ Next pass   Done → │
│  hold: ◎ Dial      tap: open      │
└──────────────────────────────────┘
```

A swipe right completes the card, left moves it to tomorrow, and up moves it to the next pass.
Press and hold to open the Dial, or tap to open the action sheet. The Sweep ends on "Morning
clean ✓" and returns to Today. Overdue items lead the day's first Sweep, and stale waiting items
end the Evening Sweep (2.8).

### 2.11 The widget

A resizable home-screen widget (default 4×3) shows the current pass:

```
┌────────────────────────────────┐
│ ◔ Morning · 3 left · to 12:00 + │
│ ☐ Call about furnace filter     │
│ ☐ Email agenda to bishopric     │
│ ☐ Order seed catalogue          │
│ Afternoon 3 · Evening 2          │
└────────────────────────────────┘
```

Checking a box completes the item. The row stays struck through for the undo window, and the
header shows **Undo**. Tapping text opens the action sheet in the app. **+** opens capture. When
the pass is clear, the widget shows "Morning clean ✓", then the next pass's list. If Dandelion
can't be reached, it shows "Offline · updated 9:12" (G-9).

### 2.12 Being offline (G-9)

The app is online-only. When a request fails, a red bar across the top says "Can't reach Dandelion
· Retry", and the data shown is marked with its age. Changes are never queued. A failed change
shows "Not saved: no connection" and the row returns to where it was.

---

## 3. Reminders

### 3.1 Behavior

**The day has passes.** The app keeps a **pass schedule** on the phone: four start times and a
day-end time for each weekday. Dandelion has the four time-of-day blocks but no clock times, which
is by design (README decision, 2026-11-11). Defaults:

| Pass | Weekdays | Saturday | Sunday |
|---|---|---|---|
| Wake | 6:30 | 7:30 | 7:00 |
| Morning | 8:30 | 9:00 | 9:00 |
| Afternoon | 12:00 | 12:00 | 13:00 |
| Evening | 17:30 | 17:00 | 17:00 |
| Day ends | 21:30 | 22:00 | 21:30 |

**Two kinds of reminder**, both per pass, and both off when there's nothing to say:

| Kind | Fires | Says | Actions in the notification |
|---|---|---|---|
| **Pass start** (P-22) | At the pass's start time | "Evening · 3 things: Take out recycling, Call Mom, …" (up to 5 lines) | **Open** · **Snooze 30m** · **Quiet today** |
| **Sweep** (P-21) | 15 minutes before the pass ends (configurable) | "Morning closes · 3 left" | **Sweep** · **All to next pass** · **Snooze 30m** |

If exactly one item is left, the Sweep notification acts on it directly: **Done · Tomorrow ·
Open**.

**Grouped, never per action.** A pass produces at most one start reminder and one Sweep reminder.
A new Sundial reminder replaces the previous one, so they never stack in the notification shade.
Nothing fires for an empty pass.

**Classes of actions: "watches" (P-23).** A **watch** is a named rule that says which actions a
reminder cares about:

- **Which actions:** any combination of projects and action types. "Everything" is allowed.
- **Which passes and days:** a 4×7 grid of checkboxes, with separate columns for start and Sweep.
- **Excluding** is allowed too, e.g. "everything except *might* and *would like to*".

The default watch is "**Everything except might / would like to**", for every pass, every day. Why
those two: they're optional by definition, and listing them in a Sweep reads as nagging. They still
appear on Today. Examples Nathan might add: "**Church** (Ward Council and Primary projects), Sunday
Morning start only", or "**Home chores**, weekday Evening Sweep". A pass's reminder counts the
actions matched by *any* watch that's on for that pass and day.

**Snoozing and silencing.**

- **Snooze 30m** re-fires the same reminder with fresh data (one tap; long-press for 10m or 1h).
- **Quiet today** silences every Sundial reminder until tomorrow's Wake.
- **Pause** in Settings silences for 1–14 days (vacations).
- Android's Do Not Disturb and per-channel settings apply as usual. Start and Sweep reminders are
  separate **notification channels**, so he can, for example, make starts silent and Sweeps sound.

**Fine-tuning (P-24).** Settings show the pass schedule as a week grid. Tapping a time adjusts it,
and "Copy Monday to weekdays" fills the rest. None of this is sent to Dandelion.

### 3.2 How it's built

- **Exact alarms.** When the schedule changes and every day at Day end, `ReminderScheduler`
  computes the next 48 hours of reminder times and registers each with
  `AlarmManager.setExactAndAllowWhileIdle`.
  - On Android 13+ (the S23), the app declares **`USE_EXACT_ALARM`**, which is granted at install.
    Google Play restricts that permission to alarm and calendar apps, but Sundial is installed
    outside the store (G-11), so it doesn't apply.
  - On Android 12 and below, the app declares `SCHEDULE_EXACT_ALARM`.
  - If exact alarms are ever unavailable, it falls back to `setWindow` with a 10-minute window.
  - If Sundial is ever published, this has to change (section 11).
- **Fresh at fire time.** When an alarm fires, a `BroadcastReceiver` calls `goAsync()` and makes a
  request for the day, with a 10-second timeout. It filters by the watches and posts the
  notification only if something matched. If Dandelion can't be reached, it uses the widget's
  cached data if that's less than 2 hours old, marked "as of 11:40"; otherwise it posts nothing.
- **Notification actions** (Done, Tomorrow, All to next pass) are `PendingIntent`s to a receiver
  that calls the API, then rewrites the notification with Undo for 10 seconds (`setTimeoutAfter`).
- **Survives the phone.** `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED` and app updates
  (`MY_PACKAGE_REPLACED`) reschedule the alarms.
- **Samsung battery management.** One UI can put apps to sleep, which delays alarms and widget
  updates. At setup, Sundial checks `isIgnoringBatteryOptimizations` and, if needed, takes Nathan to
  the setting to add Sundial to **Never sleeping apps**. This is the one step that needs his hands.
- **Notification permission** (Android 13+) is requested once, after sign-in, with a one-line
  explanation.

---

## 4. App architecture

| Area | Choice |
|---|---|
| Language and build | Kotlin 2.x, Gradle Kotlin DSL with a version catalog, **Gradle wrapper committed** |
| UI | Jetpack Compose, Material 3, Navigation Compose |
| Networking | Retrofit and OkHttp, kotlinx.serialization (JSON) |
| Concurrency | Coroutines and Flow; a ViewModel per screen |
| Widget | Jetpack **Glance** |
| Background | `AlarmManager` (reminders) and WorkManager (widget refresh every 30 minutes) |
| Settings | DataStore (Preferences): pass schedule, watches, last projects |
| Secrets | The device key, encrypted with an Android Keystore AES key and stored in DataStore. App backup is disabled. |
| Dependency injection | **Manual** (a small `AppContainer`), so agents don't have to deal with Hilt's code generation. It's easy to switch later. |
| Tests | JUnit 5 and Turbine (JVM); Compose UI tests (emulator); MockWebServer for the API layer |

**Where the code lives: `android/` in this repository.** An API change and the app change that
uses it land in one commit. Agents working here see the servlets, services and DTOs they call.
Maven ignores `android/`, and Gradle ignores the rest.

```
android/
  settings.gradle.kts, gradle/libs.versions.toml, gradlew(.bat)
  app/src/main/java/org/dandeliondaily/sundial/
    core/      api (Retrofit, DTOs), auth, undo, time (passes, Dandelion dates), ui (theme, rows, snackbar)
    dial/      the Dial component and its math (pure Kotlin, unit-tested)
    personal/  today, lookahead, waiting, projects, action, capture, sweep
    reminders/ scheduler, receivers, watches, settings UI
    widget/    Glance widget, Quick Settings tile
    student/   (deliverable 2) school, store, bank
  app/src/dev/   network security config (cleartext to localhost), dev icon
  tools/         env.ps1, phone.ps1 (connect, install, screenshot, logs)
```

**Two product flavors.** `dev` (application ID `…sundial.dev`, "Sundial Dev", points at the local
server) and `prod` (points at `https://dandelion-daily.org`). Both can be installed side by side on
the phone, so testing never disturbs real use.

**One app, two modes (G-1).** After sign-in, `GET /api/v1/app/session` returns `mode: PERSONAL |
STUDENT`, based on `workflowType`. The root composable picks the navigation graph:

```
SessionGate ─┬─ PERSONAL → Today · Waiting · Projects (+ reminders, widget, tile)
             └─ STUDENT  → School · Store · Bank     (reminders, widget, tile disabled)
```

Sign-in, device approval, the API client, the offline bar, the undo snackbar, day navigation and
the theme are shared. In student mode the widget shows "Not available", and the tile and reminder
scheduler don't register. Nothing personal-mode–specific has to be pulled apart later.

---

## 5. Minimum Android version (G-12)

**minSdk 28 (Android 9). targetSdk and compileSdk 36.**

Raising the minimum would buy almost nothing for this design:

- Compose, Glance, WorkManager and DataStore all support API 28.
- Exact alarms, notification permission and the like are handled by version checks that already
  sit in the code.
- Glance checkboxes in the widget are native on Android 12+. On older versions Glance draws them
  itself, and student mode doesn't use the widget anyway.

So the Note8 runs the same APK for deliverable 2, with no second build and no second device.
Running on both versions costs:

- an Android 9 emulator image (`system-images;android-28;google_apis;x86_64`) for testing student
  mode;
- one rule: no API above 28 without a version check. Android Lint (`NewApi`) enforces this in the
  build.

---

## 6. Authentication and devices (G-3 to G-7)

**The idea: each device is a `WebApiClient` that it requested itself.** The API already
authenticates requests with an `X-Api-Key` that maps to a `WebApiClient` row (user and workspace).
Sundial reuses that, and adds a way for a device to obtain its key by signing in, plus an
approval state.

**Schema (the next `src/db/vN.M.sql`; `v5.15.sql` at the time of writing).** Add to `web_api_client`:

- `client_kind` (`AGENT` | `DEVICE`; existing rows are `AGENT`)
- `device_name` and `device_model`
- `device_status` (`PENDING` | `APPROVED` | `REVOKED`)
- `approved_date` and `revoked_date`
- `api_key_hash`: device keys are stored only as a SHA-256 hash. Existing agent keys are unchanged.

**Sign-in flow.**

```
App                                   Server
 │ POST /api/v1/app/devices/sign-in    │  { username, password, deviceName, deviceModel }
 │──────────────────────────────────▶ │  check the password (same code as LoginServlet)
 │                                    │  create WebApiClient(kind=DEVICE, status=PENDING,
 │                                    │    user, the user's workspace), random 256-bit key
 │◀────────────────────────────────── │  { deviceKey (shown once), status: PENDING }
 │ store the key (Keystore)            │  email the approver: "New device: Galaxy S23 Ultra"
 │ "Waiting for approval…"             │
 │ GET /api/v1/app/session (polls)     │  403 device_pending … then 200 when approved
```

- **Email link (G-3).** `POST /app/devices/sign-in/email { email, deviceName }` creates the same
  pending device and emails a link, through the existing magic-link code. Opening the link in any
  browser marks the device as verified by email. The app is polling, and moves on to "Waiting for
  approval".
- **Password check.** The checks inside `LoginServlet` (password and magic link) move into a
  `LoginService` that the servlet and the new endpoints both call (G-13).
- **Approve (G-5).** A new desktop page, **Settings → Devices**, lists pending and active devices
  with their name, model, date and last use. **Approve** or **Reject** a pending one, **Revoke** an
  active one (G-6). The approval email links to this page. In the MVP Nathan approves his own phone.
  For deliverable 2, the page also lists the student's devices, because he is her guardian.
- **Stay signed in (G-4).** Device keys don't expire. The app is never signed out by time,
  updates, or reboots. Only **Revoke** ends access.
- **What the app shows.** A revoked key gets `401 device_revoked`, and the app shows "This phone's
  access was removed" with a sign-in button. A pending key gets `403 device_pending`, and the app
  shows the waiting screen.
- **One account per device (G-7).** The app stores one key. Signing in as someone else requires
  "Sign out of this phone", which removes the key on the phone and revokes it on the server.
- **Filter changes.** `ApiKeyAuthFilter` looks up the hash first, then the old plain key. It refuses
  devices that aren't `APPROVED`, and lets the two sign-in endpoints through without a key. The
  existing `ApiRateLimitFilter` throttles sign-in attempts. To guard against password guessing, it
  also gets a per-username limit of 5 failures in 15 minutes.

---

## 7. Server-side changes

### 7.1 Services first (G-13)

The logic now inside the `/m/*` servlets moves into services in `org.dandeliondaily.mobile.service`.
The servlets and the new API both call them. DAOs, where needed, go in
`org.openimmunizationsoftware.pt.doa`.

| Service | Takes over from | Responsibility |
|---|---|---|
| `PersonalDayService` | `TodoServlet` query and grouping | A day's actions: overdue (today only), Wake, Morning, Afternoon, Evening, with the web's ordering and personal-only filter. Also per-day counts for a date range (for the Dial's density marks). |
| `PersonalProjectService` | `ProjectServlet` | Open personal projects with today counts, and a project's actions grouped as Overdue, Due today, Due later, Unscheduled |
| `ActionQuickChangeService` | `MobileBaseServlet.completeAction / cancelAction / rescheduleAction / postponeToTomorrow` | Complete (with `ProjectActionBlockerManager.unblockActionsBlockedBy`), cancel, move to a date and slot. Every change is logged through `ActionChangeLogService` with actor `MOBILE_APP` or `WEB_MOBILE`. |
| `ActionEditService` | The `ActionServlet` save path | Create and edit, with priority copied from the project, the standard action set, `LinkedMeetingService.actionTypeError` validation, and the `READY` rule |
| `ActionFollowUpService` | `ActionServlet` "Schedule & Block" and "Complete & Next" | Block on a new action. Complete and create the next action via `ActionSentenceImportService`. |
| `ActionAccessService` | *(new)* | **G-8**: the action's project is in the client's workspace, the user is its owner or assignee, and the project is personal (not billable). Every action endpoint calls it first. |
| `ActionUndoService` | *(new)* | Records a snapshot before each change and reverses it on request (7.3) |
| `DeviceAccessService` and `LoginService` | `LoginServlet` (password and magic link) | Device sign-in, approval, revocation |

As each `/m/*` servlet switches to these services, it gets the ownership check from
`ActionAccessService` for free. That resolves the deferred "missing ownership check" issue as a
side effect, not as a separate fix. The GET-for-changes issue stays in the old pages, which the app
replaces.

### 7.2 New endpoints: `/api/v1/app/*`

All JSON, authenticated by the device key, and limited to the key's user and workspace.

| Method and path | Purpose |
|---|---|
| `POST /app/devices/sign-in`, `/sign-in/email` | Device sign-in (no key needed) |
| `GET /app/session` | User, mode, workspace, time zone, server "today", minimum supported app version |
| `GET /app/day?date=` | One day's actions grouped by pass (with overdue on today) |
| `GET /app/load?from=&to=` | Action counts per day (for the Dial), up to 120 days |
| `GET /app/waiting` | Open waiting actions with their age |
| `GET /app/projects`, `GET /app/projects/{id}` | Project list and grouped project actions |
| `GET /app/actions/{id}` | Detail: notes, link, type, assignee, project contacts |
| `POST /app/actions/{id}/complete`, `/cancel`, `/move` | Quick changes. Each response includes `undoToken`. |
| `POST /app/actions/{id}/notes` | Append to notes |
| `PUT /app/actions/{id}` | Edit (P-14) |
| `POST /app/actions/{id}/block` | Block on a new action (P-15) |
| `POST /app/actions/{id}/complete-next` | Complete and create the next action (P-16) |
| `POST /app/actions` | Capture `{ sentence, projectId?, date?, slot? }` (P-17) |
| `POST /app/capture/preview` | Parse a sentence without saving (for the live preview) |
| `POST /app/undo/{token}` | Undo |
| `GET /app/action-types` | The type list, with labels |

These are new resources beside the existing `ActionsResource` and `ProjectsResource`. They extend
`BaseApiResource` and appear in the OpenAPI document like the rest.

### 7.3 Undo, server side

Table `action_undo` (new model with `.hbm.xml` and a `hibernate.cfg.xml` mapping):

- `undo_id` and `token_hash`
- `client_id` and `created_date`, with an expiry of 5 minutes
- `payload`: JSON holding a before-snapshot of every action row the change touched (status, date,
  slot, `blockedBy`, `nextChangeDate`) and the IDs of any rows it created
- `used`

**Undo** restores each snapshot row, and cancels any rows the change created (the same thing the
web's "Delete" does). It first checks each row's `nextChangeDate` against the one recorded after
the change. If anything changed since, undo refuses with `409 changed_elsewhere`. A nightly cleanup
deletes expired rows.

### 7.4 Desktop

There is one new page, **Settings → Devices** (section 6), plus a link to it from the account menu.
Nothing else on the desktop changes.

---

## 8. Development setup and workflow (D-1 to D-5)

### 8.1 One-time setup (D-1)

| Step | Who | What |
|---|---|---|
| 1 | Agent | Create `android/` with the Gradle wrapper, pinned to a wrapper version that supports the AGP release in use |
| 2 | Agent | Write `android/tools/env.ps1`. It sets `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`, puts `platform-tools` and `emulator` on the PATH **for the session**, and points `JAVA_HOME` at **Android Studio's bundled JBR (JDK 21)**. The machine's Java 25 is newer than Gradle and the Android Gradle Plugin reliably support, so the Android build doesn't use it. |
| 3 | Nathan, once | Set `ANDROID_HOME` as a user environment variable (one `setx`, which an agent can run if allowed) |
| 4 | Agent | `sdkmanager "platforms;android-36" "build-tools;36.0.0"` and, later, `"system-images;android-28;google_apis;x86_64"` for the Note8 emulator |
| 5 | Nathan, once | On the S23: Settings → About phone → tap **Build number** 7 times. Then Developer options → **USB debugging** and **Wireless debugging** on. |
| 6 | Nathan, once | Plug in by USB and accept the "Allow USB debugging" prompt (tick *Always allow*) |
| 7 | Agent and Nathan, once | Wireless pairing: Nathan opens Wireless debugging → *Pair with code*. The agent runs `adb pair <ip:port> <code>`. |
| 8 | Agent | Create the release signing key with `keytool` at `%USERPROFILE%\.android\sundial-release.jks`, with its passwords in `%USERPROFILE%\.gradle\gradle.properties`. Never commit it. **Nathan backs it up**: losing it means uninstalling to update (and signing in again). |

### 8.2 Emulator or phone (D-2)

| Use | Where | Why |
|---|---|---|
| Layout work, Compose UI tests, screenshots for review | **Emulator** (`Pixel_7`, API 36) | Agents can start, drive and screenshot it with no hands |
| Student mode on Android 9 | **Emulator** (API 28, created in deliverable 2) | No need to find the Note8 |
| The Dial's feel, haptics, widget, notifications, battery behavior, daily use | **The S23 Ultra** | Haptics and One UI's alarm and widget behavior only show up on the real device |

The phone connects **over Wi-Fi** day to day (`adb connect`), and by USB as the fallback when
wireless debugging drops after a network change. `tools/phone.ps1 connect` tries Wi-Fi, and
explains what to tap if the phone isn't visible.

### 8.3 Agent commands (D-5)

All commands run from `android/` after `. tools\env.ps1`:

```
.\gradlew :app:testDevDebugUnitTest            # JVM tests (Dial math, scheduler, parsing, view models)
.\gradlew :app:assembleDevDebug                # build
.\gradlew :app:connectedDevDebugAndroidTest    # Compose UI tests on the running emulator
emulator -avd Pixel_7 -no-snapshot-save        # start the emulator (background)
.\gradlew :app:installDevDebug                 # install on the emulator or phone (-s picks the device)
adb shell am start -n org.dandeliondaily.sundial.dev/.MainActivity
adb exec-out screencap -p > shot.png           # an agent looks at the result
adb logcat -s Sundial:*                        # app logs
tools\phone.ps1 install-prod                   # build prodRelease and install on the S23
```

A debug-only broadcast fires any reminder now, for testing:
`adb shell am broadcast -a org.dandeliondaily.sundial.dev.DEBUG_REMINDER --es pass MORNING --es kind SWEEP`.
`adb shell dumpsys alarm | findstr sundial` shows which alarms are scheduled.

### 8.4 Local and production servers (D-4)

- **The `dev` flavor** uses `http://localhost:8089/dandelion/`.
  - **On the emulator**, the build uses `10.0.2.2:8089`, the emulator's alias for the host
    machine.
  - **On the phone**, the agent runs `adb reverse tcp:8089 tcp:8089` (USB or wireless), so
    `localhost:8089` on the phone reaches Tomcat on the PC. That needs no firewall changes or IP
    addresses. `phone.ps1` sets it up on every connect.
  - Cleartext HTTP is allowed **only** in the dev flavor's network security config, and only for
    `localhost` and `10.0.2.2`.
  - The local database is a daily copy of production. Nathan signs in with his normal username,
    and approves the dev device on the local Settings → Devices page.
- **The `prod` flavor** uses `https://dandelion-daily.org`, and talks HTTPS only.
- A hidden settings screen in the **dev** flavor allows overriding the URL, for example to point at
  the PC's LAN IP without adb.

**Server first.** Every build step that adds endpoints is deployed to the local Tomcat (per
`CLAUDE.md`) and tested with `dev` before it is staged to production. The `prod` app is only
updated after Nathan runs `sudo deploy-dandelion`. `/app/session` reports the server version, and
the app shows "Server too old for this build" rather than failing in odd ways.

### 8.5 Installing and updating on the phone (D-3)

- **First install:** `tools\phone.ps1 install-prod` builds `prodRelease` (signed with the release
  key) and runs `adb install -r`. Nathan signs in once and approves on the desktop.
- **Updates:** the same command. `-r` with the same signing key keeps the sign-in, settings,
  watches and widget. A "What's new" line shows on first launch after each update. `versionCode`
  comes from the git commit count, so every build is newer than the last.
- **Without a PC nearby:** not needed for the MVP. If it becomes useful later, the APK could be
  copied to the phone and opened in My Files, which works because the signature matches.

### 8.6 Testing approach

| Layer | What's tested | How |
|---|---|---|
| Server services | Day grouping and ordering (against the old servlet's output for the same data), access checks, undo restoring blocked actions, sentence capture | JUnit against the local database copy, run by agents with Maven |
| App logic (JVM) | **Dial math** (angle → day, laps, clamping), **reminder planning** (schedule + watches + day's actions + clock → which notifications, and when), parse preview mapping, undo state | Pure Kotlin unit tests with a fixed clock and time zone, including DST days and midnight crossovers |
| API client | Error handling (pending, revoked, offline, 409 on undo) | MockWebServer |
| UI | Today gestures, move tray, Sweep, capture flow | Compose UI tests on the emulator, plus screenshots for review |
| Reminders on the device | Delivery under Doze and Samsung sleeping, actions from the notification | The debug broadcast, plus `adb shell dumpsys deviceidle force-idle` and a 3-day real-use check on the S23 after step 7 |

---

## 9. Build order

Each step ends with something Nathan can use or check on his phone. The `/m/*` pages remain
available throughout.

| Step | Builds | At the end Nathan can… |
|---|---|---|
| **0. Skeleton** | `android/` project, flavors, `env.ps1`, `phone.ps1`, CI-style test task; phone paired | Install "Sundial Dev" on the S23 from a command an agent runs; it shows the server's version |
| **1. Devices** | Schema, `LoginService`, `DeviceAccessService`, sign-in endpoints, filter changes, desktop **Devices** page; app sign-in, pending and revoked screens | Sign in on the phone, approve it on the desktop, stay signed in through reboots, revoke it |
| **2. Read the day** | `PersonalDayService`, `PersonalProjectService`, the read endpoints, `/m/todo` and `/m/project` switched to the services (G-13); Today, look-ahead, Projects, Waiting, action sheet (read-only), offline bar | Check his day, other days, projects and waiting items on the phone; the web shows the same lists |
| **3. Act and undo** | `ActionQuickChangeService`, `ActionAccessService`, `ActionUndoService`; complete, cancel, move tray, snackbar undo; `/m/*` quick buttons switched over | **Use Sundial as his daily to-do list.** Check off, move and cancel, with undo |
| **4. The Dial** | The Dial component (gesture and tap mode), `/app/load` density marks, jump chips, date picker fallback | Move anything to any date in one gesture. *Decision point:* if the gesture doesn't feel right after a week, the tap mode and chips carry P-9 on their own. |
| **5. Capture and edit** | Capture with preview, project context and recents, Quick Settings tile, launcher shortcut, share target, Just-added chip; edit form, notes, link, Block on, Complete & Next (`ActionEditService`, `ActionFollowUpService`) | Do everything the `/m/*` pages do, faster |
| **6. Reminders and Sweep** | Pass schedule, watches, scheduler and receivers, notification actions, the Sweep screen, battery-setting check | Get start and Sweep reminders, and handle them from the notification |
| **7. Widget** | Glance widget with checkboxes, refresh on change, boundaries and every 30 minutes | Check off and capture from the home screen |
| **8. Replace** | Two weeks of daily use alongside the web; fixes; then a hint on the mobile login choice | Stop using `/m/*` on the phone |

Steps 0–3 are the critical path. After step 3 Sundial is already a better way to work the day than
the mobile pages, and everything after it adds to a working app.

**Deliverable 2 (student mode) afterwards:**

1. Extract `StudentSchoolService`, `StudentStoreService` and `StudentBankService` from the three
   student servlets. Switch the servlets to them, and add `/api/v1/app/student/*`.
2. Add the `student/` navigation graph: School (day view, Required, Assigned, Optional and Chores,
   points, today's total), Store, and Bank. It reuses day navigation, the undo snackbar (S-6) and
   sign-in.
3. Test on the API 28 emulator, then install the same `prodRelease` APK on the Note8 by USB.
   Nathan approves it on the Devices page, and then she stays signed in (G-4, her main complaint).

---

## 10. Requirements coverage

| ID | How Sundial meets it | Status |
|---|---|---|
| G-1 | One APK. `/app/session` mode picks the personal or student navigation graph (4) | Met |
| G-2 | Kotlin and Compose, no WebView (4) | Met |
| G-3 | Sign-in with a password or email link; the device key is invisible to the user (6) | Met |
| G-4 | Device keys never expire; only revocation ends them (6) | Met |
| G-5 | New devices are `PENDING` until approved on Settings → Devices (6) | Met |
| G-6 | Revoke on the Devices page; the app shows "access removed" (6) | Met |
| G-7 | One stored key; switching means signing out, which revokes (6) | Met |
| G-8 | `ActionAccessService` on every endpoint; the key's user and workspace only (7.1) | Met |
| G-9 | Offline bar, data marked with its age, no queued changes (2.12) | Met |
| G-10 | Immediate changes, server-side undo, no confirmations (2.5, 7.3) | Met |
| G-11 | Sideloaded with adb, signed with a private key (8.5) | Met |
| G-12 | minSdk 28 covers the Note8; the S23 is the design and test target (5) | Met |
| G-13 | `/m/*` servlets and `/app/*` call the same new services (7.1) | Met |
| G-14 | Only the S23 in the MVP (8.5) | Met |
| P-1 | Today with an overdue row first (2.1) | Met |
| P-2 | Passes; the current one opens automatically (2.1) | Met |
| P-3 | Day-by-day arrows and swipe; a **Now** chip pulls items forward (2.2) | Met |
| P-4 | Waiting tab, oldest first; stale ones join the Evening Sweep (2.8) | Met |
| P-5 | Projects tab with the four groups (2.9) | Met |
| P-6 | Tap ○ or swipe right; blocked actions unblocked by the service (2.1, 7.1) | Met |
| P-7 | Move tray or action sheet, undoable (2.3) | Met |
| P-8 | Move tray: next pass, tonight, tomorrow, the next three days (2.3) | Met |
| P-9 | **The Dial**, plus jump chips and a date picker (2.4) | Met |
| P-10 | 6-second snackbar; step-back in the Sweep; Undo in notifications (2.5) | Met |
| P-11 | Notes in the action sheet (2.6) | Met |
| P-12 | Append-only note field via `ActionNotesService.append` (2.6) | Met |
| P-13 | Open link button (2.6) | Met |
| P-14 | Edit form with project, description, type, when, link and assignee (2.6) | Met |
| P-15 | Block on… (2.6) | Met |
| P-16 | Complete & next with verb chips and a live parse preview (2.6) | Met |
| P-17 | One sentence; when defaults to now if not stated (2.7) | Met |
| P-18 | Capture from a project or action sheet uses that project (2.7) | Met |
| P-19 | Recent-project chips, search, or a project token (2.7) | Met |
| P-20 | "Details" in the snackbar, and the Just-added chip for 10 minutes (2.7) | Met |
| P-21 | Sweep reminder before each pass closes (3.1) | Met |
| P-22 | Pass-start reminder (3.1) | Met |
| P-23 | Watches: projects and types, by pass and weekday (3.1) | Met |
| P-24 | Pass schedule on the phone, built on the four Dandelion blocks; nothing sent to Dandelion (3.1) | Met |
| D-1 | Setup table: env script, JBR for Gradle, one-time phone steps (8.1) | Met |
| D-2 | Emulator vs phone, and when each is used (8.2) | Met |
| D-3 | `phone.ps1 install-prod`; same key keeps sign-in (8.5) | Met |
| D-4 | `dev` and `prod` flavors, `10.0.2.2` and `adb reverse` (8.4) | Met |
| D-5 | Every step is a command; Nathan only taps on the phone (8.3) | Met |
| S-1 to S-16 | A `student/` graph on the same sign-in, client and services pattern; School, Store and Bank as described in 9; undo for completion (S-6) uses the same undo service with a points reversal | Fits, no rework |

**Things Sundial adds that the requirements don't ask for:** the widget, the share target, the
Quick Settings tile and the density marks. Each is small and built in a late step.

**Suggestions about the requirements.** These are open questions for Nathan, not changes Sundial
depends on:

- **P-17, "when".** Sundial defaults *when* to "today, current pass" if the sentence has none. That
  meets the requirement without forcing him to pick. If he'd rather be asked every time, it's a
  setting.
- **Student purchase undo (open in `Student App.md`).** Suggestion: buying takes effect right
  away, the guardian email waits 60 seconds, and an undo within that window is a Return, with no
  email sent.

---

## 11. Risks and open questions

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| **The Dial gesture doesn't feel natural.** A circular drag is unusual, and it may need tuning. | Medium | It is built and tried in step 4 on its own. Tap mode, jump chips and the date picker meet P-9 without the gesture. The drag is tuned on the real phone, not the emulator. If it doesn't win after a week, the gesture is turned off and the Dial stays as the tap-mode sheet. |
| **Samsung delays alarms or widget updates** | Medium | Never-sleeping setup check, exact alarms, a 3-day field test in step 6. The widget is refreshed at pass boundaries by the same alarms. |
| **Undo is subtle** (completions unblock other actions, captures create rows) | Medium | One service with a full before-snapshot, a changed-elsewhere check, and unit tests for each change type |
| **Gradle and JDK mismatch** on a machine with Java 25 | Low | The build pins Android Studio's JBR through `env.ps1`; the wrapper is pinned |
| **The sentence parser** reads some phone-typed sentences wrongly | Medium | The live preview shows the result before saving; chips and the Dial override it; the parser improves for the web too |
| **Password sign-in on a public API** | Low | Rate limits, approval required before any data flows, a hashed key, email to the approver |
| **`USE_EXACT_ALARM` if the app is ever published** | Low | Only matters for a store listing (G-11 says none). Switch to `SCHEDULE_EXACT_ALARM` with a settings prompt. |
| **Scope**: widget, tile and share target add work | Low | They come in steps 5 and 7, after the app is already in daily use. Any of them can slip without hurting the MVP. |

### Questions for Nathan

1. **Pass times:** are the defaults in 3.1 close to your real day, particularly the Saturday and
   Sunday columns?
2. **Waiting items:** what age makes one "stale"? Seven days is the proposed default. Should
   undated waiting actions count?
3. **Default watch:** leave *might* and *would like to* out of reminders by default?
4. **Device approval:** is a desktop page plus an email enough, or do you also want a one-tap
   Approve link inside the email?
5. **Both apps on the phone:** happy to have "Sundial" and "Sundial Dev" side by side?

---

## 12. Why this one

**Where the three entries agree.** Seedclock, Relay and Sundial all organize the day by
Dandelion's four time-of-day blocks. All three close a block with a short review of what's left
(Seedclock's Sweep, Relay's handoff, Sundial's Sweep), and all three surface waiting items at
useful moments. That convergence suggests the requirements point clearly there. Whichever entry
wins, the funded app will very likely have this end-of-block review, and the judges can treat it as
common ground rather than as any entry's distinction.

**Where they differ: what each one bets on.**

- **Seedclock** bets on a **single ongoing notification**, the Now card, as the main screen. Its
  big strength is speed for the current block: check-off from the lock screen without opening
  anything. The trade-off it accepts is that the notification is a small canvas with a few
  actions, so moving, capture details, projects and editing happen in its companion app.
- **Relay** bets on **organizing the transition**: a calm Now screen for the current block, a
  handoff at each boundary, and a "Waiting on" pulse. Its strength is a focused, uncluttered
  home screen and an emphasis on ending each block deliberately.
- **Sundial** bets on **the Dial**: making "when" a one-thumb gesture with a view of each day's
  load, used for every date choice in the app. Its argument is that **moving**, not checking off,
  is where the current pages are clunkiest. "Move" needs a pop-up today, and anything past seven
  days needs the full edit form. Moving is also what Nathan does to almost everything he doesn't
  finish, and moves of more than a week are common (P-9). Its glance surface is a home-screen
  widget rather than a notification.

**Why base the plan on Sundial.**

1. **It aims at the most frequent friction.** Checking off is already one tap on the web. Moving
   isn't, and the Sweep (which all three entries have) is mostly moving. So how fast moving is
   decides how good every Sweep feels. The Dial makes each "tomorrow, next pass, +2 weeks,
   the 24th" one gesture, with the load in view.
2. **It's the most complete plan.** Every requirement maps to a section, the server work is
   grounded in the classes that exist (`MobileBaseServlet`'s change methods,
   `ProjectActionBlockerManager`, `ActionSentenceImportService`, `ActionNotesService`,
   `WebApiClient` and `ApiKeyAuthFilter`), and undo is designed to reverse unblocking correctly.
3. **Its signature risk is contained.** If the Dial gesture disappoints, step 4 falls back to tap
   mode and chips, and the rest of the app is unaffected.
4. **It's combinable.** If the judges like Seedclock's lock-screen card or Relay's calm Now
   screen, either fits on top of Sundial's services, API and undo design without changing them.
