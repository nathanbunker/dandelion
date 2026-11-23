## Plan: working sessions on actions, and agent actions

Nathan wants to go from "this is my next action" to working on it in one click: the project's
folder open, and a Claude or Codex session running there — either new, or resumed from where he
stopped. He also wants some actions done by an agent before his day starts (gathering, compiling,
drafting), with a review action waiting for him that resumes the agent's session.

Most of this is Dandelion work. A smaller part is setup on Nathan's Windows PC, listed at the end.

Written 2026-11-20 from a design conversation with Nathan. Everything under "Principles" and
"Decisions" is settled. Related plan: `Project Captures.md` (the project inbox).

---

### Version 1 scope (settled)

- **Two agents only: Claude Code (`claude-code`) and Codex (`codex`).** The agent list and
  capabilities are still built so more can be added later, but nothing else is built now. Cowork is
  documented in the agent reference for later; it is not part of version 1.
- **Agent actions (scheduled, overnight) run on Claude Code only.**
- **Codex is interactive only:** New, Start on this action, and Resume, on any action. Accept its
  known limitations; don't engineer around them. No Codex runner, no headless Codex.
- **The buttons go on the Focused Action page**, in the button row on the action (see phase B).

---

### Principles (settled)

These are how Nathan thinks about Dandelion. Every part of this plan must keep to them.

1. **An action is the smallest unit of work:** one step on a project. Sit down, do it, close it,
   create what comes next. Actions are not milestones or Gantt-chart items.
2. **A project is a series of actions replacing earlier ones.** Completing an action does **not**
   mean its original intent was fulfilled. It means the work done is recorded and new actions have
   been created to carry on. Close early and often. Incremental work on an open action is allowed,
   but closing and creating new actions should be the easy path.
3. **Actions are scheduled by day, never by time of day.** The user's chosen time zone decides which
   day "today" is. Dandelion organizes actions for each day. It is not a scheduling engine, and it
   does not replace systems that do up-to-the-minute or fully automatic work.
4. **Humans and agents use actions the same way** and read each other's work in the same places:
   - **The action's notes** (`nextNotes`, the notepad) are scratch space for before and during the
     work: the brief, what to do, progress so far. Reports do not read them.
   - **The completion** (`ActionTaken`: `completion description` + `outcomeNote`, the same as
     `complete_action` in `apply_changes`) is the record of what was done. Reports read this. Every
     completion needs one, even when progress notes were written on the action along the way.
5. **Agents never record time.** Completing or parking an action from an agent session sends no
   `durationMinutes`. Time is Nathan's attention, tracked by the web app.
6. **Keep actions simple.** Add as little to the action as possible. Put things on the project
   (folders) or on the PC (launching, running) instead.

---

### Decisions (settled)

**Project folders**
- A project has **one primary folder** and **zero or more related folders**.
- Paths are stored relative to known roots, not as full paths: `brain:` (`C:\brain`), `dropbox:`
  (`C:\dropbox`), `dev:` (`C:\dev`). The roots are resolved on the PC (see local setup), so the data
  survives a Dropbox move or a second machine.

**Agents (the software that does the work)**
- Claude Code, Codex, and later others (for example Claude Cowork) are **agents**. The **model** is
  an optional setting inside an agent (Claude Code runs Claude models, Codex runs OpenAI models).
- Dandelion keeps an **agent list** that can grow: a key (`claude-code`, `codex`, `cowork`), a
  display name, an integration style, and capabilities (below). Adding an agent means adding a row,
  not changing code.
- **User preferences:** a default agent, plus an optional default model for each agent. The Start
  and New buttons use the default and offer a chooser for the others.
- Dandelion stores **only agent keys**, never command lines. The commands for each key live in the
  PC's config (see local setup). This is a security boundary, and it's what keeps new agents cheap
  to add.
- **Capabilities** each agent declares; the buttons adapt to them:

  | Capability | Meaning | If missing |
  |---|---|---|
  | `new` | Open a new session in the folder | — |
  | `start-with-prompt` | Launch with a starting instruction | **Clipboard fallback:** copy the "start on action #123" prompt and open the app |
  | `session-id-at-launch` | The launcher can choose the session ID up front | The launcher finds the ID after the session ends |
  | `resume-by-id` | Reopen a past session by ID | Resume is hidden; notes are how you pick up |
  | `headless` | Can run unattended (agent actions) | Not offered for agent actions launched by the runner |
  | `pull` | Can check Dandelion itself for work assigned to it, on its own schedule | — |

- **Two integration styles:**
  - **Launched** (Claude Code, Codex): the PC starts the agent with a prompt, in the folder, and
    records its session. This is what the rest of this plan describes.
  - **Pull** (Cowork and other app-based agents): the agent can't be launched from outside, so it
    asks Dandelion, through the MCP, "what is assigned to me today?" Nathan starts it by hand (with
    the clipboard prompt), or one of its own scheduled tasks runs the check. Dandelion still decides
    *what* is done each day; the agent only decides *when* it looks.
- **Assignment to agents is not the assignee.** The existing assignee stays for human Dandelion
  users. Agents are not Dandelion users. An action gets a separate, optional **"performed by
  agent"** field holding an agent key. Empty means a person does it. Recurring templates carry it.

**Sessions on actions**
- An action can carry a **session link**: the tool (`claude` or `codex`), the session ID, the
  folder it ran in (relative to a root), and when it was saved. Resuming must run in the same folder
  the session started in, so the folder is required.
- One current session per action is enough. Older session IDs can be listed in the notes if needed.
- **Resume is offered for 30 days** after the session was saved, then hidden. If the session is gone
  sooner, resume fails and the notes are how you pick up. Don't over-engineer this.

**Starting work on an action** (one-click options in Dandelion, PC only)
- Open primary folder (and the related folders)
- Terminal in folder
- New Claude session / New Codex session: blank, in the folder
- **Start Claude on this action / Start Codex on this action:** a new session told to read this
  action, its notes, and the chain of actions it follows (`followsActionNextId`), then begin
- **Resume:** shown when a session link exists and is under 30 days old. Reopens the right tool.

**Stopping work** (three ways, from fastest to best)

| | Bail | Park | Close out (preferred) |
|---|---|---|---|
| Time | Seconds | About a minute | A few minutes |
| What happens | Session link saved; a 2–3 line "where I was" written to the notes without asking anything | Session link saved; a short handoff written to the notes; action moved to tomorrow (or a chosen day) | Action **completed** with a completion description and outcome note; next action(s) created with briefs in their notes; other open actions on the project reviewed |
| Action | Stays open | Stays open | Completed |

- **Automatic fallback:** if a session ends without any of these (the window was just closed), the
  session link is still saved to the action. Any action worked in a session can always be picked
  up. For Claude Code this is nearly free: the launcher chooses the session ID with `--session-id`
  and records it on the action **when the session starts**, so even a crash leaves a working Resume.
  For Codex, the launcher waits for the process to exit and then records the newest session for
  that folder.
- A bailed or parked action that is resumed should start by offering to finish it or close it out
  properly, so quick stops don't quietly turn into long-running actions.

**Close out looks at the whole project, not just the chain**
- Finishing one action often shows that other open actions on the project are no longer right. Close
  out must show the project's other open actions and help to **rewrite, merge, reschedule or cancel**
  them, as well as create the next ones. This is the hardest part for Nathan, and the most valuable.
- **Agents always create a next action,** because their work must be reviewed.
- **Humans may close with no next action,** and this must be possible. But close out expects and
  proposes one by default, even if it's only "check back on this in a month".
- New actions link back with `followsActionNextId`, so a fresh session can walk the chain of earlier
  completions.

**Agent actions** (work done before Nathan's day starts)
- There is no special "prep" feature. **An agent does its own action**, just like a person:
  1. The agent action, for example "Prepare daily triage", is scheduled for a day and assigned to an
     agent. Its notes, usually from a recurring template, hold the instructions, including what
     review action to create.
  2. The runner starts it. The agent works in the project's primary folder, then **closes out**: it
     completes its action (outcome note, no time) and creates the review action for Nathan on the
     same day. The review action's notes hold the summary, and its session link points to the
     agent's session.
  3. Nathan opens the review action and clicks **Resume**. He's in the session that did the
     gathering, ready for his decisions.
- **Eligible from midnight** on the action's scheduled day, in the user's Dandelion time zone. Daily
  granularity only: no times, no lead times.
- **Up to 3 run at once.** There's plenty of time overnight.
- **Agents never act outward on their own.** An agent action may read, draft, and write files in its
  folder, and create its own completion and next action. Sending, trashing, archiving and changing
  Nathan's other actions wait for the review session, where Nathan confirms.
- **If the agent fails,** the runner itself (a plain script, not the model) leaves the agent action
  open with the error in its notes and creates a short action for Nathan ("Daily triage prep failed —
  see notes"). A failure must never be silent.
- Example uses: daily triage, weekly triage, the weekly report draft, meeting research the day
  before a meeting (then a review action, then the meeting the next day).

---

### Dandelion work

Order is a suggestion. Each phase deployable on its own; bump `pom.xml`, migrations in `src/db/`.

**Phase A: data model and MCP/API**
- **Project folders:** a primary folder and a list of related folders (`root` + relative path, and
  an optional label). Add them to `get_project_context` and project editing (web form plus MCP).
  Validate that the root is one of the known roots.
- **Action session link:** tool, session ID, root + relative folder, saved-at. Set and clear it
  through `apply_changes` (a new operation or a field on `update_action`). Return it in the action
  reads. Include an "age" or "resumable" flag (under 30 days).
- **Agent list and preferences:** a table of agents (key, display name, integration style,
  capabilities, active flag), seeded with `claude-code` and `codex`. Per-user default agent and
  optional default model per agent. Readable through MCP/API so the PC launcher and the web app use
  the same list.
- **"Performed by agent" on actions and templates:** an optional agent key. It is separate from the
  assignee, which stays for human users. Include it in reads, `apply_changes`, and template
  generation (`TemplateGenerationService`).
- **Pull support:** an MCP read such as `list_agent_work(agentKey)`: today's open actions performed
  by that agent, with notes and project folder. Same claim and failure rules as the runner.
- **Runner endpoints** for the PC (API key, not a device key):
  - list agent actions that are eligible now (scheduled today or earlier in the user's time zone,
    open, assigned to an agent, not already claimed);
  - **claim** an action (so two runners can't start the same one) and release it on failure;
  - record a failure (note on the action, plus the "prep failed" action for the user).
- **Links on actions:** `linkUrl` currently accepts only `http`/`https`
  (`McpApplyChangesService`, around line 1130). The one-click buttons are built from data instead
  (project folder, session link, action ID), so `linkUrl` doesn't need to change. Note the
  decision either way.
- **Time:** make sure the completion path used by agents never needs `durationMinutes`.

**Phase B: buttons on the Focused Action page**
- **Where:** the Focused Action page (`FocusedActionServlet`, HTML in
  `focus/render/FocusedActionPageRenderer.java`). The action's title card already has a row of small
  buttons (`fa-small-btn`: Reschedule, Edit Action, This project, around lines 385–396). There is
  room there; add the new buttons to that row in the same style. Follow
  `docs/plans/Focused Action Redesign.md`, and don't change the parts it marks "Do not change"
  (switch bar, Quick Capture, work form, automatic next-action selection).
- **Buttons:**
  - **Folder:** opens the project's primary folder. A small menu lists the related folders.
  - **Terminal:** opens a terminal in the primary folder.
  - **Start:** starts the user's default agent on this action. A small menu offers the other agent
    and the blank "New session" variants.
  - **Resume:** shown only when a session link exists and is under 30 days old. Shows the agent and
    age ("Resume Claude · 2d").
  - Each button is a `dandelion:` link (see local setup) carrying **only IDs**, never paths or
    commands.
  - Hide or disable buttons that don't apply: no folder set, or no session.
- **Agent actions:** on an action performed by an agent, show which agent, and a failure state if
  the run failed. On the review action, Resume is the main button.
- **Relationship to the wrap-up form:** the page already has "Save progress" (logs work and keeps
  the action open) and "Done". Bail and park should use the same progress path, so their notes show
  in the action's History, rather than inventing a second way to save progress.
- Other pages (dashboard, Plan Ahead) can link to the Focused Action page rather than repeat the
  buttons.
- Sundial: none of this applies on the phone for now. The buttons are Windows-only.

**Phase C: close-out support**
- A close-out view: the action being completed (completion description and outcome note), plus
  **all other open actions on the project**, with quick rewrite, merge, reschedule and cancel, plus
  create the next action(s) with notes and a `followsActionNextId` link.
- The same thing available to agents through MCP as one atomic batch (`apply_changes` already
  supports complete with `followUp`, create, update, reschedule and remove). Mostly this means making
  sure an agent can do the whole close-out in one call.
- Default proposal: a next action. "No next action" is allowed for humans.

---

### Local PC setup (Nathan's Windows machine)

Not Dandelion code. Lives in `C:\brain\scripts\` with docs, like the existing hourly runner.

1. **Root map and agent commands:** a small config mapping `brain:` → `C:\brain`, `dropbox:` →
   `C:\dropbox`, `dev:` → `C:\dev`, and each agent key to its commands (new, start with prompt,
   resume, headless, model flag). Starting points from the agent reference below:
   - `claude-code`: new `claude`; start `claude --session-id <uuid> --name "<action>" "<prompt>"`;
     resume `claude --resume <id>`; headless `claude -p "<prompt>" --session-id <uuid>
     --output-format json` plus permission flags; model `--model`; optionally `--remote-control` so
     any launched session can be picked up from the phone.
   - `codex`: new `codex -C <folder>`; start `codex -C <folder> "<prompt>"`; resume
     `codex resume <id>`; model `-m`. (Headless `codex exec` is not used in version 1.)
2. **`dandelion:` link handler:** register a URL protocol in `HKCU\Software\Classes\dandelion` that
   runs a PowerShell script. The script:
   - accepts only known verbs (`open-folder`, `terminal`, `new`, `start`, `resume`) and numeric IDs
     or a strict UUID; it rejects anything else;
   - looks up the folder, tool and session ID **from the Dandelion API** using the ID, never from
     the link;
   - opens only folders under the known roots;
   - opens File Explorer, or Windows Terminal in the folder running `claude`, `codex`, or the right
     resume command;
   - for `start` and `resume`, passes the action ID to the session (an environment variable) so bail,
     park and the fallback know which action they belong to.
3. **Claude Code and Codex commands:** `/bail`, `/park`, `/close-out`, and the "start on action"
   instruction, for both tools where possible. They write through the Dandelion MCP.
4. **Fallback hook:** a session-end hook in Claude Code (and the Codex equivalent, if there is one)
   that saves the session link to the action from the environment variable, or to the current
   started action if there isn't one.
5. **Session retention:** check how long Claude Code and Codex keep sessions, and set it to at least
   30 days.
6. **Agent runner:** a Task Scheduler job (shortly after midnight, then every 15–30 minutes until
   the queue is empty, and catching up after sleep). **Claude Code only in version 1.** For each
   eligible action: claim it, run Claude Code
   headless in the project folder with the agent permission profile, then let the agent close out.
   On failure, report it through the failure endpoint. At most 3 at a time. The permission profile
   allows reading, drafting, files in the folder, and its own completion and next action, and blocks
   outward actions.
7. **The first agent actions:** daily triage and weekly triage templates, built on the workflows in
   `C:\brain\system\workflows\` (Gmail, texts, inbox and captures, journal). Text triage needs
   Chrome and marks threads read when opened, so it may stay in the live review session rather than
   the overnight run.

---

### Agent reference (researched 2026-11-20)

Checked against the official docs on this date. These tools change quickly, so re-check when
building.

**Claude Code** ([CLI reference](https://code.claude.com/docs/en/cli-reference),
[headless](https://code.claude.com/docs/en/headless), [hooks](https://code.claude.com/docs/en/hooks),
[Remote Control](https://code.claude.com/docs/en/remote-control))
- `--session-id <uuid>` sets the session ID up front. `--resume <id|name>` resumes. With an
  explicit ID it searches the current project first, then all projects on the machine (v2.1.223
  and later). `--continue` and the picker are limited to the current folder. `--name` names a
  session.
- Headless: `-p` with `--output-format json` returns `session_id`. Restrict with `--allowedTools`,
  `--disallowedTools`, `--tools`, `--permission-mode`; `--model` picks the model.
- `cleanupPeriodDays` defaults to **30 days**, matching our Resume window. Desktop and Cowork
  sessions use a separate `desktopSessionCleanupPeriodDays`.
- `SessionEnd` hook: receives `session_id`, `cwd`, `transcript_path`, `reason`. It has a short
  default time budget (about 1.5 seconds, raisable to 60 per hook), so the fallback hook should only
  queue the update (write a small file or fire one quick request), not do slow work.
- Remote Control: `claude --remote-control` (or `/rc` inside a session) makes a local session
  reachable from the Claude mobile app or claude.ai/code while the PC process keeps running.

**Codex** ([CLI reference](https://developers.openai.com/codex/cli/reference),
[non-interactive mode](https://developers.openai.com/codex/noninteractive))
- `codex "<prompt>"` starts with a prompt; `-C/--cd` sets the folder; `-m` the model; `-p` a
  config profile.
- `codex resume <id>` (or `--last`, limited to the current folder unless `--all`). `codex fork`
  copies a session.
- Headless: `codex exec`, `--json` prints events; the session ID is `thread_id` on the first
  `thread.started` event. `codex exec resume <id> "<prompt>"` continues one.
- **`codex exec` requires a git repository** unless `--skip-git-repo-check` is passed. Most Dropbox
  folders are not repos.
- Sandbox: `--sandbox read-only | workspace-write | danger-full-access`, `--ask-for-approval`.
  MCP servers are configured with `codex mcp add` in `~/.codex/config.toml`.
- No documented way to choose the session ID up front, and no retention period found. Codex has
  hooks, but a session-end hook was not confirmed.

**Claude Cowork** ([Cowork and chat are one Claude](https://support.claude.com/en/articles/16761823-claude-cowork-and-chat-are-one-claude),
[projects](https://support.claude.com/en/articles/14116274-organize-your-tasks-with-projects-in-claude-cowork),
[scheduled tasks](https://support.claude.com/en/articles/13854387-schedule-recurring-tasks-in-claude-cowork),
[getting started](https://support.claude.com/en/articles/13345190-get-started-with-claude-cowork),
[Dispatch](https://support.claude.com/en/articles/13947068-assign-tasks-from-anywhere-in-claude-cowork))
- Chat and Cowork are being merged into a single Claude experience in the Claude desktop app
  (macOS, Windows, Linux beta), rolling out gradually to Pro, Max and Ultra plans. Tasks run in the
  cloud; local files, the built-in browser and computer use work only while the desktop app is open.
- **Projects** are workspaces with their own files, instructions and **memory**, and can be created
  from **an existing folder on your computer**. Memory is per project ("what Claude learns in one
  project doesn't carry over to other projects") and is reused across tasks in that project.
  Projects are Cowork-only, not Claude Code.
- **Scheduled tasks** (`/schedule`) run in the cloud by default, even with the PC off. A task that
  uses a local folder must "Require this computer" and runs only while the PC is awake and the app
  is open (offered to organizations with computer access turned on).
- Connectors, skills and plugins work in tasks. Plugins with local MCP servers need the desktop app.
- **No documented way to launch a task from outside the app** (no deep link, URL scheme, CLI or
  API), no task IDs or shareable session links. Past tasks are reopened from Recents.
- Dispatch (control the desktop from the phone) is in limited beta and closed to new users.
  Starting a cloud task from the phone is supported.

**What this means for Cowork here:** it fits the **pull** style. Map one Cowork project to one
Dandelion project, using the same primary folder. Cowork's per-project memory then carries what was
learned across tasks, while Dandelion's action notes and completions remain the shared record that
every agent and Nathan can read. Start on an action uses the clipboard prompt. Resume uses Cowork's
own Recents rather than a session ID. Close out needs Cowork to reach the Dandelion MCP as a
connector. Check whether a custom connector can authenticate to Dandelion's MCP (it uses API keys
today; connectors often expect OAuth). For agent actions, a Cowork scheduled task could run "check
Dandelion for work assigned to `cowork` today" as a pull, but only cloud runs work with the PC off,
and those can't see local folders.

---

### Open items for implementation

- Whether the session link is stored on the action or in a small side table (one current link per
  action either way).
- Codex (interactive only in version 1): how the launcher finds the session ID after exit, and
  whether a session-end hook exists. If neither is easy, Codex Resume can come later; New and Start
  matter more.
- Cowork (after version 1): try it on one personal project before designing further. Confirm
  whether the Dandelion MCP can be added as a custom connector, and how its memory feels in practice
  compared with resuming sessions.
- Whether the "prep failed" action goes to the same project or the user's default work/personal
  capture project (see `Project Captures.md`).
