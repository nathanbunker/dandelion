## Plan: project captures (an inbox on every project)

Nathan wants one place where unsorted information lands — his own quick thoughts, journal entries,
and observations written by agents (email triage, text triage, meeting notes, calendar sync) — so
it can be filed later. Each project gets its own inbox. A **capture** is an uncategorized thought or
observation attached to a project, with a status that says whether it still needs to be read.

This replaces the current "AI thoughts" feature (`project_ai_note`), which is already most of the
way there: free text, required project, a `source`. What it lacks is a status, a record of where the
capture went, and a way for a human to be the author.

Written 2026-11-19 from a design conversation with Nathan. Decisions marked **settled** were made
with him; the rest are recommendations for the implementing agent to confirm.

---

### Why

Capture should take seconds and require no decision about where something belongs. Filing happens
later, in a triage session Nathan starts and steers (daily or weekly, frequency not yet chosen).
That session covers his texts, personal Gmail, the `C:\brain\inbox` folder, and journal entries,
and Dandelion captures become the common inbox they all feed. Other processes can write captures
too ("this happened"), without deciding what it means.

Flow:

```
Nathan (phone/web) ─┐
Email triage ───────┤                                  triage session (Nathan + agent)
Text triage ────────┼──► captures (status NEW) ──► action · narrative · brain/Dropbox file · dismissed
Meetings ───────────┤     on a project                          (status PROCESSED / DISMISSED)
Journal ────────────┘
```

---

### Decisions (settled)

1. **A project is always required.** There is no project-less capture. Nathan keeps two default
   inbox projects, one personal and one work, and captures with no obvious home go there. **Which
   projects those are is a stored user preference** (default personal capture project, default work
   capture project). The phone app and the web app both use it.
2. **Every capture is attributed:** who or what wrote it.
3. **Status plus outcome.** A capture is new until it is processed or dismissed, and records where it
   went (the outcome). Processed captures are hidden by default but **never deleted** by processing,
   so you can always trace where a thought ended up.
4. **Captures can be moved** to another project.
5. **The journal is a project.** "Daily Journal" is an ordinary project with goals and a recurring
   "complete my journal" action. Journal entries are captures on that project. Nothing special is
   needed in the data model for this. (Triage copies journal captures into `C:\brain\journal\` before
   marking them processed; that is a process rule, not app behavior.)
6. **Rename, don't alias.** AI thoughts become captures, and names should match what things do:
   table, model, service, MCP tools and UI labels all say "capture". No long-lived aliases for the
   old `*_ai_thought` tool names.
7. **Project narratives stay as they are.** They are curated, semi-polished records. Captures are
   raw input, not a kind of narrative.
8. **Shared or personal projects, never both.** A capture can be added to any project, shared or
   private. Unlike actions, captures are **not twinned** across a linked private/shared project pair:
   each capture lives on exactly one project.
9. **Kept forever for now.** Processed and dismissed captures are not purged. A cleanup process may
   come later if the database grows too large.
10. **NOTE narratives stay.** NOTE is the daily project review note (see "Why NOTE stays" below).
    It is a semi-formal project output and stays a narrative type. Nothing is migrated.
11. **Priority order.** The first use case is **capturing from the phone, for personal projects**.
    (1) Data model, service, and MCP; (2) phone capture in Sundial, personal projects first;
    (3) web app design and build. Each phase must be deployable on its own.

---

### How AI thoughts work today

- **Table** `project_ai_note` (`src/db/v5.10.sql`): `note_id`, `project_id`, `note_text`,
  `source varchar(80)`, `created_at`, `updated_at`. Mapping: `ProjectAiNote.java` /
  `ProjectAiNote.hbm.xml`; DAO `doa/ProjectAiNoteDao.java`.
- **Service** `org.dandeliondaily.projectainote.service.ProjectAiNoteService`: plain CRUD with
  workspace scoping through the project. Its comment says "no batch/staleness complexity, no review
  workflow".
- **MCP tools** `add_project_ai_thought`, `update_project_ai_thought`, `delete_project_ai_thought`
  (`api/v1/mcp/tools/*AiThoughtTool.java`, registered in `McpToolRegistry`). `source` is set from
  `context.getAgentName()`, falling back to `"mcp"`.
- **Reads:** `get_project_context` returns `aiThoughts` (`McpProjectContextService`).
- **UI:** the "AI Thoughts" section on the project dashboard (`DashboardNowColumnService`,
  `DashboardNowColumnModel.AiThoughtItem`, `DashboardPageRenderer`), with add, edit and delete
  handlers in `DandelionDashboardServlet` (around lines 2117–2191).
- **Chat context:** `ProjectDashboardAiContextService.appendAiThoughts` adds up to
  `MAX_AI_THOUGHTS = 20` to the in-app project chat, labeled as assistant observations. Tested in
  `ProjectDashboardAiContextServiceTest`.
- **Data:** the local copy of production has only 2 rows (both `source = MCP`, project 48903,
  2026-09-30).

The real difference between the old and new concept: an AI thought was **memory**, re-read as
context in every session forever, while a capture is a **queue item**, read once and filed. Adding a
status fixes a weakness AI thoughts already have: stale thoughts pile up and fill the 20-item chat
context whether or not they are still relevant.

---

### Phase 1: data model, service, API/MCP

**Schema** (`src/db/v5.17.sql` or the next free number; the latest file today is `v5.16.sql` and
`pom.xml` is at 5.17.0):

- Rename the table `project_ai_note` → `project_capture` and the column `note_id` → `capture_id`,
  `note_text` → `capture_text`. Keep `project_id`, `source`, `created_at`, `updated_at`.
- Add:
  - `author_type varchar(10) NOT NULL` — `HUMAN` or `AGENT`. Existing rows become `AGENT`.
  - `source varchar(80)` (existing) — free-text detail: agent name (`gmail-triage`,
    `text-triage`, `codex`) or the human channel (`web`, `sundial`).
  - `author_web_user_id int NULL` — the signed-in user for human captures, and the key owner for
    MCP. Recommended, so attribution doesn't rely on free text.
  - `status varchar(12) NOT NULL DEFAULT 'NEW'` — `NEW`, `PROCESSED`, `DISMISSED`.
  - `disposition text NULL` — where it went, written when it leaves `NEW`: "Action 51234 created",
    "Filed to C:\dropbox\money\recurring-payments.md", "Added to project narrative", "Nothing to do".
  - `processed_at datetime NULL`, `processed_by varchar(80) NULL`.
  - `captured_at datetime NOT NULL` — when the thought happened, which can be earlier than
    `created_at` (a journal entry typed tonight about this morning, or an email sent yesterday).
    Defaults to `created_at`.
- Indexes: `(project_id, status)`, and something that supports "all new captures for this user's
  workspaces" ordered by `captured_at`.
- Moving a capture just changes `project_id`. Record the move by appending to `disposition` or a
  `moved_from_project_id` column; a full history table is not needed now.
- **Default capture projects (preference).** Two per-user values: the default personal capture
  project and the default work capture project. Store them wherever per-user settings live today (a
  `web_user` column pair or the existing settings mechanism; the implementing agent should follow
  the codebase's pattern). Validate that the personal default is a non-billable (personal) project,
  because Sundial will only use that one.

Rename `ProjectAiNote`, its `.hbm.xml`, `ProjectAiNoteDao`, the `hibernate.cfg.xml` mapping line, and
the `projectainote` package to `ProjectCapture`, `ProjectCaptureDao`, `projectcapture`. Follow the
repo conventions in `CLAUDE.md` (DAOs in `...pt.doa`; the UI and the MCP call the same service).

**Service** `ProjectCaptureService`, shared by MCP, REST and UI:

- `create(workspace, projectId, text, authorType, source, authorWebUserId, capturedAt)`
- `update(captureId, text)` — edits text only.
- `list(projectId, statusFilter)`
- `listInbox(user, statusFilter = NEW, optional projectId/tag, limit/offset)` — **across all
  projects the user can see**, newest first. This is the key new read: one call returns the whole
  inbox.
- `process(captureId, newStatus, disposition, optional moveToProjectId, processedBy)` — rejects a
  missing disposition when leaving `NEW`. Allows reopening (`PROCESSED` → `NEW`) so a mistake can be
  undone.
- `delete(captureId)` — kept for real mistakes and junk; processing never deletes.
- A staleness check on `process`/`update` using `updated_at`, matching the `asOf` pattern in
  `apply_changes`, so two triage sessions can't overwrite each other.

**MCP tools** (rename and extend; update tool descriptions so agents know when to use them):

| Tool | Replaces | Notes |
|---|---|---|
| `add_project_capture` | `add_project_ai_thought` | `projectId`, `text`, optional `capturedAt`. Callers through MCP are recorded as `AGENT` with `source` from the agent name. Allow an explicit `authorType: HUMAN` for an agent saving Nathan's own words verbatim (for example a journal entry sent by email), so attribution stays correct. |
| `update_project_capture` | `update_project_ai_thought` | Text only. |
| `delete_project_capture` | `delete_project_ai_thought` | |
| `list_captures` | (new) | The inbox across projects. Defaults to `NEW`. Returns project id and name, author type, source, timestamps, text, and the `updated_at` marker. Paginated like `list_projects`. |
| `process_captures` | (new) | A batch of `{captureId, status, disposition, moveToProjectId?, asOf}`, applied atomically like `apply_changes` (validate everything, then apply everything). A triage session usually files many captures at once. Alternatively, add `process_capture` as an `apply_changes` operation if that keeps the surface smaller — the implementing agent should choose and say why. |

`get_project_context`: rename `aiThoughts` to `captures` and by default return only `NEW` ones, with
a count of processed ones.

`add_project_capture` with no `projectId` needs to know whether the capture is personal or work
(for example a `kind: PERSONAL | WORK` argument) and falls back to that default capture project.
If the preference isn't set, refuse with a clear error rather than guessing. Also add a way to read
and set the two defaults: a field on the existing Settings page is the smallest option, or an MCP
tool. Either is fine for phase 1.

**Shared projects:** allowed (decision 8). Use the same read/write authorization the MCP already
applies to shared-workspace projects for other writes. Never write a capture to both twins of a
linked project.

**Existing UI and chat during phase 1:** the AI Thoughts section and the chat context must keep
working on the renamed service, and change as little as possible:

- Rename the dashboard section label to "Captures". Show only `NEW` captures. Adding a capture from
  the web creates a `HUMAN` capture.
- The chat context shows only `NEW` captures, labeled by author type ("Nathan wrote" vs. "an agent
  observed") so the model doesn't treat an agent's guesses as Nathan's decisions. Keep the cap, and
  give human captures priority or list them separately so a burst of agent captures can't crowd
  them out.
- Update `ProjectDashboardAiContextServiceTest`.

**Testing:** unit tests for the service (status changes, move, staleness rejection, the
disposition requirement, workspace scoping, and that the inbox list never shows another
workspace's captures). Live-test the MCP tools against the local deploy
(`http://localhost:8089/dandelion/api/v1/mcp`), then record the result in `docs/MCP-Feedback.md`.

---

### Phase 2: phone capture in Sundial (personal projects first)

This is the first real use: Nathan types or dictates a thought on his Galaxy S23 Ultra and it lands
as a capture in Dandelion. Read `docs/plans/Android App/Sundial Plan.md` first. Everything there
still applies, especially **personal only, never work**: device keys may only call `me`,
`devices/me` and `personal/*`, and the server refuses billable projects.

**Naming conflict to settle first.** Sundial already has a feature called **Capture**: the Now
card's inline-reply button, the Quick Settings tile, and the long-press shortcut all open a capture
sheet that **creates a personal action** (`ActionCaptureService`, `project:` prefix, "Card captures
to…"). A thought capture is a different thing. Options for Nathan to choose from (recommendation
first):

1. **One capture sheet with a toggle: Action / Thought.** Same entry points. The sheet remembers
   the last choice, and the card's inline reply uses a per-card setting. Least new UI, one habit.
2. Separate entry points: keep "Capture" for actions and add "Thought" (a second tile, a second
   shortcut, a second card button).
3. Rename the existing action feature to "Add action" so "Capture" means only thoughts.

**Server (personal API, bump `apiLevel`):**

- `POST /v1/personal/captures {text, projectId?, capturedAt?}`: creates a `HUMAN` capture with
  `source = sundial`. With no `projectId`, it uses the **default personal capture project**. Refuses
  a billable (work) project, and refuses clearly if no default is set. It must be idempotent the way
  the existing personal endpoints are (`IdempotencyService`), so a retried tap doesn't create two.
  The same `project:` prefix rule as action capture can pick another personal project.
- `GET /v1/personal/captures?status=NEW` (optional for this phase): the user's recent personal
  captures, so the app can show "saved" and allow a quick edit or delete.
- `PATCH`/`DELETE /v1/personal/captures/{id}`: fix a typo or remove a mistake right after saving.
- Same service as MCP (`ProjectCaptureService`); no separate logic.

**App:**

- Thought capture from the existing entry points (per the naming decision): the capture sheet, the
  card's inline reply, the tile, and the shortcut.
- **Voice:** the keyboard's mic button works in any text field. Make sure the field is multi-line
  and long enough for a dictated paragraph or a journal entry.
- **Android share target:** "Share → Sundial" from any app (selected text, a link, a Samsung Voice
  Recorder transcript) opens the capture sheet prefilled as a Thought. Low cost and high value for
  this use case.
- Receipt like the action capture: `Saved to Personal Inbox: … · Edit`.
- **Offline:** action capture deliberately queues nothing ("Not saved · can't reach Dandelion").
  For thoughts, losing a journal paragraph is worse than for a one-line action. Recommend keeping
  the unsent text in the sheet (a draft that survives closing the app) rather than a background
  queue. Confirm with Nathan.
- Picking the project: the default personal capture project, with the option to choose another
  personal project. Nathan's Daily Journal project should be one tap away (for example a recent or
  pinned project chip), since journaling is a daily use.

**Not in this phase:** work captures from the phone (never, unless designed separately), a list
or inbox screen for processing on the phone (triage happens with an agent at the desk), and
anything on shared projects from the phone.

**Testing:** server tests for the endpoints (default project, billable refusal, idempotency, no
default set); app tests for the sheet and share-intent parsing. Test on the S23 against the local
server (`dev.ps1 run` with adb reverse) before production.

---

### Phase 3: web app (design, then build)

Where captures appear in the web app needs real design work. Questions to answer with Nathan, then
build:

- **Quick add.** Where the "capture a thought" control lives (dashboard header? the Now column? a
  global shortcut?). It defaults to the personal or work capture project, based on context or a
  toggle.
- **The inbox view.** One page listing all `NEW` captures across projects, grouped by project, with
  inline process, dismiss and move. This is what a triage session would use in the browser.
- **On the project page.** How the captures section (renamed in phase 1) fits with narratives,
  issues and actions, and whether project reviews (the review cadence) should start by showing new
  captures.
- **Counts.** Whether new-capture counts show on project lists, Plan Ahead, or the dashboard.
- **Settings.** A full place to set the default capture projects, if phase 1 only added a minimal
  field.

Output: a short design section added to this file and agreed with Nathan before building.

---

### Why NOTE stays (settled)

Nathan first asked to move any NOTE narratives into captures and remove the NOTE type. After seeing
what NOTE is used for, he decided to keep it. For the record:

- `project_narrative` holds **319 NOTE rows** (Feb–Nov 2026, 38 projects), plus DECISION 17,
  INSIGHT 41, OPPORTUNITY 41, RISK 17.
- NOTE is the **daily project review note**. `save_work_day_review` (`SaveWorkDayReviewTool`,
  `McpSaveWorkDayReviewService`) writes it, and its tool description says *"a NOTE is what marks the
  project reviewed."* Many rows are exactly that: "Reviewed/no comments", stamped 18:00 or 06:00.
- The report generator reads them: `OpenAiNarrativeGenerator` builds a "Key Notes" section from
  NOTE entries (around lines 52 and 436), and the in-app chat context includes recent narratives.
- Nathan's "Weekly thoughts" entries are stored as NOTE on project 49051.

Moving them and removing NOTE would break the daily review, the "reviewed" marker, and the
daily/weekly report. **Do not migrate, rename or remove NOTE.**

---

### Open questions for Nathan

**Q1. Naming in Sundial.** How thought capture fits next to the existing action Capture (phase 2,
"Naming conflict"). Recommendation: one sheet with an Action / Thought toggle.

**Q2. Offline thoughts on the phone.** A draft kept in the sheet (recommended) or a real offline
queue.

**Q3. Who sees captures on shared projects.** Captures are allowed there (decision 8). Decide
whether all members of the shared workspace see them, or only the author, before the web app shows
them (phase 3). Until then, MCP reads follow the existing shared-workspace read rules.
