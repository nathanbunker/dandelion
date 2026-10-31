# Dandelion MCP Feedback

A running list of problems found and ideas raised while using the Dandelion Daily MCP
server (`/api/v1/mcp`) from Claude Code. Add new entries at the top of each section with
the date found. When something is fixed, move it to **Resolved** with the fix date rather
than deleting it.

See `docs/Dandelion_Daily_AI_Integration_Assessment.md` for the design and
`docs/MCP-Codex-Setup.md` for client setup.

## Status (2026-10-01)

**Historical status:** I-12 build item 14 was outstanding. I-12 was implemented in version 5.10.6 and
is deployed; build item 13 was verified on 2026-10-03 (see Resolved), and build item 14
still needs its write test against production. Every other problem (P-1 to P-10)
and idea (I-1 to I-11) in this document has been addressed, and each entry below is
marked **Status: Addressed**. The entries are kept for history.

I-11 (build items 11 and 12) was deployed and verified on 2026-10-01; see Resolved.

## Recommended Changes for the Next Build

### Action notes become one plain-text value in 5.13.0 (2026-10-31; local build verified, deployment pending)

An action's notes are now one text value per action instead of a log of dated lines (plan:
`docs/plans/Action Notes Notepad.md`). Migration `src/db/v5.13.sql` copies the old lines into
`action_next.next_notes`. MCP clients will see these changes:

- **`notes` in every action context** is the stored text exactly as typed: blank lines, indentation and
  leading dashes are kept. The `" - "` prefix that used to start every line is gone. `null` means
  the action has no notes.
- **`apply_changes` `update_action.notes`** is new. It replaces the whole text, and `""` or `null`
  clears it. Like every `update_action`, it requires `asOf`.
- **`update_action.addNote`** still appends, now as a new line at the end of the text with no prefix or
  date. Sending both `notes` and `addNote` in one change is a validation error.
- Both `notes` and `addNote` are allowed on a template-generated instance (they change only that
  instance). On a linked-project action, both reach the action's twin on the other project, so the twin's
  `asOf` changes too.
- Notes are limited to 20,000 characters; longer text is rejected, not truncated.
- **`search_actions` with `includeNotes`** searches the notes text.

### P-12 to P-15 follow-up in 5.12.2 (local build verified; deployment pending)

Added authorized workspace/source filters without deduplicating distinct synchronized projects; separated
future independent action sets for work attribution; suppressed closed-action dashboard buckets; and normalized
calendar-date output. Unknown historical timestamps are preserved, not guessed. See the
[5.12.2 contract and review notes](mcp-project-and-action-requests.md#follow-up-refinements-5122).
Clean build: 421 tests, 65 suites, zero failures/errors/skips, including disposable MySQL and JSON-RPC tests.
The existing UI project editor does not consistently stamp modification dates; that broader writer-audit work is
not included in this MCP patch. Existing null values remain honest unknowns.

### Project discovery and safe moves in 5.12.1 (partly verified 2026-10-26)

Reads and the create/move happy path were verified against production on 2026-10-26 (see Resolved).
Rejection cases, moves with a linked meeting, and historical time after a move are still untested.
Follow-ups found during verification are P-12 to P-15.

The project-discovery portion of I-1 was not actually covered by the previous tools: a project with no open
actions or recorded time could not be discovered. `list_projects` now explicitly covers empty private projects
and authorized shared projects, with matching shared reads in `get_project_context`. `search_actions` adds
completed/cancelled history with conservative, explicit handling of unknown completion dates.

`apply_changes` adds private `create_project`, same-batch project references, and guarded moves of open,
non-template actions. Identity/meeting links survive moves; historical time stays in its original project.
Related-project visibility links remain deferred and must not be simulated using SHARED action sets.
See the [implemented contract and deployment review checklist](mcp-project-and-action-requests.md#implementation-contract-5121).
No 5.13 migration is required. Production-agent review is still pending; local tests are not a production result.

### Linked meetings implemented in 5.12.0 (not yet production-verified)

`get_linked_meetings` supports durable occurrence/series identity lookup, schedule/status filters, and series
project suggestions. `apply_changes` supports atomic linked-meeting upserts/unlinks, including linking an action
created earlier in the same batch. Meeting timing and invitation data remain separate from action planning fields.
Project/planning reads include the linked snapshot. The old unused meeting-note subsystem is removed; InteropHub
continues to own meeting notes. See the [linked-meeting contract](MCP-Codex-Setup.md#linked-meetings-5120) for the
manual migration sequence, examples, concurrency rules, and owner-run production verification.

### Tracker report lifecycle implemented in 5.10.16

The existing read-only `get_narratives` now has monthly/status/history/deleted
filters and explicit approval indicators. New tools support detail reads,
external draft submission, editing, explicit approval, rejection, soft deletion,
and application-side generation/regeneration. Both browser editors use the same
workflow, stale-version checks, and approved-report revision preservation.
See [report workflow and tool contracts](MCP-Codex-Setup.md#daily-weekly-and-monthly-report-workflow-51016).
Focused automated coverage exercises the JSON-RPC dispatcher and shared service;
deployment verification against a live database is a separate step.

**Agreed with Nathan:** 2026-09-30. This is the work list for the implementing agent.
Details are in the referenced entries below. After deployment, Claude will test each
item against the **Verify** line and move fixed entries to **Resolved**.

General rules for all new tools:
- return a JSON object, never a bare list, in `structuredContent` (P-1);
- any tool that writes narratives, cadence, or project fields requires Nathan's explicit
  approval in conversation before it's called, and the tool descriptions should say so;
- the UI and the MCP should call the same service, so behavior can't drift.

| # | Change | Ref | Verify |
| --- | --- | --- | --- |
| 1 | Fix list results in `structuredContent` (wrap lists in an object) | P-1 | `get_day_availability` and `list_outlooks` return data instead of a schema error |
| 2 | Add `get_work_day_review(date)` | I-10 | Returns today's review list with minutes, reviewed flag and reason, completed and deleted actions, and existing narratives with ids, matching the review screen |
| 3 | Add `save_work_day_review(date, projectId, entries)`, using a service shared with `ProjectNarrativeReviewServlet` | I-10 | Saving through the MCP marks the project reviewed in the UI; omitted fields stay unchanged; an empty field removes that day's entry |
| 4 | Fix clearing a field in the review screen | P-7 | Clearing a Decision in the UI and saving removes it |
| 5 | Add narrative ids (and `lastUpdated`) to `get_project_context` | I-10, I-5 | `recentNarratives` entries include ids |
| 6 | Add narrative create, update, and delete for any date | I-5 | Add, edit, and remove a test narrative on an older date |
| 7 | Report daily report (`TrackerNarrative`) status in the work day review | I-10 | Review result shows whether the day's report was generated or approved |
| 8 | Add read-only access to daily and weekly narratives | I-8 | Last week's narrative can be read, with its review status |
| 9 | Add a tool to change a project's review cadence (`ProjectContactAssigned.updateDue`) | I-7 | Move a test project from Week to Month and see it on the Project Health page |
| 10 | Return the new `asOf` from `apply_changes` results | P-4 | An update followed by a reschedule works without a fresh read |
| 11 | Return `completionOrder`, `priorityLevel`, and the dashboard bucket for each action in `get_planning_context` and `get_project_context`, sorted within a day the way the dashboard sorts (added 2026-10-01) | I-11 | Read today; the order and groups match the dashboard Today column |
| 12 | Add an `order_day` change type to `apply_changes` that sets `completionOrder` within buckets; the dashboard bucket order doesn't change (added 2026-10-01) | I-11 | Reorder three WILL actions for today through the MCP; the dashboard shows them in that order inside the WILL group, and the order is still there after the dashboard reloads |
| 13 | Return `linkUrl` on each action in `get_planning_context` and `get_project_context` (added 2026-10-01) | I-12 | Read a day that includes an action with a link set in the UI; `linkUrl` matches |
| 14 | Let `create_action`, `update_action`, and `split_action` (per new action) set `linkUrl`; allow `update_action` to change only `linkUrl` (and `addNote`) on template-generated instances (added 2026-10-01) | I-12 | Create an action with a link, change it, and clear it through the MCP; each shows on the dashboard Now column. Set a link on a template-generated instance; it's accepted, and the template root is unchanged |

Items 1 to 13 are done (see Resolved). Item 14 (I-12) is deployed and waiting on verification.
The entries that were left out of the first build (I-1, I-2, I-3, I-4, I-9, P-2, P-3,
P-5, P-6) have since been addressed as well.

## Problems

### P-12: `list_projects` returns each shared project twice

**Status:** Addressed in 5.12.2; deployment verification pending
**Found:** 2026-10-26 (production, 5.12.1 verification)

Each InteropHub project appears once in the InteropHub workspace (3, source `InteropHub`) and
once in Emerging Standards (6, source `InteropHub-ES`), with the same `externalProjectId`.
For example, "Immunization CDS (ImmDS + HALO)" is both 48791 and 48968 (`PRJ-1`). Only 48968
has activity. An agent resolving a name has no rule for which copy to use.

**Suggestion:** document which workspace is canonical for agent use, or add a filter
(for example `workspaceId` or `externalSourceKey`) so one copy can be selected.

**Resolution:** added both filters within existing authorization. These are distinct project records, not
duplicate query rows. No merging, activity-based selection, or canonical-source default is performed.

### P-13: Completion dates are unknown for most completed meeting actions

**Status:** Future attribution improved in 5.12.2; existing historical ambiguity remains
**Found:** 2026-10-26 (production, 5.12.1 verification)

`search_actions` for completed actions 2026-10-19 to 2026-10-23 returned completed meetings
796769 (Weekly S&A/Tech & Dev Team Meeting) and 796770 (Internal Pfizer Meeting) with
`completionDateKnown:false` and null `recordedWorkDate`. A completed WAITING action (796640)
did have a known date. Suspected (not verified): meetings created in one `apply_changes`
batch share an action set, so the history rule treats their work as ambiguous. If so,
meetings synced in batches will never have known completion dates.

**Resolution:** the suspected batch-set cause is confirmed by code. New independent creates/split results get
individual STANDARD action sets; intentional SHARED semantics are unchanged. Historical sets/work are not
rewritten, and absent/ambiguous work still yields null. `completionDateUnknownReason` distinguishes
`NOT_COMPLETED` from `NO_UNAMBIGUOUS_WORK_RECORD` (null when known). A comprehensive legacy-history solution
would require a separate action-to-work model change, not inference from titles or modification dates.

### P-14: Closed actions still report a `dashboardBucket`

**Status:** Addressed in 5.12.2; deployment verification pending
**Found:** 2026-10-26 (production, 5.12.1 verification)

`search_actions` returns completed 796769/796770 with `dashboardBucket:"Overdue"` and
cancelled 797164 with `dashboardBucket:"Will"`. A closed action isn't on the dashboard;
the bucket should be null or omitted for `C`/`X` actions.

**Resolution:** shared action serialization returns `dashboardBucket:null` for closed actions; open-action bucket
behavior is retained.

### P-15: Minor output inconsistencies in 5.12.1 reads

**Status:** Date formatting addressed in 5.12.2; unknown historical modification dates retained
**Found:** 2026-10-26 (production, 5.12.1 verification)

- `lastModifiedAt` is null on every pre-existing project checked. Projects created through
  `create_project` do get it (49089). Probably historical data, not a bug.
- `scheduledDate` is returned as a timestamp (`2026-10-28T00:00:00Z`) while inputs and the
  contract use `yyyy-MM-dd`.

**Resolution:** action schedule/deadline/target fields now return `yyyy-MM-dd` across project/planning/search
reads; instants retain ISO timestamps. This is an intentional read-contract change. Unknown `lastModifiedAt`
values remain null, without a fake backfill. Production verification is pending.

### P-11: Narratives written through the MCP were saved with contact_id 0 and broke the dashboard

**Status:** Addressed (2026-10-03, version 5.10.9)
**Found:** 2026-10-03 (production, reported by Nathan)

The dashboard failed with `ObjectNotFoundException: No row with the given identifier
exists: [ProjectContact#0]` in `DashboardNowColumnService.buildNarrativeItems`.

**Cause:** `McpWebUserSupport.requireWebUser` loads the `WebUser` by query, so its
unmapped `projectContact` field is null (only web logins fill it in). Both narrative
create paths, `add_project_narrative` (`McpProjectNarrativeCrudService`) and
`save_work_day_review` (`ProjectNarrativeService`), called only
`setContact(webUser.getProjectContact())`. In `project_narrative`, `contact_id` is written
from the `contactId` property (the `contact` association is insert/update false), so
each narrative created through the MCP was saved with `contact_id = 0`. This is the same
missing-contact problem fixed for `create_action` in `20e8c0e`.

**Fix:** `requireWebUser` now loads the user's `ProjectContact` by `contactId`, so every
MCP tool sees the same `WebUser` a web session does, and both narrative create paths
also set `contactId` directly. Tests: `McpWebUserSupportTest`.

**Data fix (production):** Nathan is the only MCP user, so the existing rows can be
repaired with `UPDATE project_narrative SET contact_id = <Nathan's contact_id> WHERE
contact_id = 0`. To verify after deploying 5.10.9: add a narrative through the MCP and
confirm the dashboard shows it with Nathan as the author.

### P-10: The UI blocks changing cadence on projects linked to other projects

**Status:** Addressed (2026-10-01)
**Found:** 2026-09-30 (in the app, reported by Nathan)

Nathan couldn't change Building Bridges' review cadence in the UI because the project is
linked to another project (shared with Philippines, workspace 7). He believes the check
is unintended for cadence. `update_project_review_cadence` changed it without trouble
(Week to Month). Cadence is per person (`ProjectContactAssigned.updateDue`), so the
link shouldn't block it.

### P-8: Review cadence can be set but not read

**Status:** Addressed (2026-10-01)
**Found:** 2026-09-30

`update_project_review_cadence` exists, but no tool returns a project's current cadence.
`get_project_context` has no cadence field, and there's no project list (I-1). Nathan
sorted his projects into cadences (Week = highest priority, Two Weeks = next) to drive a
health review, and the assistant can't see that sorting. Quick fix: add `reviewInterval`
(and the next review due date) to `get_project_context`. Full fix: I-1.

### P-9: Building a project health view hits the rate limit

**Status:** Addressed (2026-10-01)
**Found:** 2026-09-30

Without a project list (I-1), the assistant has to discover project ids from planning
and time data and then call `get_project_context` once per project. For about 50
projects plus discovery calls, that exceeded `limit=60 requests per minute` (HTTP 429).
I-1 would make this a single call.

Related: `recentActionTaken` only reflects completed actions, so a project with logged
time but no completed action (for example, Dandelion Daily on 2026-09-30, 106 minutes)
looks inactive. A health view should use the last billed time as well.

### P-7: Clearing a review field doesn't remove the narrative

**Status:** Addressed (2026-10-01)
**Found:** 2026-09-30 (in the app, from reading the code; not tested)

In `ProjectNarrativeReviewServlet.saveNarratives`, Decisions, Insights, Risks, and
Opportunities are only upserted when the text is non-empty. If Nathan clears a field
that already has a narrative for that day and saves, the old narrative stays. The same
semantics should apply in the UI and in the proposed MCP tool (I-10): an emptied field
removes that day's entry.

### P-1: Tools that return a list fail MCP schema validation

**Status:** Resolved 2026-09-30 (see Resolved)
**Found:** 2026-09-30
**Affects:** `get_day_availability`, `list_outlooks` (probably any tool whose result is a list)

The client rejects the whole result with:

```text
MCP server "dandelion" returned a malformed result that failed schema validation:
Invalid result for tools/call: expected record, received array (path: structuredContent)
```

**Cause:** `McpResource.toolSuccessResult()` sets
`result.set("structuredContent", mapper.valueToTree(value))`. When `value` is a `List`,
`structuredContent` becomes a JSON array. The MCP spec requires `structuredContent` to be
a JSON object, so the client discards the result, including the text block that would
have worked.

**Fix options:**
- Wrap list results in an object, e.g. `{"days": [...]}` or `{"outlooks": [...]}`.
  This is the most useful for clients.
- Or only set `structuredContent` when the node is an object and rely on the text block
  otherwise.

**Impact:** Planning can't check real availability. The assistant falls back to the
documented 8-hour weekday default, which may not match (see P-5). Outlook history can't
be reviewed.

### P-2: `get_planning_context` gets too large to use over a week

**Status:** Addressed (2026-10-01)
**Found:** 2026-09-30

An 8-day range (2026-10-02 to 2026-10-09) returned about 77,000 characters, which exceeds
Claude Code's per-result limit. The client saved it to a file and the assistant had to
parse it with a script. Most of the volume is template-managed routine items (prayers,
brushing teeth, email clearing, etc.) repeated every day with full field sets.

**Ideas:**
- `excludeTemplateManaged` or `workOnly` flag (for example, only actions whose project has
  a bill code).
- A compact mode returning only id, project, description, type, estimate, and dates.
- A per-day summary block (minutes by meetings, routine, tasks, and might), so load
  questions don't need the full list. See I-3.

### P-3: Some action descriptions look truncated

**Status:** Addressed (2026-10-01)
**Found:** 2026-09-30

Several descriptions end mid-phrase, as if a linked name or mention was dropped during
serialization:

| actionNextId | Description as returned |
| --- | --- |
| 796154 | "setup meeting agenda for" (since renamed) |
| 796000 | "setup project on" |
| 795750 | "brainstorm new name for this concept on" |
| 795969 | "review" (since removed) |
| 796162 | "with AIRA Co-Ag call" (meeting phrasing, probably fine) |

**To check:** whether these contain a link/mention token in the database or UI that the
MCP output strips. If so, include the linked name (and URL) in the output.

### P-4: `apply_changes` results don't return the new `asOf`

**Status:** Addressed (2026-10-01)
**Found:** 2026-09-30

After an `update_action`, the action's `asOf` changes, but the result only returns
`status: applied`. A follow-up change to the same action needs a fresh read first (this
happened with action 796106: update, then reschedule). `create_action` returns
`createdActionNextId` but also no `asOf`.

**Idea:** Include the new `asOf` (and ideally the updated action) in each result entry.

### P-5: Daily capacity default is unclear

**Status:** Addressed (2026-10-01)
**Found:** 2026-09-30

`get_day_availability` documents 8 hours on weekdays by default, but
`get_time_allocation_context` reports `obligatedMinutes: 2250` for the week (37.5 hours,
or 7.5 hours a day). With P-1 blocking availability reads, the assistant can't tell which
is right for a given day.

### P-6: Duplicate action created at the same timestamp

**Status:** Addressed (2026-10-01)
**Found:** 2026-09-30 (probably a Dandelion app issue rather than the MCP)

Actions 796171 and 796172 (Dandelion Daily, "continue with Phase 3", MIGHT, 20 min,
scheduled 2026-10-01) both had `asOf` 2026-09-30T20:45:24Z. Only 796171 had notes
(a `claude --resume` line). Nathan didn't create a duplicate intentionally. Possible
double submit in the create or "add note" flow. 796172 was removed through the MCP.

## Ideas

### I-12: Read and set an action's link (`linkUrl`) through the MCP

**Status:** Partly verified. Fix implemented 2026-10-01 in version 5.10.6 and deployed.
Build item 13 verified 2026-10-03 (see Resolved); build item 14 waiting on verification.
**Raised:** 2026-10-01 (Nathan)

`ActionNext.linkUrl` (column `link_url`, up to 1200 characters) is in the model. The
dashboard Now column shows it (`DashboardNowColumnService`), and template-generated
instances copy it from their template (`TemplateGenerationService`). The MCP neither
returns it nor lets the assistant set it.

A link to a PR, ticket, chat thread, or document is often the first thing needed to act
on an item. If the assistant can see it, it can open the work directly when helping
plan or review, and if it can set it, it can attach the PR or doc it just created to the
action that tracks it.

Suggested change:

- Return `linkUrl` on every action in `get_planning_context` and `get_project_context`
  (null or empty when not set).
- Add an optional `linkUrl` to `create_action`, `update_action`, and to each entry in
  `split_action` `newActions`. On `update_action`, an empty string or null clears it.
  Validate it as an http(s) URL of at most 1200 characters.
- Template-generated instances: `update_action` currently rejects all template-managed
  actions. Like `order_day` and `complete_action`, setting the link (and `addNote`) on a
  generated **instance** should be allowed, because it describes today's occurrence and
  doesn't change the template. Template roots stay blocked.
- Write an `ActionChangeLog` entry when the link changes, as for other updates.

**Fix (2026-10-01, version 5.10.6, not yet deployed):**

- Build item 13: every action in `get_planning_context` and in `get_project_context`
  `openActions` now includes `linkUrl`, read straight from `ActionNext.linkUrl`. It's null
  or "" when no link is set.
- Build item 14: `create_action`, `update_action`, and each `split_action` `newActions`
  entry take an optional `linkUrl`. A non-blank value must be an absolute http or https
  URL of at most 1200 characters; anything else rejects the batch with a reason. On
  `update_action`, "" or null clears the link (stored as "", as the UI does).
- An `update_action` that sets only `linkUrl` and/or `addNote` is now accepted on a
  template-generated instance. It changes that occurrence only; the template root isn't
  touched. Template roots are still rejected, and so is any update to an instance that
  includes `description`, `nextActionType`, or `estimateMinutes`.
- The update's `ActionChangeLog` entry records the link change as its reason ("Set link
  to ..." or "Cleared link").
- Not changed: like other MCP updates, a link set on an action in a SHARED action set
  isn't copied to the linked projects' copies (the dashboard edit form does copy it).
- Tests: `McpApplyChangesServiceTest` covers the URL check.

To verify after deployment: run the **Verify** lines for build items 13 and 14, then
move this entry to Resolved.

### I-11: Set the order of the day's work (`completionOrder`) through the MCP

**Status:** Addressed. Deployed and verified 2026-10-01 (build items 11 and 12; see
Resolved).
**Raised:** 2026-10-01 (Nathan)

The assistant can put actions on a day but can't say what order to do them in. The data
model already supports this: `ActionNext.completionOrder` (column `completion_order`) is
the per-day sequence. The dashboard Today and Now columns sort by it within each bucket
(`DashboardTodayColumnService`, `DashboardCurrentActionService`), the dashboard's up/down
buttons swap it, and the Project Health edit form sets it. `ActionNext.priorityLevel` also
exists, but it only breaks ties after the action-type default priority, so it isn't
an ordering control.

Today the MCP neither reads nor writes either field: `get_planning_context` action output
has no `completionOrder` or `priorityLevel`, and `apply_changes` has no way to set them.
`apply_changes` sets `priorityLevel` from the type default on create and split, and
leaves `completionOrder` at 0.

Suggested change:

- Return `completionOrder` (and `priorityLevel`) on each action in `get_planning_context`
  and `get_project_context`, and sort a day's actions the same way the dashboard does.
- Add an `order_day` change type to `apply_changes`: `{type, date, actions: [{actionNextId,
  asOf}, ...]}`. It writes `completionOrder` 1..n in the given order, and the rest of that
  day's actions follow in their current order. Alternatively, add an optional
  `completionOrder` to `update_action` and `reschedule_action`.
- **Decided (Nathan, 2026-10-01): order within buckets; dashboard behavior stays as is.**
  `sortProjectActionListByCompletionOrder` sorts by bucket first (start of day, overdue,
  committed, WILL, MIGHT, waiting, meetings, end of day), and an order set through the MCP
  applies only within a bucket. The tool description should say so, and the read output
  should include each action's bucket (or return the day already grouped) so the
  assistant can see the groups it's ordering within. `rationalizeCompletionOrderForCurrentDate`
  already keeps orders that were set (> 0) and only fills in the zeros, so the order the
  MCP sets won't be overwritten.

**Fix (2026-10-01):**

- New shared class `DashboardActionOrdering` holds the Today column's bucket rules and
  in-bucket sort. `DashboardTodayColumnService` now delegates to it (same logic, moved),
  and the MCP uses it too, so the order the assistant reads and writes can't drift from
  the dashboard.
- Build item 11: every action in `get_planning_context` and in `get_project_context`
  `openActions` now has `completionOrder`, `priorityLevel`, and `dashboardBucket` (Start of
  Work Day, Overdue, Committed, Will, Personal (Morning), Might, Waiting, Will Meet, End of
  Work Day, Other, or Not on Dashboard). `get_planning_context` sorts each day the way the
  dashboard does (overdue actions too), and adds `dayOrder`: per day, the buckets in
  display order with their action ids in completion order.
- Build item 12: `apply_changes` has a new `order_day` change:
  `{type: "order_day", date, actions: [{actionNextId, asOf}, ...]}`. The listed actions go
  first in their own bucket in the order given; the rest of each bucket keeps its current
  order; bucket order never changes. It then numbers `completionOrder` 1..n down the whole
  day (the same numbering the dashboard's rationalize step uses). Each action must be open
  and scheduled on the date (overdue actions count when the date is today). Template
  roots are rejected; template-generated instances can be ordered. The result returns
  `orderedActions` and `renumberedActions` with their new `completionOrder` and `asOf`
  (renumbering changes `asOf`, like the dashboard's up/down buttons). Each ordered action
  gets an `ActionChangeLog` entry.
- Tests: `DashboardActionOrderingTest`.

### I-10: Work day review through the MCP

**Status:** Addressed (2026-10-01)
**Raised:** 2026-09-30 (Nathan)

Let an agent run the end-of-day work day review (`ProjectNarrativeReviewServlet`): look
at the day, get project context, ask clarifying questions, and fill in the Notes,
Decisions, Insights, Risks, and Opportunities for each project, with Nathan's approval.
These narratives feed the daily report and the project's running history.

How the review works today (from the code):
- The review list is the projects with at least `MINUTES_REVIEW_THRESHOLD` billed
  minutes (`BillEntry`) on the date, sorted by minutes
  (`ProjectNarrativeDao.listReviewItemsForDate`).
- A project counts as reviewed when it has any narrative on that date **and** its setup
  is complete: `ProjectContactAssigned.updateDue > 0`, outcome, and success criteria.
- Each project shows completed actions (`ActionTaken` on the date) and deleted actions
  that had time.
- Saving upserts one narrative per verb per day. NOTE is always written (default
  "Reviewed/no comments"), which is what marks the project reviewed. Narrative times are
  the start of the day plus a few seconds, to keep the order.

Recommended tools:

1. **`get_work_day_review(date)`** (read). Mirrors the review screen for a whole day:
   - total minutes for the day, and the threshold applied;
   - per project, in minutes order: id, name, minutes, reviewed flag, and **why** it
     isn't reviewed yet (no narrative, or setup incomplete with the missing pieces named);
   - completed actions: description, completion note, completion time, and minutes when
     the time entry is linked to the action;
   - deleted actions with time;
   - the existing narratives for that date by verb, each with its id and `lastUpdated`;
   - optionally, projects below the threshold (`includeBelowThreshold`).
   Return an object (see P-1).

2. **`save_work_day_review(date, projectId, entries)`** (write, after approval). Same
   semantics as the review screen, so the UI and the MCP behave identically:
   - `entries` has optional `note`, `decision`, `insight`, `risk`, `opportunity`;
   - an omitted field is left unchanged; an empty string removes that verb's entry for
     the day;
   - NOTE gets the default text when nothing else marks the review;
   - use each existing narrative's `lastUpdated` as a staleness check.
   Best done by moving the save logic out of the servlet into a shared service that both
   the servlet and the MCP call.

3. **Narrative ids in `get_project_context`.** Recent narratives currently come back
   with date, verb, and text but no id, so the agent can't edit or delete them (I-5).

4. **Daily report status.** The daily report (`TrackerNarrative`) is built from these
   narratives. The review tool should say whether the day's report was already generated
   or approved, so the agent can tell Nathan when an edit means the report needs
   regenerating.

5. **Setup gaps.** When a project can't be marked reviewed because its setup is
   incomplete, the agent can fix the outcome and success criteria with
   `update_project_language`, and the review cadence with I-7.

How an agent would run it: read the day's review, then for each project (largest first)
read its context, ask Nathan one to three questions, draft only the fields that matter,
get approval, and save. Projects with nothing interesting get the default note.

Relationship to I-5: this is the day-scoped version of narrative editing. I-5 is still
needed for correcting or removing older narratives. Both should share one service.

### I-9: Action attention modes (Focus, Concurrent, Listen)

**Status:** Addressed (2026-10-01)
**Raised:** 2026-09-30 (Nathan)

A model change beyond the MCP, written up in `docs/action-attention-modes.md`. Once it
exists, the MCP should expose each action's mode, report load by mode, and let the
assistant suggest refilling the concurrent and listening queues. Supersedes I-6.

### I-8: Read daily and weekly narratives

**Status:** Addressed (2026-10-01)
**Raised:** 2026-09-30 (agreed with Nathan)

Expose the generated period narratives (`TrackerNarrative`) read-only, for example
`get_narratives(periodType, fromDate, toDate)`. They're high-level and span projects and
tasks, which gives context the per-project tools don't: what actually happened across
the week, recurring themes, and where attention went compared with the plan. Most useful
when reviewing last week before setting the next week's outlook.

Suggestions:
- return `markdownFinal` when present, otherwise `markdownGenerated`, with
  `reviewStatus` so the assistant knows whether Nathan approved it;
- include `periodStart`, `periodEnd`, `narrativeType`, and `displayTitle`;
- return an object, not a bare list (see P-1).

### I-7: Change a project's review cadence

**Status:** Addressed (2026-10-01)
**Raised:** 2026-09-30 (Nathan)

Let the assistant move a project between review cadences (the `ReviewInterval` values:
Week, Two Weeks, Month, Two Months, Four Months, Year, or none; stored per person as
`ProjectContactAssigned.updateDue`), for example moving a
project from weekly to monthly checking as priorities shift. This is the same grouping
the Project Health page uses. Pairs with I-1: read cadence and health, then propose
cadence changes for approval. Should require explicit approval, like
`update_project_language`.

### I-1: List projects with health, priority, and week focus

**Status:** Addressed (2026-10-01)
**Raised:** 2026-09-30 (Nathan)

There's no way to discover projects. The assistant only knows project IDs that appear in
planning results. A `list_projects` tool should return, per project:

- id, name, handle, status, bill code and funding source;
- priority level and review cadence (`ReviewInterval`, as grouped on the Project Health
  page);
- health: last activity date, whether it's overdue for attention against its cadence,
  open action count, overdue actions, and waiting items;
- whether it's a current **week** (top-priority) project.

This would let the assistant help Nathan focus on the projects that matter most, notice
neglected ones, and challenge whether the week projects are still the right ones.

### I-2: Surface waiting items and what they gate

**Status:** Addressed (2026-10-01)
**Raised:** 2026-09-30

Nathan uses WAITING actions that hide follow-on work until they're done (for example,
InteropHub Communication Bundles: the next phases appear only after the first phase is
deployed and tested). The assistant can't see that anything is queued behind a waiting
item, so it can wrongly conclude a project has no planned work. Idea: include waiting
items with a short "unblocks" summary in `get_project_context` and in I-1 health.

### I-3: Day load summary tool

**Status:** Addressed (2026-10-01)
**Raised:** 2026-09-30

Most planning questions are "am I overbooked?" A tool returning, per day: available
minutes, meetings, routine (template) minutes, committed tasks, might tasks, and overdue
items would answer that directly and avoid P-2.

### I-4: Funding-source drift watch

**Status:** Addressed (2026-10-01)
**Raised:** 2026-09-30

The time report already has targets and actuals by bill code. Useful additions:
- flag bill codes drifting from target over the 4-week window;
- note which gaps Nathan controls and which are externally driven (for example, CDC
  Technical Assistance depends on incoming requests, so Nathan can't raise it alone);
- note when targets are under review (percentages may change pending discussion with
  Nathan's manager).

### I-5: Create, edit, and delete project narratives through the MCP

**Status:** Addressed (2026-10-01)
**Raised:** 2026-09-30
**Priority:** Critical (Nathan)

This means project narratives (`ProjectNarrative`: dated entries with a verb), not the
generated daily/weekly narratives (`TrackerNarrative`, see I-8). `get_project_context`
already returns recent narratives, but there's no way to write them.

Needed:
- **add** a narrative (verb: NOTE, DECISION, INSIGHT, RISK, OPPORTUNITY, etc.; date; text);
- **update** a narrative's text, verb, or date;
- **delete** a narrative.

Editing and deleting matter as much as adding: things change constantly, and outdated
narratives regularly need to be corrected or removed so planning isn't fed stale
information.

Why it matters: narratives are included in planning, so decisions and insights made in a
planning conversation (like the Conformance Reboot decisions on 2026-09-30) belong there
rather than in AI thoughts, which are the assistant's own notes. Nathan built narratives
but hasn't used them much yet; MCP access would make them part of the normal workflow.

Writes should require explicit approval of the wording, like `update_project_language`.
Consider returning the narrative id from add, and using `lastUpdated` as a staleness
check on update and delete.

### I-6: Mark dev tasks as agent-runnable

**Status:** Addressed (2026-10-01)
**Raised:** 2026-09-30

Nathan keeps specced dev tasks (for example, InteropHub export, Dandelion Phase 3) as
MIGHT and runs them with an agent during meetings. A flag or tag for "agent-runnable
during meetings" would let the assistant leave them out of load totals, or suggest them
for meeting-heavy days, instead of treating them as overflow.

## Verified Working

**2026-09-30:**
- `get_planning_context` (single day, and filtered by project)
- `get_project_context`
- `get_time_allocation_context`
- `get_outlook` (single week and month; both empty)
- `update_project_language` (project 48903, with `lastModifiedAt` null, so no `asOf`
  needed; result returned the new `lastModifiedAt`)
- `add_project_ai_thought`: returned noteIds 1 and 2. This is the first live test of the
  AI-thoughts feature.
- `apply_changes`: `reschedule_action`, `update_action` (description, estimate, type,
  addNote), `remove_action` and `create_action`, including a 10-change batch

Not yet exercised: `set_outlook`, `set_day_availability`, `update_project_ai_thought`,
`delete_project_ai_thought`, `split_action`, `complete_action`.

## Resolved

**Addressed as of 2026-10-01:** all problems (P-1 to P-10) and ideas (I-1 to I-11).

**Verified 2026-10-26 against production (5.12.1, checklist steps 2-5 in
[mcp-project-and-action-requests.md](mcp-project-and-action-requests.md#owner-run-deployment-and-agent-review)):**
- **Tools:** `list_projects`, `search_actions`, and the new `apply_changes` fields
  (`create_project`, `projectRef`, `allowDuplicate`, `projectHandle`) are advertised.
- **`list_projects`:** "AIRA AI Development" (48682, no open dated work) is found by name,
  closing the discovery gap in I-1. `scope:SHARED` finds
  "Immunization CDS (ImmDS + HALO)" with `externalManaged:true` (see P-12 for duplicates).
  `hasMore`/`nextOffset` pages correctly; `nameContains:"%"` returns nothing (literal).
- **`get_project_context`** works for shared project 48968 (tags, recent work).
- **`search_actions`:** text plus notes found 797163/797164. A completed action (796640,
  scheduled 10-26) matched a 10-19 to 10-23 range by its recorded work date (see P-13).
- **`create_project` + move + cancel in one batch:** created Repository (49089, private,
  AIRA-GA, Active, handle "Repository"), moved Repository Touch Point 797163 into it via
  `projectRef`, and cancelled placeholder 797164. 797163 kept its ID, date, estimate, notes,
  link, and `completionOrder`, and returned a new `asOf`. It had no linked meeting, so
  meeting preservation on move is not yet verified.
- **Not yet exercised:** moving an action with a linked meeting, historical time after a
  move (step 6), and the rejection cases (step 7).

**Verified 2026-10-03 against production (I-12, build item 13):**
- `get_planning_context` for 2026-10-05 (meetings only) returned `linkUrl` on action
  796221 (SS + NB Weekly Check-in) with its Teams meeting link.
- Build item 14 (setting, changing, and clearing a link through `apply_changes`,
  including on a template-generated instance) needs writes to production data and is
  not yet verified.

**Verified 2026-10-01 after deployment (I-11):**
- **Build item 11:** `get_planning_context` for 2026-10-01 returns `completionOrder`,
  `priorityLevel`, and `dashboardBucket` on every action, plus `dayOrder`. The Will group
  came back as review FHIR chat (7), clean my office (8), then the rest, which matched the
  dashboard.
- **Build item 12:** `order_day` moved clean my office (795677) ahead of review FHIR chat
  (795674) in the Will group. Both are template-generated instances, and both were
  accepted. Nathan confirmed the new order on the dashboard. The result listed 37
  `renumberedActions`: the 0-order items, including Not on Dashboard and overdue
  personal items, were numbered too. Nathan confirmed this is fine: they're numbered
  in the same order they were already served in, so nothing visible changes, and it only
  affects that one day.
- Client note: after `/mcp` reconnect, the session's copy of the `apply_changes` schema
  still didn't list `order_day`, but the call went through and the server accepted it.

**Verified 2026-09-30 after deployment** (read-only checks; the server now lists 20 tools):
- **P-1** (build item 1): `get_day_availability` returns `{"days": [...]}` and
  `list_outlooks` returns `{"outlooks": [...]}`; no more schema errors.
- **Build item 2:** `get_work_day_review` returns the threshold, total minutes, and per
  project the minutes, reviewed flag, `notReviewedReason`, `missingSetup`, completed and
  deleted actions, and narratives by verb. Matches the 2026-09-30 review list.
- **Build item 5:** `get_project_context` narratives include `narrativeId` and
  `lastUpdated`.
- **Build item 7:** `get_work_day_review` includes `dailyReport` (`exists: false` for
  2026-09-30).
- **Build item 8:** `get_narratives` returns weekly narratives with title, period,
  `reviewStatus`, `isFinal`, and text.

- **P-4** (build item 10): `apply_changes` results now include `asOf` for each change,
  including creates.
- **Build item 9:** `update_project_review_cadence` moved Building Bridges from Week to
  Month and IG Expert Group from Week to Two Weeks; results include `previousDays`.

Present but not yet exercised (write tools): `save_work_day_review`,
`add_project_narrative`, `update_project_narrative`, `delete_project_narrative`. P-7 and P-4 not yet checked.

Note: a Claude Code session only sees tools that existed when it started. After a deploy
that adds tools, restart the session (or reconnect with `/mcp`) before testing them
through the client.
