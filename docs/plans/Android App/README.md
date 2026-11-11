## Plan: Dandelion Android app

Status: **Step 1, documenting what the app should do.** No code yet.

Nathan wants a native Android app for the parts of Dandelion he uses away from a desk. Today those
parts are the server-rendered mobile pages (`/m/*`) and the student pages (`/student/*`). The app
replaces them on the phone. The desktop web app is unchanged.

There are two audiences:

1. **Personal (Nathan).** See what he needs to do today, mark it off, push it to another day, and
   capture new actions. Personal projects only. See `Personal App.md`.
2. **Student (Nathan's daughter).** See the day's school work and chores, mark them done, earn
   points, and spend or save them. See `Student App.md`.

Work (billable) projects are **not in the MVP**. The design should leave room to add them later.

### Documents in this folder

| File | What it holds |
|---|---|
| `README.md` | Goals, scope, process, architecture questions, and decisions |
| `Current Mobile Pages.md` | Inventory of today's `/m/*` and `/student/*` pages. This is the baseline the app has to match or replace. |
| `Personal App.md` | Personal app screens and behavior for the MVP |
| `Student App.md` | Student app screens and behavior for the MVP |

### Process

Each step ends with a doc update checked into this folder.

1. **Document current behavior and draft the MVP scope** (this step). Inventory the mobile pages,
   then write a first cut of what each app mode does.
2. **Settle the open questions** below with Nathan, and record each answer under "Decisions".
3. **API design.** Specify the JSON endpoints the app needs (see "Server work" below). Following the
   project convention, the API and the web pages should call the same services so their behavior
   can't drift.
4. **Build the server side.** Extract the mobile/student logic into services, then add the
   endpoints and app authentication. Deploy, and verify the endpoints with curl before writing any
   app code.
5. **Build the app.** Personal mode first (Nathan is the tester), then student mode.
6. **Side-load and use it.** Run it alongside the web mobile pages until it can replace them.
7. **Decide on distribution** (side-load, Play Store internal testing, or a public listing).

### Architecture questions (open)

These need a decision before step 3. Each has a recommendation.

1. **One app or two?** *Recommend one app.* The signed-in user's `workflowType` decides the mode,
   the same way `LoginServlet` sends `STUDENT` users to `student/school` today. One codebase, one
   install, and shared login, API client, and action list code.
2. **Native or wrapped web?** *Recommend native Kotlin + Jetpack Compose.* The alternative is a
   Trusted Web Activity or WebView wrapping the existing pages. That would be faster, but it gives
   little that the mobile web pages don't already do. A native app makes room for notifications,
   home-screen widgets, and offline use later.
3. **Authentication.** The REST API and MCP use per-client `X-Api-Key` keys. A person creates each
   key by hand under Settings -> Web API Client Keys, and each key is tied to a user and a
   workspace. That works for an AI client. A child can't be expected to paste a key into a phone.
   *Recommend:* the app signs in with username/password or the existing magic-link email. The
   server then mints a per-device key on the same infrastructure, and the app stores it in Android
   encrypted storage. Revoking the device revokes the key.
4. **Offline.** *Recommend online-only for the MVP*, with a clear "can't reach Dandelion" state.
   Marking items done offline and syncing later is a good phase-2 feature for the student app,
   since tablets are often off Wi-Fi.
5. **Notifications.** Not in the MVP. A likely phase-2 feature ("3 things left today", "you earned
   20 points").

### Server work implied (to be detailed in step 3)

The existing REST API (`/api/v1/projects`, `/api/v1/actions`, proposals, narratives) is built for
AI proposals and review. It has no "today's todo list", complete, or reschedule endpoints. The MCP
tools cover more (`apply_changes`, `get_planning_context`), but they are shaped for an AI client,
not a phone UI. The app will need new endpoints, roughly:

- `GET  /v1/me`: who am I, `workflowType`, time zone, today's date in the user's zone
- `GET  /v1/todo?date=`: the personal todo list for a day (overdue plus time-slot groups)
- `GET  /v1/projects?scope=personal` and `GET /v1/projects/{id}/open-actions`
- `GET  /v1/actions/{id}`: action detail, including notes and link
- `POST /v1/actions`: create; `PATCH /v1/actions/{id}`: edit
- `POST /v1/actions/{id}/complete | cancel | reschedule | block | complete-and-next`
- `POST /v1/actions/{id}/notes`: append a note
- `GET  /v1/student/day?date=` and `POST /v1/student/actions/{id}/complete`
- `GET  /v1/student/store`, `POST /v1/student/offers/{id}/buy | return`
- `GET  /v1/student/bank`, `POST /v1/student/bank/transfer`
- `GET  /v1/student/offers/{id}/image` (or a signed URL to the existing image servlet)

Most of this logic lives in the servlet classes today (see `Current Mobile Pages.md`). Step 4
starts by moving it into services that both the servlets and the API call.

### Issues found while documenting

These are problems in the current web pages. Fix them when the logic moves into services, or
earlier:

- **Missing ownership check on quick actions.** `TodoServlet` and `ProjectServlet` load the action
  by `actionId` and complete, cancel, or reschedule it without checking that it belongs to the
  signed-in user's workspace. `ActionServlet`'s summary view and `StudentSchoolServlet` do check.
  The API must check ownership on every write.
- **State changes over GET.** Complete, cancel, and reschedule are plain links (GET). That's fine
  for the web pages, but the API should use POST.
- **Student daily points total uses the ledger date, not the action date.** Completing yesterday's
  task today counts toward today's footer total. Decide whether that's intended.

### Decisions

(None yet. Record each settled question here with the date.)

### Later (not MVP)

- Work (billable) projects in personal mode, probably as a Personal/Work toggle. The mobile pages
  already hard-code `isShowWork = false` / `isShowPersonal = true`.
- Guardian features in the app: approving purchases, marking offers delivered, editing school
  templates. These stay on the desktop web for now.
- Time tracking, Plan Ahead, focus mode, and daily review.
- Widgets, notifications, and offline sync.
