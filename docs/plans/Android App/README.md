## Plan: Dandelion Android app

Status: **Step 4 done: the design is Dandelion Sundial** (`Sundial Plan.md`). No code yet.

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
| `Proposal Brief.md` | What every solution proposal must cover |
| `Contest.md` | Contest rules: phases, file layout, the HTML pitch page, and judging |
| `Sundial Plan.md` | The chosen design: Seedclock's proposal, renamed Sundial, with Nathan's changes |
| `contest/<contestant-id>/` | Each contestant's `pitch.md`, `proposal.md` and `index.html` |

### How these docs are used

These docs record **what the MVP must be able to do**, not how to build it. Solutions come from a
contest (`Contest.md`): several agents each pitch and then propose an app against these
requirements, and Nathan picks one or combines them. Requirements have IDs so a proposal can say
which ones it meets and how.

### Process

1. Document current behavior. *(done)*
2. **Gather requirements** with Nathan. *(done)*
3. **The contest** (`Contest.md`). Contestants pitch blind, then study each other's pitches and
   write full proposals with an HTML pitch page. Nathan and a Claude judge thread score them.
   *(done)*
4. **Choose a design** and record the decision here. *(done: `Sundial Plan.md`)*
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
- **2026-11-11: The design is Dandelion Sundial.** Seedclock's proposal (claude-2) is the base,
  renamed Sundial. From Relay: idempotency keys, reuse of `ActionCompletionService`, and
  characterization tests. Details and changes: `Sundial Plan.md`.
- **2026-11-11: Never show work actions.** Personal mode shows only personal (non-billable)
  actions, enforced on the server. Showing work would be a separate feature, not an MVP gap.
- **2026-11-11: No cap and no ranking.** The card lists the current part of the day in the app's
  order. Nathan doesn't rank personal actions, deliberately.
- **2026-11-11: Move with labeled buttons, not a dial gesture.** Quick-move chips plus a date
  picker. Sundial's Dial was judged too fiddly.
- **2026-11-11: Lock-screen limits are acceptable.** If Samsung needs an unlock before a ✔ runs,
  the card still does its job of getting Nathan engaged.
- **2026-11-11: Up to ~15 minutes behind the desktop is fine,** with a manual refresh.
- **2026-11-11: The parts of the day are meanings, not clock times.** Morning means "during the
  working day", Afternoon means after clocking out, Evening means getting ready for bed. Each user
  sets the start times on the phone (P-25). Personal Morning actions also show on the desktop
  dashboard during work.
- **2026-11-11: Few reminders; the screen changes instead.** The card switches silently at each
  part of the day (Nathan: 4am, 8am, 12pm, 8pm). One check an hour before each switch (7am, 11am,
  7pm) alerts only if items are still open. None for Evening. No start prompts (P-20a, P-21; P-22
  dropped).
- **2026-11-11: Develop against real data,** local (a copy of production) and then production. No
  fake-data prototype.

### Known issues in the current web pages

- **Missing ownership check on quick actions.** `TodoServlet` and `ProjectServlet` complete,
  cancel, or reschedule an action by `actionId` without checking that it belongs to the signed-in
  user's workspace. This was deferred on 2026-11-11.
- **State changes over GET.** Complete, cancel, and reschedule are plain links (GET).

### Not in the MVP

- Work (billable) actions in personal mode. Never planned for this app; see Decisions.
- A parent view of the student's progress in the app. Nathan uses the desktop for that.
- Guardian features in the app: managing offers, delivery status, school templates.
- Offline use, and installs for users outside the family.
- Time tracking, Plan Ahead, focus mode, daily review, and narratives.

