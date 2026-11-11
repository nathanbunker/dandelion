# Relay: the Dandelion Android app

## 1. Summary

Relay is a native Android companion for Dandelion whose home screen answers one question immediately: **what belongs in my hands now?** It preserves the full current mobile feature set, but it does not reproduce the current pages. Instead, the current time block becomes a **runway**:

- one large **Now** card for the next action;
- a short, visible **On deck** queue for everything else in the block;
- compact rails for overdue work and the other blocks;
- tap-first controls for Done, Move, and More;
- a capture button that is always reachable.

The runway is presentation, not an algorithm that changes Dandelion's priorities. Relay orders actions exactly as the server returns them and lets Nathan promote any visible action to Now locally. It does not hide the rest of the list or pretend to schedule the day more precisely than Dandelion does.

This is the proposal's singular choice. A long list makes every item visually equal; a one-card “focus mode” would hide useful context. The runway keeps context visible while giving one action an obvious place to land. Common work happens without navigation: Done is one tap, Move opens a thumb-sized sheet with Evening, Tomorrow, and the next few days, and every mutation shows Undo. Full editing, notes, links, Schedule & Block, Complete & Next, projects, future days, and waiting actions live one level deeper.

Relay sends at most two useful prompts around a configured time block: an optional start summary and an optional end nudge when included actions remain. Notifications are ordinary grouped prompts, not an always-present task surface. They open the relevant runway; a small action can be completed directly from the notification. Reminder filters, days, and times live on the phone. WorkManager provides durable, inexact scheduling, which is appropriate for “around the end of Afternoon”; Relay does not ask for exact-alarm permission.

The app is Kotlin with Jetpack Compose, a single activity, ViewModels and unidirectional UI state. Retrofit/OkHttp talks to focused JSON endpoints in the existing Jersey `/api/v1` application. The app module lives in this repository under `android/`, uses the Gradle wrapper, and targets Android 16 / API 36 with `minSdk 28` (Android 9), so the same APK can later serve the Galaxy Note8. Personal and student modes are feature packages selected from the signed-in user's `workflowType`, not separate applications.

Authentication extends the existing `WebApiClient` model. Nathan signs in with his Dandelion username and password, receives a short-lived pending-device token, approves the named phone on Dandelion's desktop device page, and the app exchanges the approval for a random per-device bearer secret. It stores that secret using Android Keystore-backed encryption and remains signed in. Revocation disables the server-side client immediately.

Server work begins by extracting the behavior currently embedded in `TodoServlet`, `ProjectServlet`, and `ActionServlet` into workspace-scoped services. Both the existing web pages and new API call those services, satisfying the “same behavior” rule. The API never accepts workspace or user identity from the phone; it derives both from the approved device. Mutations use idempotency keys and return a short-lived server undo token.

### What changed after the pitch

The pitch emphasized the block-boundary handoff. After reading the other locked pitches, it became clear that closure rituals were common ground: Sundial makes a swipe Sweep its centerpiece, while Seedclock makes a persistent notification and its Sweep the primary experience. Relay therefore sharpens a different idea already present in its pitch—“one obvious next action”—into the **Now runway**. Handoffs remain helpful reminders, but there is no full-screen sweep, gesture vocabulary, widget, Quick Settings tile, or ongoing notification in the MVP. This makes Relay materially distinct, simpler to learn, and smaller to build without borrowing either competitor's signature.

## 2. Interaction design for personal mode

### Navigation model

The bottom navigation has three destinations:

1. **Today** — the runway, date navigation, and overdue work;
2. **Waiting** — waiting actions with age and follow-up tools;
3. **Projects** — open personal projects and their actions.

A floating **+** capture button appears on all three. The top app bar shows the selected date, connection state when relevant, and a settings menu. Today opens on the current date and current block. Back returns from detail or edit to exactly the prior list and scroll position.

### Today and the runway

At 3:15 p.m., the screen looks conceptually like this:

```text
Wednesday, Nov 11                         +
‹ day                 TODAY              day ›

Overdue · 2                         Review ›

AFTERNOON · 3 LEFT
┌ Now ───────────────────────────────────┐
│ Church                                 │
│ Confirm the room for youth night       │
│                                        │
│ [ Done ]     [ Move ]     [ More ]     │
└────────────────────────────────────────┘
On deck
  ○ Send revised agenda              ⋮
  ○ Pick up prescription             ⋮

Wake  ✓ 3       Morning ✓ 5       Evening 2
```

The current block is expanded automatically. The first server-ordered action becomes Now; the rest remain visible as On deck. Tapping an On deck row promotes it to Now for this viewing session. This promotion is not persisted and does not claim to reprioritize Dandelion. A “Show all” expansion handles a large block without turning the whole screen into cards.

Other blocks are compact rails showing count and completion state. A tap expands one in place and makes its first action Now. This supports doing an Evening action early without forcing date changes. The selected block is remembered until the app is closed; reopening returns to the actual current block.

Overdue is a compact red-amber rail above the block, never silently mixed into today. Tapping it opens the same runway treatment for overdue actions. Today includes due-today plus overdue; other dates show only that date, matching current behavior. Previous is hidden on Today to match the existing personal workflow; next-day navigation and a Today shortcut remain available. A calendar sheet permits a direct jump, but day-by-day movement is the primary control.

Loading shows stable skeleton rows. If the server cannot be reached and no fresh response exists, Relay shows a clear “Can't reach Dandelion” panel with Retry; it never implies that a local action was saved. A last successful list may remain dimmed for orientation with a visible timestamp, but all mutation buttons are disabled. This is not offline mode.

### Complete, cancel, move, and undo

**Done** immediately removes the action from the active queue and advances the next On deck item into Now. The server response includes any action unblocked by completion, and Relay inserts it in the correct section after refresh. A snackbar says “Completed · Undo” for 12 seconds. The server token remains valid for 60 seconds to tolerate network delay.

**Move** opens a modal bottom sheet whose first row is context-sensitive one-tap choices:

- later today: the other remaining time blocks;
- Tomorrow;
- the next five calendar days, showing weekday and date.

The second row offers Wake, Morning, Afternoon, and Evening without changing the date. **Pick date…** opens a Material date picker with a time-block choice and supports dates months ahead. Choosing anything commits immediately, dismisses the sheet, and shows Undo. No confirmation appears.

**More** opens Notes & link, Edit, Schedule & Block, Complete & Next, Cancel. Cancel is immediate and undoable; it is not colored or positioned where an ordinary thumb tap can easily confuse it with Done. Long press and swipe have no required meaning. Accessibility users and first-time users receive the same visible controls.

Undo is server-authoritative. The app optimistically restores the row, calls the token endpoint, then reconciles with the returned action. If undo fails, the row returns to server truth and Relay explains why. Only the latest mutation has a snackbar, but every mutation result is settled before another mutation on the same action is sent.

### Action detail

Tapping the description opens a detail screen with project icon/name, complete description, date and block, action type, assignee, link, and notes. The primary row is Done, Move, and More, matching Today. The link opens through an Android intent after validating that it is an `http` or `https` URL. Notes are readable without entering edit mode; **Add note** appends text through the shared notes service. Existing notes are not edited.

**Edit** supports project, description, action type, date, time block, link, and assignee. It uses server-supplied allowed projects, action types, and contacts rather than hard-coded IDs. Validation errors stay attached to fields.

**Schedule & Block** asks for the blocking action's description, initially dates it Today in the same project, blocks the current action, and then displays the newly created action. **Complete & Next** uses a single sentence field plus when suggestions and optional notes; it completes the current action and creates the follow-up atomically using `ActionCompletionService.closeActionWithHandoff` and `ActionSentenceImportService`. The familiar verb chips may appear as writing aids, but they are not required.

### Quick capture

Tapping **+** opens a half-height capture sheet with the keyboard focused:

```text
Add action
What needs doing?
[ Ask Maria for the key                 ]

When?   [Today] [Tomorrow] [Pick…]
Block   [Afternoon v]
Project [Church v]

[ Add ]                         More details
```

At minimum Nathan supplies what and when. When opened from a project, Project is already set and visually acknowledged; saving takes one tap after typing. Elsewhere the field defaults to the most recently used personal project and exposes a searchable recent-first picker. Relay never silently guesses a project when none has been chosen before.

Natural date phrases in the description are optional convenience, not the only path. The explicit When and Block controls guarantee that quick capture works even if parsing is uncertain. The API uses `ActionSentenceImportService` for a sentence when asked, but sends back a parsed preview before commit if the sentence would override the explicit controls.

After saving, the sheet closes and a chip appears for 60 seconds: **Added · Add details**. It opens the new action directly so Nathan can add notes, link, type, or assignee a minute later. This state is also available as the top row under Today until another action is captured, so rotating or briefly leaving the app does not erase the route back.

### Waiting

Waiting is a real destination, not decoration. It lists personal `waiting` actions ordered by how long they have been waiting. Each row shows project, description, since-date, and assignee/person when available. The default visible actions are:

- **Follow up** — creates a new scheduled action in the same project, without destroying the waiting item;
- **Move** — changes the waiting action's date/block when that is the intended resolution;
- **Done / Cancel** under More — resolves it with Undo;
- open detail.

Once per day, the Today screen may show a quiet “Waiting on · 4, oldest 9 days” rail. It does not inject waiting work into the Now queue. Reminder rules can include waiting actions, but the default is one Evening summary on weekdays only if at least one item is seven days old.

### Projects

Projects shows open, non-billable projects alphabetically with icon and due-today count. Selecting one shows Overdue, Due today, Due later, and Unscheduled sections, preserving the baseline. Rows have Done, Move, and More; capture inherits the project. Due-later dates link to that day in Today. Search filters the in-memory response only; the list is small enough that server search is unnecessary for MVP.

### Handoffs without a sweep

At a configured block end, Relay opens the current runway with remaining items if Nathan taps the notification. There is no separate full-screen triage mode and no required series of swipes. He handles one item, sees the next arrive in Now, and can stop at any point. If he ignores the prompt, nothing is auto-moved. This preserves agency and keeps the same interaction model all day.

### Student mode fit

After login, `workflowType=STUDENT` selects a `student` feature graph with School, Store, and Bank bottom destinations. It reuses the same network/authentication core, error handling, design tokens, mutation/undo mechanism, and date components. It does not show the personal runway or reminders. The School list deliberately keeps Required, Assigned, Optional, and Chores together and keeps completed/cancelled items visible. This is a later feature package, not a fork.

## 3. Reminders

### Behavior

Relay has three notification templates:

1. **Start summary** (off by default for Wake, on for Morning and Evening): “Morning · 5 things, 1 overdue.” Actions: **Open** and **Snooze 30m**.
2. **End nudge** (on by default): only fires if matching work remains: “Afternoon · 3 still on deck.” It shows the first two descriptions when privacy setting permits. Actions: **Open**, **Done** for the first item, and **Snooze 30m**.
3. **Waiting pulse** (weekday Evening, seven-day age threshold by default): “Waiting on 2 replies.” Action: **Review**. It is suppressed when an Evening end nudge already includes waiting work.

There is never one notification per action. Relay uses one notification ID per block so a refreshed result replaces, rather than stacks on, the prior prompt. Completing the last matching action cancels that block's notification. Opening dismisses it. A Done action enqueues a network mutation; while it runs, the notification says “Completing…”. On success it advances to the next item or disappears. On failure it says “Couldn't reach Dandelion—tap to retry”; it never falsely reports success.

Snooze is local and applies only to that occurrence. **Silence this block today** is available after expanding the notification and does not alter tomorrow's schedule. Android notification-channel controls remain respected.

Settings are a weekly matrix. Each block has a start time, end time, start-summary toggle, end-nudge toggle, and filters. Filters are include/exclude rules for personal projects and action types; “All personal actions” is the default. A rule can vary by weekday group (Mon–Fri and Sat–Sun initially) and can be expanded to individual days. The UI shows a plain-language preview: “Weekdays at 5:30 PM, if Afternoon has unfinished Church or Home actions.” Per-action reminders are intentionally absent.

Default proposal:

| Block | Start summary | End nudge |
|---|---:|---:|
| Wake | Off | 8:00 AM |
| Morning | 8:30 AM | 12:30 PM |
| Afternoon | Off | 5:30 PM |
| Evening | 6:00 PM | 9:30 PM |

These are onboarding suggestions, not assumptions written to Dandelion. Nathan confirms or changes them. The phone time zone is used; after a zone or clock change, all work is rescheduled.

### Technical design

Settings live in Preferences DataStore. A `ReminderPlanner` creates named one-time WorkManager requests for the next seven days (`relay:block:2026-11-11:afternoon:end`) and replenishes the horizon after each run, app start, boot, app update, time-zone change, or settings change. WorkManager is intentionally inexact: a reminder about a broad Dandelion block does not justify exact alarms, battery exemptions, or `SCHEDULE_EXACT_ALARM`.

At run time, a `CoroutineWorker` fetches a lightweight `/personal/reminder-context` response, applies local filters, and posts only if something remains. Constraints require a network connection. If the network is unavailable, WorkManager retries with backoff; prompts older than 45 minutes are discarded as stale. A start prompt that runs after its block's end becomes an end nudge or is discarded.

Notification action receivers do no network work directly. They validate the action ID and enqueue a unique expedited worker with an idempotency key. Android 13+ notification permission is requested only after onboarding explains the benefit; denial leaves the app fully usable and shows a settings hint, not repeated prompts. Notification descriptions can be hidden on the lock screen through a Relay privacy toggle.

Tests inject a clock and a fake WorkManager scheduler. Debug builds include a hidden developer screen to shift block boundaries to the next two minutes, inspect scheduled work, and send sample notifications. This is more reliable than waiting until evening during development.

Rejected alternative: `AlarmManager` exact alarms. It can be more punctual but adds permission and platform-policy complexity for no user benefit when Dandelion itself models only broad periods. Rejected alternative: a foreground service / ongoing notification. It would make Relay more intrusive, consume a permanent notification slot, and duplicate Seedclock's proposal rather than serve Relay's app-first runway.

## 4. App architecture

### Platform choices

- **Language:** Kotlin.
- **UI:** Jetpack Compose with Material 3, one activity, Navigation Compose.
- **State:** screen ViewModels, coroutines, `StateFlow`, immutable UI state, saved-state handles for navigation restoration.
- **Network:** Retrofit + OkHttp + Kotlin serialization (or Moshi; choose one at implementation and use it everywhere). A debug-only interceptor logs metadata, never credentials or note bodies.
- **Settings:** Preferences DataStore.
- **Credentials:** a random device secret encrypted with a non-exportable Android Keystore key. No API key appears in source, Gradle properties, logs, or backups.
- **Background:** WorkManager.
- **Testing:** JUnit, kotlinx-coroutines-test, MockWebServer, Compose UI tests, and instrumented notification tests.

No Room database is proposed for MVP. The app is online-only, and a local domain cache creates conflict and migration work without satisfying an offline requirement. ViewModels retain the latest response in memory; DataStore retains only settings and small UI preferences such as recent project IDs. The encrypted credential is excluded from Android backup.

### Repository and modules

The app lives in this repository:

```text
android/
  gradlew, gradlew.bat, gradle/wrapper/...
  settings.gradle.kts
  app/
    src/main/java/org/dandeliondaily/relay/
      core/auth, core/network, core/design, core/reminders
      feature/personal/today, action, capture, waiting, projects, settings
      feature/student/school, store, bank       (deliverable 2)
```

One repository makes atomic API/app changes, shared CI review, and agent navigation straightforward. Maven remains responsible for the WAR; Gradle is isolated below `android/`. A separate repository would reduce checkout size slightly but would make synchronized endpoint changes and contest-plan traceability harder.

Personal and student modes share one installed application and one session. A bootstrap endpoint returns `workflowType`, capabilities, display name, server time, and schema version. The root navigator selects Personal or Student once per session. Feature packages are separated so student screens do not accumulate personal conditionals, but they share all infrastructure.

The app uses a repository layer (`PersonalRepository`) between ViewModels and generated/manual API DTOs. ViewModels never construct URLs or headers. Mutations carry a UUID idempotency key. All server timestamps are ISO-8601 instants; action dates are ISO `yyyy-MM-dd` date-only values plus a time-block enum, avoiding device-zone shifts.

## 5. Minimum Android version

Relay uses `minSdk 28` (Android 9), `targetSdk 36`, and `compileSdk 36` using the installed Android SDK. API 28 keeps the Galaxy Note8 viable for deliverable 2 while imposing little cost on this design. Compose, coroutines, WorkManager, DataStore, Keystore, and notification compatibility APIs support this level. The S23 Ultra remains the optimization and acceptance-test target.

Target-specific behavior is isolated:

- Android 13+ requests runtime notification permission; Android 9 does not.
- lock-screen privacy and notification categories use AndroidX compatibility APIs;
- predictive back and edge-to-edge enhancements apply only where supported;
- no API 29+ feature is assumed in domain code.

The main alternative is `minSdk 26`, but Android 8 support buys nothing for the named devices and expands testing. A newer minimum such as 31 would simplify a few branches but strand the Note8 and force a later split, contrary to one-app intent.

Before implementation locks the choice, create a blank Compose minSdk-28 build and install it on the available emulator; before student work, smoke-test the signed APK on the actual Note8. If a future Compose dependency raises its minimum, pin the last supported version rather than silently abandoning Android 9.

## 6. Authentication and devices

### Enrollment flow

1. Relay asks for production/local server (debug builds only expose local selection), username, password, and a device name defaulted to “Nathan's S23 Ultra.”
2. `POST /api/v1/mobile/enrollment` validates credentials using the same password verification as web login. The password is used only for this TLS request and is never saved.
3. The server returns a random, short-lived `requestId` plus polling secret, but no data credential. The app shows “Waiting for approval” with a six-character comparison code.
4. Dandelion's desktop **Devices** page shows username, workspace, requested device name, time, approximate user agent, and comparison code. Nathan approves or rejects it while signed in.
5. Relay polls `POST /mobile/enrollment/{id}/exchange` with the polling secret. Approval creates an enabled `WebApiClient` scoped to the user's active workspace and returns the device secret once. Pending requests expire after 15 minutes.
6. Relay encrypts the secret with Android Keystore, calls bootstrap, and routes by `workflowType`.

Email-link sign-in is not required because G-3 permits password **or** email link. Password is the smaller and more testable MVP path. The approval step prevents knowledge of a password alone from granting a durable device credential.

### Persistence and revocation

OkHttp adds `Authorization: Dandelion-Device <secret>` over HTTPS. For a transition period the existing `X-Api-Key` mechanism remains for MCP clients; `ApiKeyAuthFilter` delegates both schemes to one lookup service. New device secrets are 256-bit random values; only a SHA-256 hash is stored for new mobile clients. Existing raw API keys need not be migrated in the MVP.

`web_api_client` gains `client_type`, `display_name`, `credential_hash`, `approved_date`, `revoked_date`, and optional `last_seen_device`. `enabled` remains the definitive switch. A separate `mobile_enrollment_request` table stores hashed polling secrets and expiry. Sequential migration and Hibernate mapping changes follow repository conventions.

The desktop Devices page lists name, user, created, approved, last used, and status. Revoke sets `enabled=N` and `revoked_date`; the next request returns a stable `device_revoked` 401. Relay deletes its local secret and shows “This device was revoked. Sign in to request access again.” It does not silently loop or fall back to password storage.

The Keystore key is non-exportable and requires device unlock state, not biometric authentication on every use. Android backup excludes secret material. Logout is not a daily workflow but Settings offers **Remove this device**, which revokes server-side when reachable and always deletes local credentials. One installation stores exactly one account.

Threat controls include rate limiting enrollment by IP and username, generic invalid-credential messages, CSRF protection on desktop approval, no secrets in URLs, constant-time hash comparison, and audit records for approval/revocation. The app trusts only release-configured HTTPS production; debug builds may allow cleartext solely to explicitly configured local development addresses.

## 7. Server-side changes

### Shared service layer

The crucial server change is not merely adding controllers. Create services that own behavior currently duplicated or embedded in rendering code:

- `PersonalTodayService`: today/other-day selection, overdue split, ordering, time-block grouping, personal-only and owner/assignee rules.
- `PersonalProjectService`: open personal projects and Overdue/Due today/Due later/Unscheduled grouping.
- `PersonalActionService`: workspace-scoped load/edit, move, cancel, Schedule & Block, allowed contacts/types, and link validation.
- existing `ActionCompletionService`, `ActionNotesService`, and `ActionSentenceImportService`: extended through narrow non-`AppReq` methods where necessary.
- `WaitingActionService`: workspace/user-scoped waiting query and age.
- `ActionUndoService`: mutation snapshot/token, expiration, ownership, and inverse operation.

`TodoServlet`, mobile `ProjectServlet`, and `ActionServlet` call these services before the API is considered complete. Rendering remains in servlets. Thus the web and Android app cannot drift on filters, ordering, unblocking, sentence parsing, or mutations. Service tests establish parity with current servlet behavior.

The current `ActionRecoveryService` is session-based and restores completed items to Today, so it is not sufficient for API undo or exact move reversal. `ActionUndoService` records the before-state for all affected action siblings and blocker relationships in a short-lived `action_mutation_undo` row. Undo runs transactionally and rejects a token if the action has changed again. Rows expire after 60 seconds and are purged lazily plus by a scheduled cleanup. The UI exposes Undo for 12 seconds.

### API surface

All routes are JSON under `/api/v1/mobile`:

| Method and route | Responsibility |
|---|---|
| `POST /enrollment` | Validate sign-in and create pending device request |
| `POST /enrollment/{id}/exchange` | Poll/exchange approved request for device secret |
| `GET /bootstrap` | User, `workflowType`, server time, capabilities |
| `GET /personal/day?date=` | Today or exact date, overdue, block groups, summary counts |
| `GET /personal/reminder-context?date=&block=` | Minimal current remainder for worker |
| `GET /personal/waiting` | Waiting actions with age/context |
| `GET /personal/projects` | Open personal projects and due-today counts |
| `GET /personal/projects/{id}/actions` | Four required project action groups |
| `GET /personal/actions/{id}` | Detail plus allowed commands |
| `POST /personal/actions` | Explicit or sentence-assisted capture |
| `PATCH /personal/actions/{id}` | Full edit with version precondition |
| `POST /personal/actions/{id}/complete` | Complete and return unblocked action + undo token |
| `POST /personal/actions/{id}/cancel` | Cancel + undo token |
| `POST /personal/actions/{id}/move` | Date/block move + undo token |
| `POST /personal/actions/{id}/notes` | Append note |
| `POST /personal/actions/{id}/block` | Schedule & Block atomically |
| `POST /personal/actions/{id}/complete-next` | Complete & Next atomically |
| `POST /undo/{token}` | Reverse the exact mutation while valid |

Responses include an opaque `version` (derived from change time plus ID initially) and commands the user may perform. PATCH and mutation calls send `If-Match` or version in the body; a conflict returns current state and a readable message. Every mutation accepts `Idempotency-Key`; the server retains the result briefly so notification retries do not double-complete or double-create.

The API does not accept `workspaceId`, `webUserId`, or billable scope from the phone. Each query starts with the authenticated client's workspace and its `WebUser`/contact. Loading an ID always constrains it by workspace and user ownership/assignment; missing and out-of-scope both return 404. This closes the known mobile quick-action ownership gap. State changes are POST/PATCH, never GET.

### Schema and compatibility

Expected schema work:

- extend `web_api_client` for mobile metadata and hashed credentials;
- add `mobile_enrollment_request`;
- add `action_mutation_undo` and, if needed, an idempotency result table.

Each change is a sequential `src/db/vN.M.sql` migration paired with model, `.hbm.xml`, and `hibernate.cfg.xml` updates. No action-domain schema change is required for reminders because those settings live on the phone. API DTOs are explicit and do not serialize Hibernate models. OpenAPI describes all endpoints, but the Android client uses reviewed DTOs rather than depending on runtime schema generation.

Rejected alternative: expose the existing servlet URLs or reuse browser cookies. That would preserve GET mutations, couple the app to HTML/session behavior, and make device revocation awkward. Rejected alternative: copy servlet queries into resources. It is quick initially but violates G-13 and guarantees drift.

## 8. Development setup and workflow

### One-time setup on Windows 11

Android Studio, SDK platform 36.1/images, emulators, and `adb` already exist. The repository supplies `android/gradlew.bat`, so no global Gradle installation or `ANDROID_HOME` is needed. Create an untracked `android/local.properties` containing:

```properties
sdk.dir=C:\\Users\\NathanBunker\\AppData\\Local\\Android\\Sdk
```

Use Android Studio's bundled JBR for Gradle by setting `org.gradle.java.home` in the user's Gradle properties if Java 25 proves incompatible; do not change the Maven Java configuration. Install no Node.js toolchain. Generate a release signing keystore once outside the repository, back it up securely, and expose its path/password only through ignored user Gradle properties or environment variables. Debug signing uses the standard local debug key.

### Command-line loop

From `android/`:

```powershell
.\gradlew.bat testDebug lintDebug assembleDebug
adb devices
.\gradlew.bat installDebug
adb shell am start -n org.dandeliondaily.relay.debug/.MainActivity
```

For instrumented tests:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Agents can run all of these. CI (or the local pre-push script) runs JVM tests, lint, and debug assembly; device tests run on the API 28 and API 36 emulators at milestone boundaries. Maven server tests remain independent (`mvn test` or focused `-Dtest=...`).

The `Pixel_7` API 36 emulator is the fast daily UI target. Add an API 28 AVD only when shared infrastructure/student compatibility needs testing. The S23 Ultra is used for ergonomic acceptance, notification behavior under Samsung battery management, lock-screen privacy, rotation/font scale, and final installs. Nathan enables Developer options and USB debugging, connects once over USB, accepts the RSA prompt, then may use Android Studio/ADB wireless debugging on the same network. Agents can drive `adb`; Nathan only unlocks, approves prompts, pairs wireless debugging, and judges feel.

### Local and production endpoints

Debug builds show an environment selector:

- emulator local: `http://10.0.2.2:8089/dandelion/api/v1/mobile/`;
- USB phone local: run `adb reverse tcp:8089 tcp:8089`, then use `http://127.0.0.1:8089/dandelion/api/v1/mobile/`;
- Wi-Fi phone local fallback: `http://<dev-machine-LAN-IP>:8089/...`, requiring Windows firewall/LAN access;
- production: `https://dandelion-daily.org/api/v1/mobile/`.

Only debug manifests permit cleartext local traffic, scoped by network security configuration. Release builds hard-code production HTTPS and do not expose environment switching. A banner makes non-production unmistakable. Local enrollment approves against the local database; production credentials are never sent to local by accident.

### Install and update

For MVP debug use, `gradlew installDebug` installs directly. For real daily use:

1. build `gradlew clean testRelease lintRelease assembleRelease` with the stable release key;
2. record the versionCode/versionName and SHA-256;
3. connect the S23 and run `adb install -r app/build/outputs/apk/release/app-release.apk`;
4. open Relay and smoke-test bootstrap, Today, complete/undo, capture, and a two-minute debug reminder before switching back to production schedule.

`-r` preserves app data and the device credential when the package ID and signing key are unchanged. Losing the signing key prevents in-place updates, hence the backup. If Nathan prefers tapping an APK, serve/copy the signed APK and allow “install unknown apps” for that source, but ADB is the primary agent-friendly path. No Play Store is involved.

### Test strategy

Server unit/integration tests cover workspace/user scoping, billable exclusion, ordering/grouping, date zones, completion/unblocking, exact undo, idempotency, enrollment expiry/approval/revocation, and servlet/service parity. API tests attempt cross-workspace IDs deliberately.

Android JVM tests cover DTO mapping, repositories, ViewModel state, move choices, date-only handling, reminder filters, weekly schedules, stale-work suppression, and auth error mapping. MockWebServer covers retry/idempotency and revoked-device responses. Compose tests cover the Today runway, large font, capture context, date picker, Undo, offline panel, and student root routing. Instrumented tests cover Keystore round-trip, notification permission, notification Done action, WorkManager execution, process death, and API 28 compatibility.

Manual S23 acceptance uses a short script for one-handed tap reach, sunlight/dark mode, notification grouping, Doze/battery optimization, phone reboot, network loss, and production install update. Accessibility checks include TalkBack labels, 200% font, 48dp targets, color contrast, and no gesture-only command.

## 9. Build order

Each step ends in something observable and keeps server/app work synchronized.

1. **Skeleton and contract.** Add `android/`, Gradle wrapper, Compose theme/navigation, environment handling, CI commands, and a fake-repository Today runway. Nathan can install an APK and judge the core interaction on the S23 before backend investment.
2. **Shared read services.** Extract `PersonalTodayService` and `PersonalProjectService`; switch `/m/todo` and `/m/project` reads to them, with parity tests. Nathan sees unchanged web behavior backed by testable shared logic.
3. **Enrollment and bootstrap.** Add device-request tables/endpoints, desktop Devices page, hashed mobile credential support, Keystore storage, and mode routing. Nathan can sign in, approve his S23, remain signed in, and revoke it.
4. **Read-only real Today.** Add day/project/waiting DTO endpoints and wire Relay. Nathan can use Relay as a trustworthy pocket view of real personal data and navigate days/projects.
5. **Complete, cancel, move, undo.** Extract mutation services, add ownership checks, idempotency, exact undo tokens, and visible controls. Nathan can manage the day safely; this is the first daily-use beta.
6. **Capture and detail.** Add explicit quick capture, project context, recent-item chip, notes/link/detail, and full edit. Nathan can capture during meetings and enrich later.
7. **Advanced action flows.** Share Schedule & Block and Complete & Next service paths with web, then expose them in More. Personal mobile baseline is functionally complete.
8. **Reminders.** Add settings matrix, WorkManager planning, notification actions, debug time controls, boot/time-zone rescheduling, and Samsung device tests. Nathan can tune start/end nudges during a one-week trial.
9. **Hardening and release.** Accessibility, error states, telemetry-free diagnostics export, security review, production release signing/install guide, and two weeks of side-by-side use with `/m/*`. The web pages remain available until Nathan declares replacement.
10. **Deliverable 2.** Add student read/mutation services shared with current student servlets, then School (including points/undo), Store (buy/return/status), and Bank. Test on API 28 and Note8. The existing auth/network/design shell is unchanged; reminders remain disabled for Student mode.

The order rejects a “build all APIs first” approach because Nathan should validate the runway early. It also rejects a UI-only prototype that cannot prove shared behavior: step 2 establishes the service seam before real data reaches the app.

## 10. Requirements coverage

### General requirements

| ID | Coverage |
|---|---|
| G-1 | One APK; bootstrap `workflowType` chooses isolated personal or student feature navigation over shared core. |
| G-2 | Kotlin/Compose native Android UI; no WebView or wrapped pages. |
| G-3 | Username/password enrollment against Dandelion; no user-visible API keys. |
| G-4 | Keystore-protected per-device credential persists until explicit revoke/remove. |
| G-5 | Enrollment remains data-less until Nathan approves the named device on desktop. |
| G-6 | Desktop Devices page disables the `WebApiClient`; the next call logs the app out with a revocation message. |
| G-7 | Exactly one credential/account per installation; no account switcher. |
| G-8 | Auth context supplies user/workspace; every ID lookup also constrains workspace and owner/assignee; out-of-scope is 404. |
| G-9 | Online-only; clear unreachable/stale state, Retry, and disabled mutations. No claimed offline writes. |
| G-10 | Complete, cancel, and move are immediate; 12-second UI Undo backed by 60-second server token; no confirmations. |
| G-11 | Signed APK installed by ADB on family devices; no store work. |
| G-12 | `minSdk 28`, `target/compileSdk 36`; optimized on S23 and compatible with Android 9 Note8. |
| G-13 | Extracted services are called by servlets and API; DTO/resources do not duplicate behavior. |
| G-14 | MVP enrollment, acceptance, and release process targets Nathan's S23 only. |

### Personal requirements

| ID | Coverage |
|---|---|
| P-1 | Today is the default root; selected Today response contains due-today plus a visible overdue rail. |
| P-2 | Wake/Morning/Afternoon/Evening rails; current block expands into Now + On deck. |
| P-3 | Previous/next day and calendar jump; other days show only their date. |
| P-4 | Dedicated Waiting destination, age, follow-up actions, and quiet Today pulse. |
| P-5 | Open personal project list and Overdue/Due today/Due later/Unscheduled detail groups. |
| P-6 | One-tap Done through shared completion logic; response returns newly unblocked work. |
| P-7 | Cancel under More, immediate and undoable. |
| P-8 | Move sheet has other blocks, Tomorrow, and next five dates as one-tap choices. |
| P-9 | Material date picker plus block supports any future date, including months ahead. |
| P-10 | Server-authoritative short undo for complete/cancel/move; no confirmation. |
| P-11 | Action detail displays full notes. |
| P-12 | Add note appends via `ActionNotesService`; existing-note editing omitted as allowed. |
| P-13 | Validated `http(s)` link opens through Android intent. |
| P-14 | Edit supports project, description, type, date, block, link, and assignee. |
| P-15 | Schedule & Block creates same-project blocking action and clears/blocks current through shared service. |
| P-16 | Complete & Next atomically completes and sentence-parses a dated follow-up. |
| P-17 | Capture sheet requires only description plus explicit when. |
| P-18 | Capture inherits project when launched within a project. |
| P-19 | Recent-first searchable project picker elsewhere. |
| P-20 | “Added · Add details” chip and recent row reopen the captured action for notes/link/type/exact block. |
| P-21 | End worker checks remaining matching items and sends one grouped block nudge. |
| P-22 | Optional block-start summary with count and overdue context. |
| P-23 | Phone settings matrix controls times, project/type filters, and weekdays; not per action. |
| P-24 | Rules are layered on Dandelion blocks and remain local to DataStore; desktop data is unchanged. |

### Development requirements

| ID | Coverage |
|---|---|
| D-1 | Uses installed Studio/SDK; repository Gradle wrapper and `local.properties`; bundled JBR if needed; stable external signing key. |
| D-2 | CLI unit/lint/build loop, API 36 emulator daily, API 28 compatibility, S23 ergonomic/notification acceptance. |
| D-3 | Signed release APK and `adb install -r`; stable package/signing identity preserves data across updates. |
| D-4 | `10.0.2.2` for emulator, `adb reverse`/LAN for phone, production HTTPS fixed in release. |
| D-5 | Wrapper, Maven, ADB, deterministic tests, debug reminder controls, and explicit commands are agent-runnable; Nathan handles physical prompts/judgment. |

### Student requirements

S-1 through S-16 fit a later `feature/student` graph in the same APK: School supplies today/other-day grouped Required/Assigned/Optional/Chores with points and retained final states; completion/brief undo uses the shared mutation system and credits the completion day; editing stays absent; Store exposes balance, offers, affordability, purchase, return, and delivery state; Bank shows both balances and transfers. Shared auth solves persistent login. No student reminders are scheduled. Purchase Undo remains **not proposed**: S-13 already provides return, and avoiding an email/undo race is simpler and honest; Return should be immediately visible after purchase.

## 11. Risks and open questions

| Risk / question | Mitigation or decision needed |
|---|---|
| The runway may overemphasize the first server-ordered item even though Dandelion has no precise priority plan. | Label it “Now,” keep all On deck items visible, make promotion one tap and local, and usability-test the fake-data APK in step 1. Nathan decides after three days whether the large card helps. |
| Exact undo of completion can be complex because completion may unblock actions or affect shared siblings. | Snapshot all affected rows/relationships transactionally, reject undo after a later change, and build service tests before UI. Do not pretend `ActionRecoveryService` is sufficient. |
| Existing password verification may be tightly coupled to servlet sessions. | Extract only credential verification/session-independent user lookup, rate-limit it, and security-review before production enrollment. |
| Samsung may delay WorkManager more than expected. | The product promises block-level, not minute-exact reminders; test Doze on S23. If real delay is unacceptable, Nathan may opt into exact alarms later with explicit justification. |
| `minSdk 28` could constrain a future library version. | Pin supported AndroidX versions, run API 28 CI/emulator tests, and avoid optional modern-only dependencies. |
| Local and production endpoints could be confused. | Debug-only chooser, large environment banner, separate application ID suffix, and production-only fixed release endpoint. |
| Hashed mobile credentials coexist with existing raw API keys. | Put lookup behind one auth service, mark credential type, add tests for both, and migrate old clients separately if desired. |
| Notification Done while the phone is offline may surprise. | Show pending state, use one idempotent worker, and surface failure; do not optimistically remove the server item from a notification. |
| The requirements do not state default block clock times. | Nathan must confirm onboarding defaults. They remain phone-local and editable per day group. |
| “Waiting on someone” may lack a reliable waiting-since date/person in all legacy rows. | Derive age from best available action/change date and label uncertain data conservatively; inspect production-shaped local records before final DTO. |
| Server refactoring could change web behavior. | Characterization tests around current queries and actions, then servlet parity tests and side-by-side deployment. |
| Release signing loss would block updates. | Create once, back up securely, document alias and recovery location without committing secrets. |

Open decisions for Nathan before build: confirm the tentative reminder times; choose whether lock-screen descriptions are visible (default proposed: hidden until unlocked); validate the runway hierarchy on the prototype; choose a display name for the approved device; and decide whether a Waiting pulse belongs in Evening or only on the Waiting tab.

## 12. Why this one

All three pitches correctly center Dandelion's four time blocks, fast movement, waiting work, and humane reminders. The differences are where each places its strongest bet.

**Sundial** offers the clearest ritual: a full-screen, gesture-driven Sweep closes each pass, reinforced by a widget. That may be delightful for rapid triage, but it asks Nathan to learn swipe semantics and makes end-of-block cleanup the dominant moment. **Seedclock** is the boldest ambient design: one living notification makes the lock screen the primary interface. That minimizes opening the app, but it permanently occupies system space and makes Android notification constraints central to the product.

**Relay is app-first and tap-first.** Its signature is the runway: one visually prominent action, the rest still visible, and the same Done/Move/More controls everywhere. It helps not only at block boundaries but every time Nathan glances at the phone. Its notifications are replaceable invitations into that stable interface, not the interface itself. There are no hidden required gestures, permanent notification, widget synchronization, or extra sweep mode in the MVP.

That restraint matters for one developer plus agents. Relay still takes authentication, shared server logic, undo, and reminders seriously—the hard parts required by the contract—but spends its UI complexity on one coherent surface. If Nathan wants a phone app that feels calmer than the current pages without surrendering visibility or control, Relay is the strongest base.
