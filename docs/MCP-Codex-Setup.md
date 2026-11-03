# Connecting an AI assistant to Dandelion Daily via MCP

Dandelion Daily hosts its own MCP (Model Context Protocol) server inside the app
itself, at `/api/v1/mcp`. This lets an AI assistant read your project and planning
data and, once you've approved specific changes in conversation, write actions,
outlooks, and project language back to Dandelion on your behalf.

See `docs/Dandelion_Daily_AI_Integration_Assessment.md` for the full design and
decision record. This file is just the practical "how do I connect a client" guide.

This file never contains a real API key. Key values live only in each machine's
environment variables, never in this repo.

## What's exposed

Tools are task-oriented rather than raw CRUD:

- **Read-only**: `get_project_context`, `get_planning_context`, `get_outlook`,
  `list_outlooks`, `get_time_allocation_context`, `get_day_availability`, `get_linked_meetings`,
  `list_projects`, `search_actions`
- **Narrow writes** (single record, no staleness ceremony beyond a simple upsert):
  `set_outlook`, `set_day_availability`, `add_project_ai_thought`,
  `update_project_ai_thought`, `delete_project_ai_thought`, `update_project_icon` (a project's
  emoji; `list_projects` returns the current `projectIcon`)
- **Staleness-checked writes**: `update_project_language` (project description/
  focus/outcome/success criteria), `apply_changes` (batch create/update/reschedule/
  split/remove/complete on actions, private project creation, guarded action moves, and linked-meeting
  synchronization, applied atomically -- all or nothing)
- **Tracker reports**: `get_narratives`, `get_tracker_narrative`,
  `submit_tracker_narrative`, `update_tracker_narrative`,
  `approve_tracker_narrative`, `reject_tracker_narrative`,
  `delete_tracker_narrative`, `generate_tracker_narrative`,
  `regenerate_tracker_narrative`.

### Outcome notes and action threads (5.15)

Requires `src/db/v5.14.sql` (adds `action_taken.action_note` and `action_next.follows_action_next_id`).

- `complete_action` takes `outcomeNote` and `followUp: {description, nextActionType?, scheduledDate?,
  deadlineDate?, targetDate?, estimateMinutes?, notes?, linkUrl?}`. Record work in three parts:
  `completionDescription` is a short past-tense fragment; `outcomeNote` holds the detail (what happened,
  decisions, blockers, where it was left); `followUp.notes` is the handoff for whoever picks up the next step.
- The follow-up is created in the completed action's project and follows it in its thread. A linked-project
  action gets a linked follow-up, one twin per project, each following the step in its own project; the result
  lists the extra twins in `followUpLinkedActionNextIds`. `split_action` replacements also follow the original.
- Action results (`search_actions`, `get_planning_context`, `get_project_context`) include
  `followsActionNextId` and `history` (up to 3 earlier steps, newest first, with `done`, `outcomeNotes`, `notes`,
  `minutes`) when the action continues an earlier one; `historyContinues` says whether there are more.
- `get_work_day_review` returns each completed action's `completionNote` (the outcome note), and
  `get_project_context` recent actions taken carry `outcomeNote`. The daily and weekly reports use outcome notes;
  an action's own notes (written before or during the work) stay out of them.

The same rules apply in the UI: the Edit Action Complete section (outcome note, next action, notes for the next
action) and the dashboard / Focused Action "Next" box create linked, twinned follow-ups the same way, and the
Edit Action box and Focused Action page show the thread's History.

### Read-contract refinements (5.12.2)

Production feedback P-12 through P-15 is addressed without a new schema migration:

- `list_projects` accepts optional `workspaceId` and `externalSourceKey` filters. Both are intersected with
  existing authorization. Distinct synchronized copies remain distinct; no workspace/source is automatically
  canonical. Choose the source explicitly when a name is ambiguous.
- Independently created actions and split replacements now receive separate STANDARD action sets. This improves
  future work-date attribution without changing SHARED action behavior or rewriting old records.
  `search_actions` returns `completionDateUnknownReason`: null when known, `NOT_COMPLETED` for non-completed
  actions, or `NO_UNAMBIGUOUS_WORK_RECORD` when a completed action lacks safely attributable work.
- Completed/cancelled actions return `dashboardBucket:null`.
- **Read-format change:** action `scheduledDate`, `deadlineDate`, and `targetDate` are now `yyyy-MM-dd` (or null)
  across project context, planning context, and action search, instead of midnight timestamps. These are calendar
  dates, not UTC instants. Meeting/work timestamps, `asOf`, and `lastModifiedAt` remain ISO timestamps.
- Unknown historical project `lastModifiedAt` remains null; activity dates are not substituted.

Clean package validation passed 421 tests across 65 suites, with zero failures/errors/skips, including disposable
MySQL tests and in-process JSON-RPC tests. Deployment and live agent verification remain owner-run.

### Project discovery and action management (5.12.1)

See [MCP project/action requests and implementation contract](mcp-project-and-action-requests.md#implementation-contract-5121)
for exact filters, history limitations, batch references, move restrictions, examples, and the owner-run deployment
review checklist. `list_projects` finds projects even without actions or recorded time, including authorized shared
projects. `search_actions` searches private workspace action history. `create_project` and `update_action` project
moves are available through `apply_changes` after user approval.

This is a code-only patch on schema 5.12: there is no new migration beyond the linked-meeting prerequisites below.
Related-project visibility links are deferred; existing SHARED action sets are not equivalent.

### Linked meetings (5.12.0)

Linked meetings complement ordinary `WILL_MEET` actions. Calendar identity, invitation content, start/end, and
source status live on the linked meeting; the action retains its project, focused description, estimates, notes,
manual order, completion, and actual time. This release adds no general action start times, calendar connector,
automatic reordering, reminder delivery, or meeting-note editor.

**Deployment:** manually apply [v5.11.sql](../src/db/v5.11.sql) (old meeting-table removal) and then
[v5.12.sql](../src/db/v5.12.sql) (linked-meeting table), coordinated with deployment of the clean-built WAR.
Both migrations are required before using the new build; action context reads now query the linked-meeting table.
Project/contact sync fields are retained. Production verification is a separate, owner-run step.

#### Read and match

Call `get_linked_meetings` with any combination of:

- `actionNextId`.
- `sourceSystem`, `externalEventUid`, optional `sourceNamespace` (default `""`), and optional
  `externalOccurrenceId`. Omit the occurrence ID to search a recurring series. Source identity is case-sensitive
  and scoped to the authenticated workspace.
- `date` (`yyyy-MM-dd` in the authenticated user's time zone), or both `rangeStart` and `rangeEnd` with explicit
  offsets. Ranges return meetings overlapping `[start, end)`, including meetings that started before the range.
- `sourceStatus`: `CONFIRMED`, `TENTATIVE`, `CANCELLED`, or `MISSING`. Without this filter all statuses are returned.
- `limit` (1-200, default 100) and `offset` (default 0). Follow `hasMore` / `nextOffset` for additional results.

The result is an object with `meetings`, `hasMore`, and `nextOffset`. Each meeting contains the complete source
snapshot, `linkedMeetingId`, numeric `version`, `actionNextId`, `actionAsOf`, action description/status, project
ID/name, and `lastSyncedAt`. Instants are UTC ISO-8601 strings. Series queries also return
`seriesProjectCandidates` across the series' history, independent of occurrence/date/status filters and pagination.
`suggestedProjectId` is populated only when exactly one distinct project was found. Never silently change a
project because of that suggestion.

Project/planning action entries also include `linkedMeeting` (null when absent), including the complete snapshot
and meeting version. For conflicts, compare the returned intervals; do not treat `CANCELLED`/`MISSING` as confirmed
attendance or decide which meeting the user should attend.

#### Synchronize an existing action

After user approval, call `apply_changes`:

```json
{
  "changes": [{
    "type": "upsert_linked_meeting",
    "actionNextId": 456,
    "asOf": "2026-10-26T12:00:00Z",
    "meeting": {
      "sourceSystem": "OUTLOOK_ICS",
      "sourceNamespace": "personal-calendar",
      "externalEventUid": "stable-calendar-series-uid",
      "externalOccurrenceId": "2026-10-26T15:00:00Z",
      "originalSubject": "Planning meeting",
      "originalDescription": "Original invitation content",
      "scheduledStart": "2026-10-26T09:00:00-06:00",
      "scheduledEnd": "2026-10-26T10:00:00-06:00",
      "sourceTimeZone": "America/Denver",
      "joinUrl": "https://example.org/join",
      "sourceStatus": "CONFIRMED"
    }
  }]
}
```

Omit `expectedMeetingVersion` only for a new link. For an existing link, supply the numeric `version` from a read
as `expectedMeetingVersion`, even if it is zero. Keep `asOf` from the action read as well. These are separate
concurrency tokens: meeting sync does not modify the action's `asOf`. The result includes `linkedMeeting` with the
current version after database flush. Identical snapshots preserve the version and `lastSyncedAt`; after a lost
response, reread by source identity before retrying rather than creating a second action.

Required snapshot fields: `sourceSystem` (max 64), `externalEventUid` (255), `externalOccurrenceId` (128),
`originalSubject` (512), `scheduledStart`, `scheduledEnd`, and `sourceStatus`. End must be after start; timestamps
must contain `Z` or an explicit offset. Stored precision is milliseconds. Retain the original occurrence ID when
renaming/rescheduling; do not derive it from the newly scheduled start.

Optional snapshot fields: `sourceNamespace` (128, default `""`), `originalDescription` (1,000,000),
`organizerName` (255), `organizerAddress` (320), `sourceTimeZone` (128), `location` (2048), `joinUrl` (1200),
`sourceLastModifiedAt` (offset timestamp), `sourceRevision` (255), and `sourceFingerprint` (255).
`joinUrl` must be absolute HTTP(S), without embedded credentials. Use the direct conferencing link, not invitation
management/help links. Source time-zone IDs and revisions are retained verbatim.

**This is a complete snapshot, not a patch:** omitted optional source fields are cleared. Unknown fields are
rejected. Never put action notes, project assignment, or estimates inside `meeting`. General action-edit operations
do not accept meeting source fields. Source timestamps older than the stored `sourceLastModifiedAt` are rejected
when both are supplied; opaque revision values are not interpreted as ordered counters.

#### Create and link atomically

Use an ordinary `create_action` with `nextActionType: "WILL_MEET"`, followed by `upsert_linked_meeting` with
`actionChangeIndex` pointing to that earlier entry (zero-based), instead of `actionNextId` and `asOf`:

```json
{
  "changes": [
    {"type": "create_action", "projectId": 123, "description": "Attend planning",
     "nextActionType": "WILL_MEET", "scheduledDate": "2026-10-26", "estimateMinutes": 30},
    {"type": "upsert_linked_meeting", "actionChangeIndex": 0,
     "meeting": {
       "sourceSystem": "OUTLOOK_ICS", "externalEventUid": "event-uid",
       "externalOccurrenceId": "original-occurrence-id", "originalSubject": "Planning",
       "scheduledStart": "2026-10-26T15:00:00Z", "scheduledEnd": "2026-10-26T16:00:00Z",
       "sourceStatus": "CONFIRMED"
     }}
  ]
}
```

A batch is limited to 25 changes. Duplicate source occurrences, multiple meeting operations on the same action,
and conflicting type changes are rejected for the entire batch. Runtime/constraint/concurrency failures roll back
all writes, including newly created actions. Source identity and action attachment are immutable during upsert.
There is no automatic reassignment, transfer to split actions, or propagation through shared action sets.

#### Cancellation, absence, and unlinking

Synchronize a complete snapshot with `sourceStatus: "CANCELLED"` or `"MISSING"` to record those states. Neither
closes or reschedules the action. Absence from a read never automatically marks anything missing; the integration
must explicitly decide that based on its successful source reads. Completing/cancelling an action retains its
meeting. Action date/order changes remain separate, explicit `apply_changes` operations.

After explicit approval, `unlink_meeting` accepts `actionNextId`, action `asOf`, and `expectedMeetingVersion`.
It deletes only the linked snapshot, preserving the action. To replace or reassign a link, unlink first, then
perform the new link in a later call. Existing browser/Plan Ahead edits reject type changes away from `WILL_MEET`
while linked and reject conversion into a template; unlink through MCP first. There are no new meeting UI controls.

**Owner-run production verification:** create/link one occurrence, read it back by identity, repeat with the current
version, rename/reschedule it while preserving its occurrence ID, and confirm no duplicate action or unintended
planning changes. Verify a new recurrence suggests its previous project, then verify cancellation leaves its action
open. Local automated tests exercise the JSON-RPC dispatcher and rollback path; the opt-in persistence test uses
only a disposable local `linked_meeting_test_*` schema, never the application database.

### Daily, weekly, and monthly report workflow (5.10.16)

For the exact daily/weekly writing instructions, report sections, source-data
rules, and an MCP evidence-gathering recipe, see the
[Tracker narrative generation guide](Tracker-Narrative-Generation-Guide.md).

These tools operate on **TrackerNarrative** reports, not dated project notes
(`add_project_narrative`, etc.). Every write requires explicit authorization
in conversation. Permission to save a draft is **not** permission to approve it.

1. Generate report text in the assistant, then call `submit_tracker_narrative`
   with `projectId`, `periodType` (`DAILY`, `WEEKLY`, `MONTHLY`), a `date`
   (`yyyy-MM-dd`) within the period, and nonempty `markdown`. The project must be
   in the API client's workspace; the owner comes from the authenticated user.
   Optional `modelName` and `promptVersion` are limited to 50 characters each;
   optional `promptUsedText` records the source prompt. Source metadata is
   caller-supplied, not independently verified.
2. Submission returns a saved, **unapproved** `GENERATED` draft, with the submitted
   text in both `markdownGenerated` (original source) and `markdownFinal`
   (editable text). It does not call the application's AI provider.
3. Call `get_tracker_narrative(narrativeId)` to read it back for review. Set
   `includePrompt: true` to include the captured prompt; it is omitted otherwise.
4. Use `update_tracker_narrative(narrativeId, lastUpdated, markdown)` to save edits.
   The original generated Markdown is preserved. Editing an approved report
   creates a **new draft ID**; always use the ID returned by the tool. The old
   approved text stays unchanged until the new draft is approved. The browser's
   two report editors follow the same revision rule.
5. Only after the user explicitly approves the saved text, call
   `approve_tracker_narrative(narrativeId, lastUpdated)`. The response includes
   `displacedNarrativeIds`: any previously approved reports for the same
   owner/type/period are changed to `REJECTED`. An approved weekly report is
   used by the weekly report page, so this action can publish its text.

All report results are objects, with `narrativeId`, `projectId`, title, type,
status, both Markdown fields, generation/approval timestamps, source model/prompt
version, and `lastUpdated`. The existing `text` and `isFinal` fields remain:
**`isFinal` only means editable text exists, not that the report is approved.**
Use `isApproved` or `reviewStatus` to determine approval. Existing `periodStart`
and `periodEnd` timestamp fields are retained for compatibility; new
`periodStartDate` and `periodEndDate` provide timezone-independent civil dates.
Weeks run Sunday-Saturday; months use their calendar boundaries.

Existing-record writes (including regeneration, rejection, and deletion) require
the exact latest `lastUpdated` token. A `stale` error means reread, review the
new text, then retry with fresh authorization as appropriate. Tokens advance at
least one second per mutation to work with the legacy database's second precision;
under rapid edits they are version tokens, not precise wall-clock audit times.
Checks and writes are serialized per owner within the database transaction.
Unknown IDs/workspace mismatches, blank Markdown, and invalid state changes fail
explicitly. A `GENERATING`, `FAILED`, `REJECTED`, or `DELETED` report cannot be
approved; edit a rejected report back to a draft first. A generating report
cannot be edited, rejected, deleted, or regenerated.

`reject_tracker_narrative` retains text/history; `delete_tracker_narrative`
soft-deletes. Either action on an approved report removes its current approval.
Historical `dateApproved` can remain after rejection/deletion; status is authoritative.

Submission retries with identical **project, canonical period, original Markdown,
and optional source metadata** return the oldest matching existing report,
including if it has since been edited, approved, or deleted. No duplicate is
created and no deleted report is restored. Different source text/metadata creates
a new candidate. Editing and approval are separate from this submission deduplication.

`get_narratives` still requires `periodType`, `fromPeriodStart`, and
`toPeriodStart` (inclusive bounds on period **start**, not overlap). It now supports
`MONTHLY`, optional `reviewStatus`, `includeHistory` (default `true`), and
`includeDeleted` (default `false`). `includeHistory: false` returns the newest
matching candidate per period, not necessarily the approved candidate. To list
deleted reports, set `includeDeleted: true`, also when filtering on `DELETED`.
Results are restricted to the authenticated owner and client workspace.

Alternatively, `generate_tracker_narrative(projectId, periodType, date)` uses the
application's configured AI provider. `regenerate_tracker_narrative(narrativeId,
lastUpdated)` creates a separate candidate without replacing the source.
Both return a new `GENERATING` record immediately. Work is queued only after
the database commits; use `get_tracker_narrative` to check for `GENERATED` or
`FAILED`. Missing AI configuration returns `generation_unavailable`; external
submission does not require it. Generation failure details remain in server logs.

No schema migration is needed for these tools.

## Authentication

Every request needs an `X-Api-Key` header. Keys are managed per-client under
**Settings -> Web API Client Keys** in Dandelion itself:

1. Log in to Dandelion.
2. Go to Settings.
3. Under "Web API Client Keys", enter an agent name (e.g. `codex`, `claude-code`)
   and click Create API Key. Leave the workspace field blank -- it defaults to your
   own private workspace.
4. Copy the generated key immediately. It's also visible any time you're on that
   page later, listed by agent name.
5. To revoke a key, delete it from the same table -- this disables it immediately.

Each client (Codex, Claude Code, anything else) should get its **own** key with its
own agent name, so you can tell them apart in `action_change_log` and revoke one
without affecting the others.

## Endpoint

```
https://dandelion-daily.org/api/v1/mcp
```

JSON-RPC 2.0 over HTTPS POST. `X-Api-Key` and `Content-Type: application/json` on
every request.

## Codex CLI setup

Codex's MCP configuration is **global per machine** (`~/.codex/config.toml` --
on Windows, `%USERPROFILE%\.codex\config.toml`), not per-project. Configure it once
and it's available in every future Codex session regardless of working directory --
you don't need to launch Codex from inside this repo.

1. Store the API key as a **persistent environment variable** on the machine running
   Codex -- never inline in `config.toml`. On Windows, a User or Machine environment
   variable named `DANDELION_MCP_API_KEY` works (System Properties -> Environment
   Variables, or `[Environment]::SetEnvironmentVariable(...)` in PowerShell). Any
   terminal or Codex session already running when you set it won't see it -- start a
   fresh one.
2. Add this to `config.toml`:

   ```toml
   [mcp_servers.dandelion]
   url = "https://dandelion-daily.org/api/v1/mcp"
   env_http_headers = { "X-Api-Key" = "DANDELION_MCP_API_KEY" }
   ```

   `env_http_headers` maps a header name to the *name* of an environment variable
   Codex reads at runtime -- the key itself never appears in this file.
3. Start a new Codex session and confirm the server connected (Codex should list
   `dandelion` among its available MCP servers/tools, or you can just ask it to use
   one of the tools above, e.g. "check my outlook for this week").

## Rotating or removing a key

Delete the old key under Settings -> Web API Client Keys, mint a new one, and update
the environment variable it's stored in. Nothing in `config.toml` needs to change,
since it only ever references the variable's name.

## Claude Code setup

Like Codex, Claude Code's MCP configuration can be scoped globally per machine
(`user` scope) rather than per-project, so one setup covers every future session
regardless of working directory. User- and local-scope servers both live in
`~/.claude.json` (on Windows, `%USERPROFILE%\.claude.json`); user scope is the one
that isn't tied to a specific project directory.

1. Store the API key as a persistent environment variable first (same as the Codex
   step above) -- e.g. `DANDELION_MCP_API_KEY`. Any Claude Code session already
   running when you set it won't see it; start a fresh one.
2. Add the server with the CLI (don't type the real key -- `${VAR}` stays literal
   and is resolved from the environment at runtime):

   ```bash
   claude mcp add --transport http dandelion https://dandelion-daily.org/api/v1/mcp \
     --header 'X-Api-Key: ${DANDELION_MCP_API_KEY}' --scope user
   ```

   Quote the `--header` value with single quotes (or otherwise prevent shell
   expansion) so the literal string `${DANDELION_MCP_API_KEY}` reaches Claude Code,
   not an already-expanded (or empty) value. You can confirm what actually got
   stored with:

   ```bash
   grep -A 5 '"dandelion"' ~/.claude.json
   ```

   The `X-Api-Key` value shown should be exactly `${DANDELION_MCP_API_KEY}`, never
   the real key.
3. Start a new Claude Code session and confirm the connection -- ask it to use one
   of the tools above, e.g. "check my outlook for this week in Dandelion."

To remove or change it later: `claude mcp remove dandelion --scope user`, then
re-add.
