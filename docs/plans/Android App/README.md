## Plan: Dandelion Android app

Status: **Step 2, gathering requirements.** No code yet.

Nathan wants a native Android app for the parts of Dandelion he uses away from a desk. Today those
parts are the server-rendered mobile pages (`/m/*`) and the student pages (`/student/*`). The app
replaces them on the phone. The desktop web app is unchanged.

There are two audiences, served by one app and delivered in order:

1. **Deliverable 1, the MVP: personal mode (Nathan)** on his **Samsung Galaxy S23 Ultra**. See
   what he needs to do, mark it off, move it, capture new actions, and get reminded at the right
   times. Personal projects only. See `Personal App.md`.
2. **Deliverable 2: student mode (Nathan's daughter)**, the same software, on a **Samsung Galaxy
   Note8**. See the day's school work and chores, mark them done, earn points, and spend or save
   them. The student web pages work well enough until then. See `Student App.md`.

Solution proposals target deliverable 1. They must also show how deliverable 2 fits into the same
app without rework.

### Documents in this folder

| File | What it holds |
|---|---|
| `README.md` | Goals, process, requirements that apply to both modes, and decisions |
| `Current Mobile Pages.md` | Inventory of today's `/m/*` and `/student/*` pages. This is the baseline behavior. |
| `Personal App.md` | Personal mode requirements (P-*) |
| `Student App.md` | Student mode requirements (S-*) |
| `Proposal Brief.md` | Instructions given to each agent writing a solution proposal |

### How these docs are used

These docs record **what the MVP must be able to do**, not how to build it. Two agents will each
write a competing solution proposal against these requirements, and Nathan will pick from or
combine them. Requirements have IDs so a proposal can say which ones it meets and how.

### Process

1. Document current behavior. *(done)*
2. **Gather requirements** with Nathan. *(in progress)*
3. **Competing solution proposals.** Two agents each propose the interaction design, server/API
   changes, app architecture, and a build order against these requirements.
4. **Choose a design** and record the decision here.
5. **Build deliverable 1** (server side, then the app) and use it alongside the mobile web pages
   until it can replace them.
6. **Build deliverable 2** (student mode).

### Requirements for both modes (G-*)

- **G-1 One app.** One Android app. The signed-in account's type (`workflowType`) decides whether
  it runs in personal or student mode.
- **G-2 Native.** A true native Android app, not a wrapper around the web pages.
- **G-3 Sign in with Dandelion credentials.** Users sign in with their Dandelion account (password
  or email link). They never handle API keys.
- **G-4 Stay signed in.** Once a device is signed in, it stays signed in. Being logged out is the
  daughter's main complaint about the current pages.
- **G-5 Approve new devices.** A newly signed-in device doesn't work until Nathan approves it.
- **G-6 Revoke devices.** Nathan can revoke a device's access.
- **G-7 One account per device.** Each installed app runs under a single account. No account
  switching is needed.
- **G-8 Own data only.** Every request is limited to the signed-in user's own data.
- **G-9 Online only.** The app may require a connection. It must clearly show when it can't reach
  Dandelion.
- **G-10 Undo, not confirmations.** Changes such as complete, cancel and move happen immediately and
  can be undone for a short time afterwards. The app should not ask "are you sure?".
- **G-11 Family devices only.** The MVP only has to install on the family's devices. It doesn't
  need a public store listing.
- **G-12 Target devices.** Deliverable 1 must run well on Nathan's **Samsung Galaxy S23 Ultra**
  (current Android). This is the top priority. Deliverable 2 is meant for a **Galaxy Note8**
  (last official update: Android 9). Android 9 support is a **preference, not a hard rule**. A
  proposal may choose a newer minimum version if that gets Nathan something useful. It must say
  what the newer version buys and what it would mean for the Note8 (another device, a later
  student build, or a different approach for student mode).
- **G-13 Same behavior as the web.** The web pages and the app must share the same server logic so
  their behavior can't drift (a project convention).
- **G-14 MVP installs on one phone.** For the MVP, the only install is Nathan's S23 Ultra. No store
  listing, no other users.

### Development requirements (D-*)

- **D-1 Explain the toolchain.** What tools Nathan needs and how they're set up on his Windows 11
  machine. What's already installed is listed in `Proposal Brief.md`.
- **D-2 Explain the build and test loop.** How the app is built, run and tested day to day:
  emulator, the real phone over USB or Wi-Fi, or both, and when each is the right choice.
- **D-3 Explain installing and updating on the phone.** How builds get onto the S23 Ultra and how
  updates are installed afterwards.
- **D-4 Local and production servers.** How the app is pointed at the local Dandelion
  (`localhost:8089` on the dev machine) for development, and at production for real use.
- **D-5 Agent-friendly.** Most code will be written by AI coding agents (Claude Code, Codex)
  working in this repository. The build, install and test steps should be things an agent can run
  from the command line, with Nathan only doing what needs his hands, such as plugging in the
  phone or approving a prompt.

### Decisions

- **2026-11-11: Planning gathers requirements, not solutions.** Keep UI mechanics, API shapes and
  data design out of these docs, except where a requirement depends on them.
- **2026-11-11: One app, not two** (G-1).
- **2026-11-11: Native Android app** (G-2).
- **2026-11-11: Sign in on the device** with Dandelion credentials. Each device gets its own
  access, which can be revoked (G-3, G-6).
- **2026-11-11: No offline support in the MVP** (G-9). It may be needed in the distant future.
- **2026-11-11: Undo instead of confirmations** (G-10).
- **2026-11-11: The security gap stays as is for now.** The gap on the mobile Todo and Project
  pages (see "Known issues") won't be fixed separately; the focus is the new app. G-8 covers it
  for the app.
- **2026-11-11: Personal mode is the MVP.** It targets the Galaxy S23 Ultra. Student mode is a
  second deliverable in the same app, targeting the Galaxy Note8. The student web pages are good
  enough until then.
- **2026-11-11: The S23 Ultra comes first.** Android 9 / Note8 support is a preference, not a
  hard limit (G-12). Proposals may argue for a newer minimum Android version.
- **2026-11-11: Reminders are planned on the phone.** Dandelion only records each action's project,
  type, and general time of day. It deliberately doesn't plan the day in more detail. The app
  decides when to alert, using settings Nathan controls on the phone (P-21 to P-24).

### Known issues in the current web pages

- **Missing ownership check on quick actions.** `TodoServlet` and `ProjectServlet` complete,
  cancel, or reschedule an action by `actionId` without checking that it belongs to the signed-in
  user's workspace. This was deferred on 2026-11-11.
- **State changes over GET.** Complete, cancel, and reschedule are plain links (GET).

### Not in the MVP

- Work (billable) projects in personal mode.
- A parent view of the student's progress in the app. Nathan uses the desktop for that.
- Guardian features in the app: managing offers, delivery status, school templates.
- Offline use, and installs for users outside the family.
- Time tracking, Plan Ahead, focus mode, daily review, and narratives.

