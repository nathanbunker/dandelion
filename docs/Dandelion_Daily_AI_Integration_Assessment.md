# Dandelion Daily AI planning integration — codebase assessment

Answers the questions raised in `docs/Dandelion_Daily_AI_Integration_Codebase_Assessment.md`. This is analysis only — nothing in the codebase has been changed.

**Status: Phases 0, 1, and 2 implemented and verified end-to-end against a live local deployment (2026-09-30). Phase 3 implemented 2026-10-03 in version 5.10.8; not yet deployed to production.**

Phase 0: all 8 tools (`get_project_context`, `get_planning_context`, `get_outlook`, `list_outlooks`, `set_outlook`, `get_time_allocation_context`, `get_day_availability`, `set_day_availability`) passed an end-to-end test against a local deployment with real data — protocol handshake, auth rejection, the frozen-outlook-period rule, day-availability read/write (including the template-regeneration side effect), time-allocation context, and error handling all confirmed working. One bug was found and fixed during testing: `ActionNext.nextActionDate`/`nextDeadlineDate`/`nextTargetDate` are Hibernate `type="date"` and come back as `java.sql.Date`, whose `toInstant()` throws `UnsupportedOperationException` — `McpActionContextSupport.toIso()` now builds the `Instant` from `getTime()` instead.

Phase 1: `project_ai_note` table added; `ProjectAiNoteService` (shared by MCP and UI) backs three new MCP tools (`add_project_ai_thought`, `update_project_ai_thought`, `delete_project_ai_thought`) and a new "AI Thoughts" section on the project dashboard page (view/edit/delete, modeled directly on the existing "Blockers" section's collapsible-chip UI pattern). `get_project_context` now includes an `aiThoughts` list. Live-tested 2026-09-30 (`add_project_ai_thought` returned noteIds 1 and 2; see `docs/MCP-Feedback.md`, Verified Working).

Phase 2: `Project.lastModifiedDate` added (and bumped at every active human project-language edit path: `handleLanguageReviewApply`, `handleSaveProject`, and both `ProjectHealthServlet` patch-apply paths -- the external "Patch"-workspace sync paths and the legacy `ProjectEditServlet` are not covered, a deliberate scoping decision). `update_project_language` added. `apply_changes` added, covering `create_action`/`update_action`/`reschedule_action`/`split_action`/`remove_action`/`complete_action`, atomic (validate-all-then-apply-all, nothing partial), with template-managed restrictions and staleness checks per the design. `ActionCompletionService.closeAction`/`validateCompletion` were refactored (extract-method, behavior-preserving, confirmed by the existing `ActionCompletionServiceTest` suite) to expose AppReq-free core methods `applyCompletion`/`validateCompletion(WebUser, Session, ...)` for MCP to call without a web session. Also fixed a latent transaction-safety gap affecting every mutating tool since Phase 0: `McpResource` now rolls back the ambient transaction whenever a tool call throws, so a tool that mutates something and then fails can no longer have that partial mutation silently committed.

Two rounds of live testing against real data found and fixed: (1) `get_project_context`/`get_planning_context` never exposed the `asOf` value `apply_changes` needs for staleness checks -- found before testing even started; (2) a timestamp-precision mismatch (MySQL's `datetime` columns drop fractional seconds, but `update_project_language`'s response echoed the in-memory millisecond-precision value) that made a freshly-returned `asOf` spuriously fail as stale -- fixed via `McpActionContextSupport.truncatedNow()`, applied everywhere a version-marker field is set; (3) `complete_action`'s default `completedAt` was treated as the start of the time entry rather than the end, so any positive `durationMinutes` with no explicit `completedAt` always failed validation -- fixed by deriving the start from the end minus the duration; (4) `remove_action`'s `reason` was parsed but never written to `ActionChangeLog.change_reason` -- fixed. Batch atomicity, staleness rejection, soft-cancel (never hard delete), split grouping, and template-managed restrictions were all confirmed working end to end, including the critical all-or-nothing check (a batch with one valid and one stale item applied neither). A pre-existing, unrelated dashboard quirk was reconfirmed (the project page can show a different project's data than the one requested, per `DashboardNowColumnService`'s existing "current action's project wins" behavior) -- not fixed, out of scope for this work.

Post-Phase 2: further tools and fixes (work day review, narrative CRUD, review cadence, day ordering, action links, and more) are tracked in `docs/MCP-Feedback.md`.

Phase 3 (planned and implemented 2026-10-03, version 5.10.8; see §5 and §6, decisions 19-23). Each step was committed separately; 313/313 tests pass.

- **3.1 Outlook start dates.** `PlanningOutlookService.requireValidPeriodStart`/`periodStartFor`, enforced in `getOutlook` and `setOutlook`. `get_outlook` and `set_outlook` return `invalid_arguments` with the suggested start date.
- **3.2 Outlooks page.** `OutlooksServlet`, linked from the home page under Time Management & Reporting and from Plan Ahead. Editors for this and next month and this and next week (next month was added so a month's outlook can be written before it starts); the last 8 weeks and 6 months are read-only. Each period saves on its own. A save is refused if the outlook changed after the page loaded (for example, through the MCP); the typed text stays on screen with the saved version shown beneath it. Browser-tested locally: save, refused stale save, a non-Sunday start, and an ended week all behaved as designed.
- **3.3 In-app chat context.** `ProjectDashboardAiContextService.buildContextText()` adds "AI Thoughts" (up to 20, labeled as assistant observations, not facts or decisions) and "Planning Outlook" (current month and week, marked as cross-project). Verified locally: the project chat ("Other" mode) quoted both sections.
- **3.4 Outlooks feed the weekly narrative.** `GenerationContext` carries the reported week's outlook, the next week's outlook, and the outlooks for the month(s) the week falls in; `TrackerNarrativeGenerator` loads them for WEEKLY narratives only. The WEEKLY prompt (`weekly-supervisor-v2`) adds the outlook input blocks, the intent-not-evidence and never-quote rules, a "Plan vs. Actual" section (left out when the week has no outlook), and has "Next Week" lead with the next week's outlook; target length is now 300-500 words. The `set_outlook` description says outlooks feed the weekly narrative. A live narrative test was deliberately skipped (no real outlooks exist yet); Nathan will verify it in normal week-by-week use.
- **3.5 Outlook on Plan Ahead.** A collapsible banner under the header shows the outlook for the week containing the first displayed day (open when one exists, otherwise a collapsed "none written yet" line with a "Write one" link), with that month's outlook collapsed inside; an "Outlooks" button sits next to Manage Templates. Browser-checked locally, including the switch to next week's outlook when the window starts on Sunday.
- **3.6 Wrap-up.** MCP-Feedback I-12 build item 13 (`linkUrl` in reads) verified against production; build item 14 (setting links through `apply_changes`) still needs a production write test. Version bumped to 5.10.8 (no schema change).

**Bottom line: this is doable, and cheaper than the prompt implies.** The two hardest-sounding pieces — a task-oriented API surface with auth, and a propose/review/apply workflow for AI-authored edits — already exist in working form (`/api/*` + `ApiKeyAuthFilter`, and `ProjectReviewChatService`'s three chat modes). The main net-new work is: one new table for outlooks, one new table for AI project-thoughts, and finishing a batch-apply-with-staleness-checks operation that today only exists as an unfinished skeleton (`ActionProposal`/`ActionChangeLog`) plus several one-entity-at-a-time precedents.

---

## 1. Map of existing code and what it already supports

### Transport, auth, and the API layer

- `/api/*` is a real, working JAX-RS (Jersey) API, mounted via a `ServletContainer` in `web.xml:280-288`, scanning `org.openimmunizationsoftware.pt.api.v1.resource` and `api.common`. Existing resources: `ProjectsResource`, `ActionsResource`, `ProposalsResource`, `ProjectNarrativesResource`, `TrackerNarrativesResource`, `SyncResource`, plus OpenAPI/Swagger bootstrap.
- Every `/api/*` request is wrapped in one Hibernate `Session`/`Transaction` by `HibernateSessionFilter` (`HibernateSessionFilter.java:14-64`, mapped in `web.xml:567-570`), opened before the JAX-RS filter chain and committed/rolled back after, bound via `HibernateRequestContext`.
- Auth is API-key based, already separate from cookie/session web login: `ApiKeyAuthFilter` (`ApiKeyAuthFilter.java:13-59`) reads an `X-Api-Key` header, looks up `WebApiClient` via `WebApiClientDao.findByApiKey()`, and binds a thread-local `ApiRequestContext.ApiClientInfo` (`clientId`, `username`, `workspaceId`, `agentName`). `WebApiClient.java:1-101` has `apiKey`, an owning `WebUser`, a single `workspaceId`, a free-text `agentName` (already used as the audit "who proposed this" label at `ActionsResource.java:84`), `enabled`, and usage timestamps. There is no per-key scope/permission list — a key is all-or-nothing within its one workspace.
- Framework is Jersey/`javax.*` (Servlet 2.5 in `web.xml:3`), Jackson for JSON, Swagger for docs. **No MCP SDK or JSON-RPC library is in `pom.xml`.**
- Existing services called by resources are plain POJOs with no HTTP coupling (e.g. `ActionProposalService.java`), running raw HQL against `HibernateRequestContext.getCurrentSession()` — directly callable from a new MCP resource without going through HTTP, as long as it runs inside the same session/transaction wrapper.
- `SyncResource` (`SyncResource.java:1-149`, `/v1/sync/*`) is a working batch-apply precedent: takes an `items` array, returns a `SyncBatchResponse` with a per-item `SyncBatchItemResult` (status/message) and aggregate counts. Its matching logic (external-id upsert) doesn't apply to AI-approved edits, but its **response shape is a direct template** for the new batch-apply tool.

### Data model already in place

- **Project language fields already exist as native columns**, not a blob and not the fact-value system: `Project.java` has `description` (:17), `currentFocusText` (:18), `outcomeText` (:19), `successCriteriaText` (:20), plus `projectName`/`projectHandle`, `projectStatus`, `workspaceId`, `priorityLevel`. `ProjectFactValue`/`ProjectFactDefinition` is a separate, unrelated EAV system used by the project-health checklist/scoring feature — do not conflate it with project language.
- **Action classification is one field, not several booleans**: `ActionNext.nextActionType` (`ActionNext.java:26`), a string validated against `ProjectNextActionType` constants — `WILL`, `WILL_MEET` (= meeting), `WILL_CONTACT`, `WILL_REVIEW`, `WILL_DOCUMENT`, `WILL_FOLLOW_UP`, `MIGHT`, `WOULD_LIKE_TO`, `COMMITTED_TO`, `GOAL`, `WAITING`, `OVERDUE_TO`. Convenience predicates (`isWill()`, `isWillMeet()`, `isMight()`, `ActionNext.java:134-180`) exist. This is confirmed independently by `PlanAheadBoardService.toRowKey()` (`PlanAheadBoardService.java:606-625`), which buckets the exact same field into `ROW_MEETINGS/ROW_COMMITTED/ROW_WILL/ROW_MIGHT`.
- **Scheduling**: `nextActionDate`, `nextDeadlineDate`, `nextTargetDate`, `nextChangeDate` (last-modified timestamp), `rescheduleLocked`, `timeSlot` (`ActionNext.java:40-48`). Rescheduling away from today has special handling that zeroes `nextTimeActual` (`ActionNext.java:440-445`).
- **Estimate vs. actual is a real, existing distinction**: `nextTimeEstimate`/`nextTimeActual` on `ActionNext` (:27-28), and a separate durable log of completed work, `ActionTaken` (`ActionTaken.java:1-101`).
- **Notes**: `ActionNextNote` (one row per note line) aggregated via `ActionNext.getNextNotes()`/`setNextNotes()` — but `setNextNotes` **replaces the whole set** (`ActionNext.java:332-336`), so an "add a note" MCP operation must insert a new `ActionNextNote` directly, not resend the whole blob (unless it always resends full history).
  - *Superseded in 5.13/5.14:* notes are now one plain-text column, `action_next.next_notes`, written only through `ActionNotesService`; `ActionNextNote` and its table are gone.
- **Batch grouping already exists**: `ActionSet`/`ActionSetType` (`STANDARD`/`SHARED`/`ASK`) tags actions created together and is what `ActionCompletionService` uses to fan out completion across a `SHARED` set (e.g., one meeting logged against several projects).
- **Propose/audit scaffolding already exists but is unfinished**: `ActionProposal` (`ActionProposal.java:1-187`) has `clientId`, `modelName`, `requestId`, `proposedSummary`, `proposedRationale`, `proposedPatch` (JSON), `inputSnapshot` (a read-time snapshot — clearly built for optimistic concurrency), and `proposalStatus` (`NEW → SHOWN → ACCEPTED/REJECTED/SUPERSEDED`). `ActionChangeLog` (`ActionChangeLog.java:1-158`) is a field-level audit row with `actorType` (human vs. AI), `changePatch`, `changeReason`, linked back to the authorizing proposal. **But `ActionProposalService` only implements `createProposal` and `supersedeProposal` — there is no `acceptProposal`/`applyProposal` anywhere in the codebase**, and nothing in `org.dandeliondaily` reads `ActionProposal` at all. The skeleton for exactly what the assessment doc wants exists; the load-bearing part (turning an approved proposal into a real mutation + changelog row) was never built.
- **Two distinct, pre-existing narrative concepts, neither a clean fit for "AI thoughts on a project"**:
  - `ProjectNarrative` (`narrativeVerb`: `NOTE/DECISION/INSIGHT/RISK/OPPORTUNITY`, `providerId` field apparently meant for AI attribution) is used by `ProjectNarrativeReviewServlet` as a **one-row-per-(project, verb, day) daily-journaling upsert**, with a required (`not-null`) `contactId`. It's date-scoped, fixed-vocabulary, human-journaling-shaped — not a free-form, independently editable/deletable AI-observation log.
  - `TrackerNarrative` (`narrativeType`: `DAILY/WEEKLY/MONTHLY`, `periodStart`/`periodEnd`, `markdownGenerated` vs. `markdownFinal`, `reviewStatus`, `modelName`/`promptVersion`/`promptUsedText`) is the closest structural analog to the requested outlook — period bounds + AI draft + human approval — but `TrackerNarrative.hbm.xml` has **`project_id` and `contact_id` both `not-null="true"`**, hard-scoping it to one project. `WeeklyReportDataService.load()` already pulls the approved `WEEKLY` `TrackerNarrative` into the weekly report (`WeeklyReportDataService.java:58-59`) — this is the report-integration hook the assessment doc says can wait.
- **`WorkObligation` (the user's own uncommitted feature, `WorkObligation.java`, `v5.9.sql`)** is a simple `owner_user_id` + `week_start`-scoped table (obligated minutes for capacity planning) — unrelated to outlooks in purpose, but it is **exactly the schema pattern to copy** for a new outlook table: owner-scoped, no workspace/project foreign key, one row per period.
- Tenancy: `Workspace`/`WorkspaceMember` is the tenancy root; `Project` and `ActionNext` are scoped by `workspaceId`; `WebApiClient` binds to one `workspaceId`. `WorkObligation`/`WeeklyReport` instead scope by `owner_user_id` directly — both patterns are established, and the outlook (explicitly cross-project and personal) should follow the owner-scoped pattern.

### Application logic already in place

- **`ProjectReviewChatService`** (`org.openimmunizationsoftware.pt.manager.ProjectReviewChatService`) makes real server-side OpenAI calls (official Java SDK, default model `gpt-5.2`, `TrackerKeysManager`-configured API key) and already implements three propose→review→explicit-apply workflows, all driven from `DandelionDashboardServlet`:
  - **LANGUAGE_REVIEW** *is* "the process for reviewing project language" the assessment doc refers to. Its system prompt (`ProjectReviewChatService.java:41-71`) already encodes the doc's own field definitions (description = stable boundary, focus = ~1 month, outcome = stable why, success criteria = 3-7 observable markers) and explicitly forbids silent renames and autonomous edits. Applying is `handleLanguageReviewApply` (`DandelionDashboardServlet.java:698-765`): re-fetches `Project` fresh, checks `WorkspaceRegistry.canAdministerWorkspace(...)`, does a **partial update** (only fields present in the edited form are set), sets `lastModifiedByWebUserId`, commits. **No staleness check.**
  - **GENERAL** proposes `ProjectIssue`/`ProjectNarrative` suggestions; **NEXT_ACTIONS** proposes candidate `ActionNext` shapes, adopted one at a time via `handleNextActionsAdopt` (creates a single new `ActionNext`).
  - Every apply path here commits **one entity per transaction**, with no shared read-snapshot and no staleness detection anywhere — same gap noted for `ActionProposal.inputSnapshot` above.
- **`ProjectDashboardAiContextService.buildContextText()`** (`ProjectDashboardAiContextService.java:24-111`) is already, in substance, a `get_project_context` implementation: name, handle, status, description, focus, outcome, success criteria, tags, up to 15 recent `ActionTaken`, up to 20 open/proposed `ActionNext`, up to 20 open `ProjectIssue`, up to 20 recent `ProjectNarrative` — it just renders to a text blob for the LLM prompt instead of structured JSON. This is directly reusable/refactorable for an MCP read tool.
- **`PlanAheadBoardService.buildBoard()`** (`PlanAheadBoardService.java:108-212`) already computes almost exactly the requested cross-project date-range view: a rolling window from a caller-supplied start date, `ActionNext` bucketed into meetings/committed/will/might rows, day capacity via `PlanAheadDayCapacityService` + `TimeAdder`, overdue actions, and actual time spent today (via `BillEntry`). It is UI-model-oriented and session-bound (`AppReq.getWebSession()`), so it needs a thin data-only extraction for MCP use, but the query logic should be reused, not reimplemented.
- **`PlanAheadMutationService`** mutates exactly one `ActionNext` per call, each in its own transaction, with ownership (`isOwnedByCurrentWorkspace`) and mode-compatibility checks — but, again, **no staleness check anywhere**.
- **Action templates are already fully modeled, no new schema needed.** A template is just an `ActionNext` row with `templateType` set (a root; `isTemplate()` true) plus a 1:1 `ActionNextTemplateConfig` row (recurrence pattern, `missedActionBehavior`). Generated instances are ordinary `ActionNext` rows with `templateActionNextId` pointing back to the root and `templateType` null. Generation/cancellation/carry-forward runs as an **hourly background job** (`TemplateSchedulerListener`) independent of any request — instances can change between two MCP reads with no MCP involvement. Today's UI applies no template-awareness to ordinary single-action edits (`PlanAheadMutationService`'s regular mutators, including completion) — editing a template root is only possible via `TemplateManagementServlet`'s two actions, `saveTemplateEdit`/`deleteTemplateEdit`.
- **Daily working availability is already fully modeled and editable, via a different mechanism than the weekly obligated-minutes figure.** `PlanAheadDayCapacityService` manages one `BillExpected` row per `(webUserId, billDate)`: `billMins` (available minutes) and `workStatus` (`W`/`N`/`V`/`H`/`T`/`S` — Working/Not Working/Vacation/Holiday/Traveling/Sick), defaulting to 8h weekdays / 0 weekends when unset. Already writable today via `PlanAheadServlet`'s `saveDayCapacity` action, which also re-runs `TemplateGenerationService.generateForwardWindow()` since `TemplateWorkdayCalendar` reads these same rows to decide whether a billable recurring instance is eligible to generate for that date. **This is a distinct, independently-maintained figure from `WorkObligation`** (the weekly obligated-minutes total behind the report's percentages) — the two don't sync with each other today.
- **No public, filterable action-list view exists yet.** `PlanAheadBoardService.buildBoard()` always computes the full multi-day board with a hardcoded 5-/8-day window; its per-type row bucketing (`toRowKey`, the same WILL_MEET/COMMITTED/WILL/MIGHT split used throughout this design) is private. `DashboardTodayColumnService` proves the same single-day, per-type bucketing pattern already exists elsewhere, just not as a reusable public method. Nothing today can answer "just meetings this week" or "just Thursday" without pulling the whole multi-day board and filtering client-side.
- **`ProjectDefinitionImportService`** (the "project-language importer" the doc says must not be replaced) is a bulk, name-keyed, all-or-nothing patch mechanism over exactly the four language fields, with explicit **presence flags** per field (`descriptionPresent`, etc.) distinguishing "omitted" from "set to empty." Its caller in `ProjectHealthServlet` resolves every patch to a project first and only opens a transaction once the whole batch validates — this validate-everything-then-commit-once shape is the right template for the new batch-apply operation.
- **`ActionCompletionService`** is the canonical "complete an action" logic: validates no future-dated completion, requires a bill code when minutes > 0, checks for overlapping time entries, fans completion out across `SHARED` `ActionSet` siblings, creates the `ActionTaken` record, flips status, unblocks dependents, and creates a real `BillEntry`. Any MCP "complete action" capability must call this service, not hand-roll a status flip.
- **`WeeklyReportDataService.load(session, report, owner, weekStart)` is already a plain, session-independent method** (no HTTP coupling) that assembles everything the human-facing weekly report shows: `BillPlan`/`BillPlanTarget` give the active annual + "steering" target percentages per bill code (resolved automatically for a date via `BillPlanDao.findActiveApprovedPlan`); `Project.billCode` is a plain string match (not a strict FK — multiple projects can share a code), and `BillEntry` denormalizes project+billCode per logged hour, which is what per-project activity groups by; this week's completed items per project (with descriptions/notes), an 8-week worked-vs-obligated-vs-variance trend, and fiscal-year-to-date minutes per project are all already computed. This method is directly callable, unmodified, for an MCP read tool.
- DAO convention: plain classes over a Hibernate `Session` (via `HibernateRequestContext.getCurrentSession()` or an injected session), raw HQL with named parameters, in package `org.openimmunizationsoftware.pt.doa` (not `dao`).
- Migration convention: sequential `vN.M.sql` files in `src/db/`, applied manually (no Flyway/Liquibase), each paired by hand with a `Model.java` + `Model.hbm.xml` + a `<mapping resource=.../>` line in `hibernate.cfg.xml`.

---

## 2. Gaps against the requested capabilities

1. **No outlook storage at all.** Monthly/weekly outlooks need a new table; nothing in the schema represents this concept today. `WorkObligation`/`TrackerNarrative` are the two closest precedents but neither fits directly (see §6, Q2-Q4 for the resolved design).
2. **No dedicated "AI thoughts on a project" entity.** `ProjectNarrative` is daily-journaling-shaped (upsert by project+verb+day, required `contactId`); reusing it would fight its existing semantics rather than extend them. A new lightweight table is warranted.
3. **The batch-apply / write-back operation does not exist anywhere**, despite scaffolding that looks built for it (`ActionProposal`, `ActionChangeLog`). Every existing AI-adjacent write path applies one entity at a time in its own transaction. This is the single biggest piece of net-new application logic the project needs — decided to bypass `ActionProposal` and build it directly against `ActionChangeLog` (§6, Q6).
4. **No optimistic-concurrency/staleness protection exists anywhere in the codebase**, for AI-originated writes or otherwise, even though `ActionProposal.inputSnapshot` appears designed for exactly that. `Project` has no modification timestamp at all (only `lastModifiedByWebUserId`, an `Integer`, confirmed by grep — no `lastModifiedDate`/`updatedAt`); `ActionNext` does have `nextChangeDate`, which can serve as its staleness marker. Decided to add `Project.lastModifiedDate` (§6, Q7).
5. **No MCP transport.** `pom.xml` has no MCP SDK, and the app is on `javax.*`/Jersey — a good candidate to hand-roll the (intentionally small) MCP JSON-RPC surface directly, but this is new infrastructure.
6. **The cross-project planning view is UI-only, and not filterable.** `PlanAheadBoardService.buildBoard()` does the right computation but returns a UI-rendering model bound to the web session, with a hardcoded window and no way to ask for just one action type (e.g. meetings) or an arbitrary date range. MCP needs a parallel, data-only, filterable entry point into the same query/bucketing logic.
7. **No per-API-key scoping.** Fine for a single trusted local Codex client (matches the doc's stated intent), but worth flagging now since the doc anticipates a second ("meeting") client later.
8. **Time-allocation context (bill plan targets, project activity, obligated minutes, trend) is not exposed to MCP at all**, despite `WeeklyReportDataService.load()` already computing all of it — this is a read-tool gap, not a data-model gap.
9. **Daily working availability (`BillExpected`) is not exposed to MCP at all**, despite being fully modeled and already editable in the UI — also a read/write-tool gap, not a data-model gap.

Everything else the doc asks to read (project identity, focus/outcome/success-criteria, open actions, recent work, notes) is already modeled and mostly already assembled by `ProjectDashboardAiContextService`.

---

## 3. Proposed data model and migration approach

**Reuse as-is (no schema change):**
- `Project.description/currentFocusText/outcomeText/successCriteriaText` for project language.
- `ActionNext` and its fields for scheduling/estimate/status; `ActionNextNote` for notes; `ActionTaken` for completed-work history; `ActionSet`/`ActionSetType` to tag AI-created batches (e.g., a new `ActionSetType` value, or reuse `SHARED`).
- `ActionChangeLog` as the audit trail for AI-driven writes (`actorType` already distinguishes human vs. AI).
- `ActionProposal`, optionally — see design question 3 below on whether to route batch-apply through it or treat it as a parallel, still-unused mechanism.

**New table 1 — `planning_outlook`:**
Modeled directly on `work_obligation`'s precedent (`v5.9.sql`): owner-scoped, not workspace/project-scoped. **Decided: latest-wins per period, no revision history, no project links** — this is a narrative/explanatory record, not a structured or prescriptive one; project-specific commitments belong on the project or on actions, not here.
```
outlook_id       PK
owner_user_id    int, not null
period_type      varchar ('MONTH' | 'WEEK')
period_start     date, not null
outlook_text     text
created_at       timestamp
updated_at       timestamp
```
Unique key on `(owner_user_id, period_type, period_start)`, matching `work_obligation`'s `(owner_user_id, week_start)` unique key. `set_outlook` is a plain upsert on that key — overwrite `outlook_text`, bump `updated_at`. No status column, no audit/revision table, no `planning_outlook_project_link` table. Paired `Model.java` (plain POJO, matching `WorkObligation.java`), `.hbm.xml`, and a `PlanningOutlookDao` in `org.openimmunizationsoftware.pt.doa` mirroring `WorkObligationDao`.

**Frozen-past-periods rule:** once a period has fully elapsed, its outlook becomes read-only. Concretely: a `WEEK` outlook is editable through `period_start + 6 days`; a `MONTH` outlook is editable through the last day of that month; both are frozen starting the following day. Enforce this in `PlanningOutlookService`/the MCP write path (reject `set_outlook` on a past period with a clear error), not just in UI.

**New table 2 — `project_ai_note` ("AI thoughts on a project"):**
```
note_id       PK
project_id    int, not null (FK to Project — workspace scoping inherited from the project)
note_text     text, not null
source        varchar (e.g. model name / agent name, mirrors ActionProposal.modelName)
created_at    timestamp
updated_at    timestamp
```
Deliberately no `contactId`/`narrativeVerb`/review-status — this is meant to be simple CRUD (add/edit/delete), not a workflow, per the doc's ask to "see, edit, and delete these in Dandelion." Distinct from `ProjectNarrative` (established facts/decisions the user actually made) by table, not by a shared verb enum, so the two can't be confused in queries or UI.

**Migration mechanics:** next sequential file after the user's uncommitted `v5.9.sql` (i.e. `v5.10.sql`), containing both `CREATE TABLE` statements, InnoDB + `utf8mb4_0900_ai_ci` to match `v5.8`/`v5.9`'s convention. Add both entities' `<mapping resource=.../>` lines to `hibernate.cfg.xml` alongside the existing 69-line list.

---

## 4. Proposed MCP tool contract

Small, task-oriented surface per the doc's instruction, hosted under `/api/*` reusing `HibernateSessionFilter` + `ApiKeyAuthFilter` as-is (mint one `WebApiClient` row, e.g. `agentName = "codex-mcp"`, scoped to Nathan's workspace).

**Reads (never mutate):**
- `get_project_context(project_id)` — refactor `ProjectDashboardAiContextService` to return structured data (it already assembles everything needed: language fields, tags, recent `ActionTaken`, open/proposed `ActionNext`, open `ProjectIssue`, recent `ProjectNarrative`); add the new `project_ai_note` list. For any `ActionNext` that belongs to a `SHARED` `ActionSet`, also surface the name/handle of the other project(s) that action is shared with — the client stays bound to one workspace (see §6, Q1), but shared-project identity is context that travels with the action itself, not general access to that other workspace's data. Every action in the output carries `isTemplateRoot`/`generatedFromTemplateId` (§6, Q14) so the assistant knows what it can and can't touch; a project's active templates are listed separately (name + cadence only, not full recurrence config).
- `get_planning_context(start_date, end_date, action_types?, project_ids?)` — new public, filterable data-only method extracted from `PlanAheadBoardService.buildBoard()`'s query/bucketing logic (meetings/committed/will/might, day capacity, overdue actions, time spent today), taking an arbitrary date range and an optional action-type filter instead of the UI's hardcoded window — this is what makes "all meetings this week" or "everything scheduled Thursday" a plain call to this same tool rather than a separate one (§6, Q17). Same shared-project and template-flag surfacing as `get_project_context` for any relevant action in the window.
- `get_outlook(period_type, period_start)` / `list_outlooks(range)` — read from `planning_outlook`; include a `frozen: boolean` flag per the past-period rule in §3.
- `get_time_allocation_context(week_start?)` — calls `WeeklyReportDataService.load()` unmodified against the one `WeeklyReport` configured today (§6, Q15) and maps the resulting view model to JSON: allocation rows (bill code, label, funding source, actual minutes/percents at week/4-week/fiscal-year grain, annual/steering target percents), project activity (this week's completed items with descriptions/notes), fiscal-year-to-date minutes per project, obligated-minutes fields, and the 8-week worked-vs-obligated trend.
- `get_day_availability(start_date, end_date)` — reads `BillExpected` rows (`billMins`/`workStatus`) for the range via `PlanAheadDayCapacityService`, filling in the same weekday/weekend defaults the UI uses for dates with no row yet.

**Narrow writes (single record or small same-domain batch, no cross-entity coherence needed — commit immediately):**
- `set_outlook(period_type, period_start, text)` — upsert by the unique key; server rejects the call if the period is frozen (past).
- `add_project_ai_thought` / `update_project_ai_thought` / `delete_project_ai_thought` — plain CRUD on `project_ai_note`, independent records, no cross-entity coherence needed.
- `update_project_language(project_id, fields...)` — thin wrapper around `Project`'s native setters, reusing `ProjectDefinitionImportService`'s presence-flag convention (omitted vs. explicitly blank) for partial updates, gated the same way `handleLanguageReviewApply` already is (`WorkspaceRegistry.canAdministerWorkspace`), plus a staleness check against the new `Project.lastModifiedDate` column (§6, Q7). Kept as its own tool, separate from the batch operation below, since language review runs on its own cadence.
- `set_day_availability(days: [{date, work_status, bill_minutes}])` — accepts a small list so "just Tuesday" and "half day Wednesday" both fit one call; calls `PlanAheadDayCapacityService.saveDayCapacity` per day, then **one** `TemplateGenerationService.generateForwardWindow()` call at the end (matching the existing UI's side effect, batched rather than repeated per day) so recurring-template eligibility stays in sync with the stated availability (§6, Q16).

**The batch operation (the centerpiece):**
- `apply_changes(changes: [...])` — a list of typed items: `create_action`, `update_action`, `reschedule_action`, `split_action`, `remove_action`, `complete_action`. **Never creates new Projects** — every item must reference an existing `project_id`; project creation is out of scope for this version (§6, Q10). **Template-managed actions are restricted**: any item targeting an action where `templateType != null` (root) or `templateActionNextId != null` (instance) is rejected for `update_action`/`reschedule_action`/`split_action`/`remove_action` with a clear reason ("template-managed; edit via Dandelion UI"); `complete_action` is the one exception and is allowed on generated instances (not roots), since it's the same terminal, one-way `ActionCompletionService.closeAction()` path used for any other action and isn't affected by the hourly template-regeneration job (§6, Q14). Server-side:
  1. Pre-validate the entire batch against current DB state (same shape as `ProjectDefinitionImportService`'s "resolve everything, fail the whole batch on any mismatch" pattern) — for each item, compare the as-of marker (`ActionNext.nextChangeDate`) against the current row; reject the whole batch with a precise per-item diff if anything is stale.
  2. Apply everything in **one transaction** (unlike every existing single-entity AI write path). **No `ActionProposal` involvement** — the propose/accept lifecycle is bypassed entirely; nothing in the app reads `ActionProposal` today, so this operation writes directly to the target entities and to `ActionChangeLog` (§6, Q6).
  3. Delegate to existing services wherever one exists rather than re-implementing: `ActionCompletionService.closeAction()` for `complete_action` (preserves bill-code validation, `SHARED`-set fan-out, unblocking), similar ownership/mode-compatibility checks to `PlanAheadMutationService` for `reschedule_action`, native `ActionNext` field sets plus a new `ActionNextNote` insert for note additions (not a `setNextNotes` overwrite).
  4. `remove_action` is always a **soft cancel** (`ProjectNextActionStatus.CANCELLED`, matching `PlanAheadMutationService.deleteCardEdit`'s existing undo-able pattern) — never a hard delete (§6, Q8).
  5. `split_action` cancels the original action and creates the replacement actions fresh, all tagged into a shared `ActionSet` so the split is traceable as one unit, with the `ActionChangeLog` entry on the cancelled original referencing the new action IDs (§6, Q9).
  6. Write one `ActionChangeLog` row per mutated entity (`actorType = AI`), tagging newly created actions with a shared `ActionSet` so the whole batch is identifiable as a unit later.
  7. Return a `SyncBatchResponse`-shaped result: per-item status (`applied`/`rejected` + reason) and aggregate counts, mirroring `SyncResource`'s existing response contract.

**Response sizing/pagination:** cap project context lists at the same limits `ProjectDashboardAiContextService` already uses (15 `ActionTaken`, 20 `ActionNext`/`ProjectIssue`/`ProjectNarrative`); default the planning-context window to `PlanAheadBoardService`'s existing `WORK_WINDOW_DAYS = 5`, allow an explicit override up to some sane cap (e.g. 31 days) for the "next month" outlook conversation.

**Auth/permissions:** one `WebApiClient` row for the single trusted local Codex client, bound to one workspace, using the existing all-or-nothing model — no scopes for this phase (§6, Q6, Q11).

---

## 5. Phased implementation plan

**Phase 0 — end-to-end read + outlook slice (the doc's requested first slice). DONE, verified 2026-09-30:**
- Stand up the MCP transport: a new servlet/resource under `/api/*` implementing the minimal MCP JSON-RPC surface, reusing `HibernateSessionFilter` + `ApiKeyAuthFilter` by copying their `web.xml` mappings onto the new path. Mint one `WebApiClient` for Codex.
- Add the `planning_outlook` table + `set_outlook`/`get_outlook`/`list_outlooks`.
- Refactor `ProjectDashboardAiContextService` into a structured-data method backing `get_project_context` (keep the existing text-blob method for `ProjectReviewChatService`, or have it call the new structured method and format from that), including the template-flag and shared-project-context additions.
- Extract a data-only, filterable method from `PlanAheadBoardService` backing `get_planning_context` (arbitrary date range, optional action-type filter).
- Add `get_time_allocation_context`, calling `WeeklyReportDataService.load()` unmodified.
- Add `get_day_availability` / `set_day_availability`, calling `PlanAheadDayCapacityService` directly. This is the one write against an existing table in this phase, but it's justified as low-risk since it fully delegates to the already-shipped `saveDayCapacity` path (including the template-regeneration side effect) rather than inventing new logic.
- Otherwise **no writes to existing action/project data in this phase** — lowest risk, immediately useful for the "short conversation about the month, then the week" rhythm the doc describes.

**Phase 1 — AI project-thoughts. Implemented 2026-09-30, not yet live-tested:**
- Added `project_ai_note` table + CRUD tools + a minimal Dandelion UI list under the project page (view/edit/delete). Low risk: independent records, no batch/staleness complexity.

**Phase 2 — safe write-back (the hard part). DONE, verified 2026-09-30:**
- Add `Project.lastModifiedDate` (new column).
- Build `apply_changes` as designed above: pre-validation pass, single transaction, delegation to `ActionCompletionService`/mode-compatibility checks, `ActionChangeLog` writes — bypassing `ActionProposal` entirely.
- Add `update_project_language` as a thin, separately-gated tool.

**Phase 3 — make MCP-entered information count elsewhere in the app. Planned and implemented 2026-10-03 (version 5.10.8; status at the top of this document):**

Goal: information entered through the MCP should be used by the rest of Dandelion, not only by the MCP. When planned, outlooks had no UI and were read by nothing except the MCP tools, and AI thoughts were missing from the in-app AI chat context (`ProjectDashboardAiContextService`). No schema change is needed anywhere in Phase 3.

- **3.1 Fix outlook start dates.** `PlanningOutlookService` rejects a `WEEK` `periodStart` that isn't a Sunday and a `MONTH` `periodStart` that isn't the 1st, and the error gives the correct date. This applies to every caller (MCP and UI). MCP tool descriptions state the rule. Sunday matches the weekly report (`previousOrSame(SUNDAY)`) and `WorkObligation`, so outlook lookups by week can't silently miss.
- **3.2 Minimal outlook UI.** A page to view and edit the current month and the current and next week, with past (frozen) periods read-only. It uses the same `PlanningOutlookService` as the MCP, so the upsert and freeze behavior can't drift. Linked from the menu and from Plan Ahead.
- **3.3 In-app AI chat context.** `ProjectDashboardAiContextService.buildContextText()` adds the project's AI thoughts (labeled as assistant observations, not decisions or facts) and the current week and month outlooks, so the in-app GENERAL / NEXT_ACTIONS / LANGUAGE_REVIEW chats see what the MCP client sees.
- **3.4 Outlook feeds the weekly narrative.** `TrackerNarrativeGenerator` loads the outlook for the reported week, the outlook for the following week, and the month outlook into `GenerationContext`. The WEEKLY prompt in `OpenAiNarrativeGenerator` gets new instructions:
  - open with plan vs. actual: either "the week went as planned" or the specific ways it diverged, backed by time by project and completed work;
  - keep divergence neutral and factual (reactive work displacing planned work is normal, not a failure);
  - add a looking-ahead part based on the next week's outlook;
  - summarize the outlooks, never quote them;
  - if a week has no outlook, skip plan vs. actual rather than inventing a plan.

  Bump `WEEKLY_PROMPT_VERSION`. Daily narratives are unchanged.
- **3.5 Outlook on Plan Ahead.** A collapsible banner at the top of the Plan Ahead page with this week's outlook (the month's collapsed underneath) and an edit link to 3.2.
- **3.6 Docs, tests, version.** Verify MCP-Feedback I-12 (`linkUrl`) first. Add tests for date normalization, the chat context sections, and the outlook input to narrative generation. Version bump.

Order: 3.1 → 3.2 → 3.3 → 3.4 → 3.5 → 3.6.

Weekly routine this assumes: at the end of the week, have the planning conversation, set next week's outlook, then generate and approve the weekly narrative. The looking-ahead part only works if next week's outlook exists before the narrative is generated; if it's written later, regenerate.

Dropped from the original Phase 3:
- *Outlook section in the weekly report.* The report shows what the supervisor sees, and outlooks reach it only through the approved weekly narrative (decision 19). Supersedes decision 13.
- *A second `WebApiClient` for a meeting assistant.* Moved to the linked-meeting work (`docs/linked-meeting-domain-model.md`), where per-key scopes (decision 11) need to be designed too.

Out of scope: action attention modes (`docs/action-attention-modes.md`, still an undesigned idea), copying `linkUrl` to SHARED-set siblings, and the plaintext DB password in `hibernate.cfg.xml` (see the incidental finding below).

---

**Phase 4 — correct time entries through the MCP. Planned 2026-10-03; implemented 2026-10-03 in version 5.10.10; verified in production by Nathan on 2026-10-03:**

Status: `TimeRegularizationService.computeNormalizedTimes` extracted from `normalizeDayEntries` (pinned by `TimeRegularizationServiceTest`; the Review page still calls `normalizeDayEntries` and was checked on a real day after the change). `McpTimeEntriesService` with `get_time_entries` and `update_time_entries` (`GetTimeEntriesTool`, `UpdateTimeEntriesTool`); `McpTimeEntriesServiceTest` runs whole batches against a stand-in session (neighbor fix, overlap with an untouched entry, stale times, reassign, create, normalization report, bad input). Times are HH:mm (24-hour) in the user's time zone. 334/334 tests pass. Deployed to production and verified there by Nathan on 2026-10-03.


Problem: time tracking is right most of the time, but completing an action automatically starts the next one, so time often lands on the wrong action or project until Nathan notices. Fixing it means clipping that time out and giving it to the entry before or after it, or to another project. The Review & Report editor (`TimeReviewService.updateEntryTime`) changes one entry's times per save, can't change an entry's project, and re-normalizes the whole day after every save (`TimeRegularizationService.normalizeDayEntries`: truncate to the minute, round each unbroken run's start down and end up to 10 minutes, close gaps inside one 10-minute window, push overlaps apart). The same normalization also runs whenever a day is opened in Review & Report. So a fix that touches several entries fights the healing between saves. Separately, reclassifying a past week (times right, projects wrong) for the annual report has no tool at all.

Background facts the design relies on:
- Time lives in `bill_entry` (project, optional action, `web_user_id`, start, end, `bill_mins`, bill code, billable, budget). Reports, the weekly report, and the work day review sum `bill_entry` directly, so corrections show up immediately; narratives already generated keep their old numbers until regenerated.
- Zero-minute entries are deleted by `TimeRegularizationService.cleanupZeroMinuteEntries` whenever the timer is stopped (except the running entry). Zeroing an entry (start = end) is how the UI removes one, and the MCP does the same.
- The running timer is web-session state (`TimeTracker`): it caches the running entry's start and rewrites its end and minutes from that cache. The database doesn't record which entry is running, so the MCP can't tell.
- `bill_entry` has no last-modified column; an entry's current start and end serve as its version for staleness checks.

Tools:
- **`get_time_entries(date)`** (read). The day's entries in order: `billId`, start, end, minutes, project id and name, action id and description, bill code, billable, plus `editable` and a reason when not, and the day's total. Returns an object (MCP-Feedback P-1).
- **`update_time_entries(date, changes, reason)`** (write, only after Nathan approves the plan in conversation). One day per call; the whole batch is validated, then applied in one transaction (nothing partial), like `apply_changes`. Change types:
  - `adjust {billId, expectedStart, expectedEnd, start, end}`: new times. `start == end` zeroes the entry; the existing cleanup deletes it later.
  - `reassign {billId, expectedStart, expectedEnd, projectId, actionNextId?}`: move an entry to another project (and optionally an action on it). Bill code, billable, and budget are resolved the way the timer does it (`ClientServlet.resolveBillCode`, `project.getBillBudgetId()`).
  - `create {projectId, actionNextId?, start, end}`: new entry for time that belongs to a project with no neighboring entry to extend, built with `TimeTracker.createBillEntry`.

  Validation (any failure rejects the whole batch with a per-item reason): each entry is Nathan's and on that day; `expectedStart`/`expectedEnd` still match (minute precision); whole minutes, start <= end, inside the day, at most 12 hours, nothing in the future; after all changes are applied, no two entries with time on them overlap anywhere in the day, untouched entries included (zero-length entries never overlap); target projects are in the workspace and have a bill code; the protected entry (decision 24) isn't touched.

  After applying, the tool runs the same day normalization the Review page uses and returns the day as stored, listing anything normalization adjusted (decision 25). What the assistant reports is then what the app keeps; opening the day later doesn't move anything further.

Implementation notes:
- `normalizeDayEntries` and `cleanupZeroMinuteEntries` begin and commit their own transactions, which would break the all-or-nothing batch. Split normalization into a pure "compute the adjusted times" step and a "save" step (a behavior-preserving extract, like the Phase 2 `ActionCompletionService` refactor); the Review page keeps calling the combined method, and the MCP calls the pieces inside its own transaction. Cover it with tests that pin the current normalization behavior before refactoring.
- The overlap check and the change application are plain functions over the day's entry list, so they can be unit-tested without a database.
- Side effect to know about: after an MCP edit to today's entries, the dashboard's time totals for today can lag until the timer next starts or stops, because the web session keeps running totals. Past days aren't affected.
- No schema change.

Size: about one working session; the read tool is small, the write tool is medium (mostly validation and tests).

## 6. Decisions

Resolved in review with Nathan; recorded here so the design doesn't drift.

1. **API-key/workspace scope.** `WebApiClient` binds to exactly one workspace (Nathan's private one), as the existing model already does — no multi-workspace spanning. Refinement: when an action is part of a `SHARED` `ActionSet` that also touches a project in another workspace, that shared project's identity (name/handle) is still surfaced as context on the action — visibility travels with the action, not with general workspace access. See §4 read-tool notes.

2. **Outlook history.** Latest-wins only. One row per `(owner_user_id, period_type, period_start)`, overwritten on every `set_outlook` call. No revision log, no audit-of-edits-within-a-period — "I can't use old, corrected information." Looking back at *previous periods'* latest outlooks is useful and is exactly what querying by `period_start` already gives for free.

3. **Outlook ↔ project links.** None. The outlook is narrative/explanatory, not prescriptive, and project-specific intentions belong on the project or its actions, not referenced from here. No `planning_outlook_project_link` table.

4. **Frozen past periods.** Once a period has fully elapsed it becomes read-only: a `WEEK` outlook is editable through `period_start + 6 days`, a `MONTH` outlook through its last calendar day, both frozen the day after. Enforced server-side in the write path, not just the UI.

5. **AI thoughts on a project.** New `project_ai_note` table, not `ProjectNarrative` — confirmed.

6. **Batch-apply vs. `ActionProposal`.** Bypass `ActionProposal` entirely — no proposal is written for MCP-originated batches. `ActionChangeLog` is the audit trail directly.

7. **Staleness marker.** `ActionNext.nextChangeDate` for actions (exists today); add a new `Project.lastModifiedDate` column for project-language edits — approved.

8. **Removing an action.** Always a soft cancel (`ProjectNextActionStatus.CANCELLED`), matching the existing `deleteCardEdit`/undo pattern — never a hard delete.

9. **Splitting an action.** Cancel the original, create the replacement actions fresh, tagged into a shared `ActionSet` for traceability.

10. **Creating projects via MCP.** Not supported in this version. `apply_changes` only ever operates on existing `project_id`s.

11. **API-key permission scope.** One all-permission `WebApiClient` for Codex now; no scopes system. Revisit only if/when a second, less-trusted client is actually built.

12. **MCP transport.** Hand-roll the minimal JSON-RPC surface inside a new Jersey resource — no MCP SDK compatible with this app's `javax.*`/Jersey stack was found, and the tool surface is small enough that this isn't much code.

13. **Weekly report integration.** *Superseded by decision 19 (2026-10-03).* Deferred, as the original prompt allowed. `WeeklyReportDataService.load()`'s existing `LocalDate weekStart`-threaded design absorbs a future outlook lookup without restructuring — no design debt from deferring it.

14. **Template-managed actions via MCP.** Visible in all read output (`isTemplateRoot`/`generatedFromTemplateId` flags, plus a project's active-template list), but off-limits for `update_action`/`reschedule_action`/`split_action`/`remove_action` in `apply_changes` — those remain exclusively a Dandelion-UI operation, for now. Exception: `complete_action` is allowed on generated instances (not roots), since completion is a terminal, one-way state change that doesn't interact with the hourly template-regeneration job the way editing/rescheduling would. This distinction (and the fact that templates can't otherwise be changed via MCP) is documented in the MCP tool descriptions so the client doesn't need to discover it by trial and error.

15. **Which `WeeklyReport` backs `get_time_allocation_context`.** Exactly one exists today and none are anticipated soon — hardcode to that single report for now, no `report_id` parameter.

16. **Daily availability via MCP.** New `get_day_availability`/`set_day_availability` tools over the existing `BillExpected` model. `set_day_availability` must always end with one `TemplateGenerationService.generateForwardWindow()` call, matching the existing UI's side effect, so recurring-template eligibility never silently drifts out of sync with stated availability.

17. **"Just meetings" / "just one day" queries.** No separate tool — `get_planning_context` gets an optional action-type filter and an arbitrary date range (rather than the UI's hardcoded window), so a single-day or single-type query is just a narrower call to the same tool.

18. **`WorkObligation` vs. `BillExpected` — not unified.** The weekly obligated-minutes figure (`WorkObligation`, used in the report's percentages) and daily availability (`BillExpected`, used in Plan Ahead's capacity gauge and template eligibility) remain two separate, independently-maintained numbers with no automatic sync between them. Noted for the assistant's and Nathan's awareness; not something this design changes.

Phase 3 decisions (2026-10-03):

19. **Outlooks reach the weekly report only through the narrative.** Outlooks are source material for the weekly narrative (`TrackerNarrative`), which Nathan edits and approves before it appears in the report. The raw outlook text never renders in the weekly report, in either the public or the private view, so Nathan sees exactly what his supervisor sees. The approval step is what keeps candid outlook wording appropriate for the supervisor.

20. **Plan vs. actual is a standard part of the weekly narrative.** The weekly narrative compares the reported week's outlook with what actually happened ("did everything planned" or "diverged in these ways"), and uses the next week's outlook for a looking-ahead part. The outlook is meant to inform what the supervisor sees.

21. **Outlooks are written before their period and normally left alone.** Latest-wins with no revision history (decision 2) stays as is. Mid-week edits aren't prevented, but the design doesn't plan for them, and the freeze after the period ends keeps the plan of record from being rewritten later.

22. **Outlook start dates are fixed.** A `WEEK` outlook always starts on a Sunday and a `MONTH` outlook on the 1st. The server enforces this for every caller.

23. **AI thoughts go to the in-app chat, not to supervisor narratives.** AI thoughts are added to the in-app AI chat context, labeled as assistant observations. They are speculative by design, so they are left out of daily and weekly narrative generation.

Phase 4 decisions (2026-10-03):

24. **The current entry is off-limits.** Today's most recent time entry is never editable through the MCP, because the MCP can't tell whether it's the running timer entry. `get_time_entries` marks it not editable ("current entry; edit in Dandelion") and `update_time_entries` rejects any batch that touches it. Every earlier entry today, and every past day, is editable.

25. **Normalize after editing, never reject for it.** After applying a batch, the tool runs the same day normalization as the Review page and reports what it adjusted. A batch is never rejected because normalization would change it; if the adjustment isn't wanted, it can be corrected with a follow-up change, and the assistant learns how the rules treat that request.

26. **No date limit.** Any past day can be corrected, including early-year weeks that need reclassifying for the annual report. Reports or narratives built from the old numbers can go out of sync; keeping them current is Nathan's call.

27. **No audit trail.** Time corrections aren't logged beyond the tool result (before and after). No change-log table.

28. **Removal is zeroing.** The MCP never deletes a time entry; setting start equal to end zeroes it, and the existing zero-minute cleanup deletes it, exactly as when Nathan zeroes an entry in the UI.

---

## Incidental finding (unrelated to this assessment)

`src/main/resources/hibernate.cfg.xml` has a plaintext database password committed in a tracked file. Not part of the AI-integration work, but worth fixing independently (e.g. move to an environment variable or untracked properties file) since this file is already in your working set of modified files.
