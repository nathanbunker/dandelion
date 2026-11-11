## Plan: Plan Ahead Kanban Board

Build a new Plan Ahead page in the DandelionDaily area that reuses Dandelion Dashboard visual language and time gauge behavior, with a 5-day forward-only Kanban, inline drag/drop rescheduling, BillExpected day-capacity editing, and template-driven day generation. Reuse existing dashboard services and modal patterns where possible, and add narrowly scoped server actions for low-latency partial updates.

**Steps**
1. Phase 1 - Baseline page skeleton and routing
1. Create a new servlet in the DandelionDaily package for Plan Ahead with GET render + POST action dispatch, mirroring the action-routing style used in DandelionDashboardServlet. *blocks all later steps*
1. Add servlet registration and URL mapping in web.xml.
1. Add top menu entry in ClientServlet.makeMenu so logged-in users can reach Plan Ahead from the same nav as Dashboard/Review.
1. Build initial renderer class for page shell and styles by reusing DashboardPageRenderer structure/patterns (header row, sticky sections, modal scaffolding), but with Plan Ahead-specific grid.

1. Phase 2 - Domain models and data query layer for 5-day board
1. Add Plan Ahead view models (day header model, day capacity/work-status model, row/cell/card models, template row model) under the dandeliondaily dashboard model package or adjacent planahead model package. *depends on Phase 1*
1. Build a dedicated service to resolve the date window (default 5 days starting today, forward-only offset parameter for scroll +1 day).
1. Add/extend data loading queries for board actions constrained to provider + user contact, nextActionDate in visible window, excluding completed/deleted states as appropriate.
1. Partition actions into rows: Meetings=WILL_MEET, Committed=COMMITTED_TO, Will=WILL/WILL_CONTACT/WILL_DOCUMENT/WILL_FOLLOW_UP/WILL_REVIEW, Might=MIGHT.
1. Exclude all template-generated actions from movable board rows by filtering actions where templateActionNextId is not null.
1. Sort cards in each cell by estimate descending (largest first) to match planning priority intent.

1. Phase 3 - BillExpected capacity/status behavior
1. Add BillExpected day service methods that upsert records for visible days when missing (default weekend=Not Working, weekday=Working).
1. Store work status as short code in BillExpected.workStatus with label mapping in UI: Working, Not Working, Vacation, Holiday, Traveling, Sick.
1. Set/edit billMins and workStatus through a modal per day; persist with transactional update and return JSON payload for affected day cell + header gauge.
1. Keep “available minutes” for each day tied to billMins (not fixed 480), while preserving today’s special planned/actual treatment from existing TimeAdder + TimeTracker behavior.

1. Phase 4 - Kanban interactions (drag/drop + full edit modal)
1. Implement native HTML5 drag/drop for action cards with drop targets as cell-level row/date buckets (no within-cell prioritization changes). *depends on Phases 2-3*
1. Add Plan Ahead POST action to move card: update nextActionType + nextActionDate + nextChangeDate transactionally.
1. Reuse/port existing dashboard edit modal payload and server update logic to support full edit fields (type/date/estimate/description/contact/target/deadline/link/notes).
1. After move/edit save, return targeted partial HTML/JSON for only affected source+destination cells and affected day headers.

1. Phase 5 - Template scheduling row redesign
1. Create non-draggable template card list in first column of final row using template actions (templateType set) grouped consistently.
1. Render checkbox matrix for each template card x visible day.
1. Default checkbox behavior: Daily templates pre-check on working days; Weekly/other template types default unchecked.
1. On check: create (or refresh) generated ProjectActionNext for that date with templateActionNextId linkage and NextActionType=Will unless overridden by template metadata.
1. On uncheck: for future dates hard delete generated action; for today mark as Cancelled (per decision).
1. Ensure generated/template-linked actions never appear in movable Kanban rows.

1. Phase 6 - Real-time gauge and summary refresh
1. Add Plan Ahead server action to recalculate per-day planned totals and gauge state after any mutation (move/edit/template toggle/day-status edit).
1. Reuse TimeAdder categorization and existing today handling rules: today includes TimeTracker billable actuals; future days use estimate summations.
1. Return minimal payload containing updated day header fragments and any impacted row cell fragments for efficient repaint.
1. Keep refresh strategy mutation-driven (no full-board rerender unless action explicitly requests it).

1. Phase 7 - Validation and guardrails
1. Enforce “no past scheduling” for board moves/edits (forward-only planning rule).
1. Validate ownership/provider scope for all update actions.
1. Add defensive handling for invalid type/date/status codes with user-visible page messages.
1. Add tests around template toggle semantics (today cancel vs future delete), row filtering of template-generated actions, and gauge recomputation deltas.

**Relevant files**
- c:/dev/bunker/dandelion/src/main/java/org/dandeliondaily/servlet/DandelionDashboardServlet.java - reference for AJAX action routing, JSON response patterns, and gauge refresh endpoints to mirror in Plan Ahead.
- c:/dev/bunker/dandelion/src/main/java/org/dandeliondaily/dashboard/render/DashboardPageRenderer.java - source of shared look/feel, modal structure, and inline CSS conventions to reuse.
- c:/dev/bunker/dandelion/src/main/java/org/dandeliondaily/dashboard/render/TimeGaugeRenderer.java - reusable gauge rendering component for day headers and cards.
- c:/dev/bunker/dandelion/src/main/java/org/dandeliondaily/dashboard/service/DashboardTimeGaugeService.java - gauge model/state generation for warning/over thresholds.
- c:/dev/bunker/dandelion/src/main/java/org/openimmunizationsoftware/pt/manager/TimeAdder.java - committed/will/meet/might estimate math and special “today” handling.
- c:/dev/bunker/dandelion/src/main/java/org/openimmunizationsoftware/pt/manager/TimeTracker.java - today billable actuals included in allocation for current day.
- c:/dev/bunker/dandelion/src/main/java/org/openimmunizationsoftware/pt/servlet/TemplateScheduleServlet.java - create/delete/update semantics for template-generated actions.
- c:/dev/bunker/dandelion/src/main/java/org/openimmunizationsoftware/pt/model/BillExpected.java - day-level available minutes + work status persistence.
- c:/dev/bunker/dandelion/src/main/java/org/openimmunizationsoftware/pt/model/ProjectActionNext.java - core action fields: nextActionType, nextActionDate, templateActionNextId, templateType.
- c:/dev/bunker/dandelion/src/main/java/org/openimmunizationsoftware/pt/servlet/ClientServlet.java - menu integration for new Plan Ahead link.
- c:/dev/bunker/dandelion/src/main/webapp/WEB-INF/web.xml - servlet registration/mapping for new page.

**Verification**
1. Manual: Open Plan Ahead default view and verify 5 columns (today + 4 future), row headers, and first-column labels render with dashboard-like style.
1. Manual: Click “next day” scroll control repeatedly; confirm window only advances forward and never shows past days.
1. Manual: For a new visible day, verify BillExpected row auto-initializes defaults (weekday Working, weekend Not Working) and persists.
1. Manual: Edit day status/minutes modal, save, and confirm day cell + header gauge update immediately without full page reload.
1. Manual: Drag action from Will(today) to Committed(tomorrow); verify DB fields changed and card re-renders in destination with source removed.
1. Manual: Edit card modal changing type/date; verify card relocates and day headers recalc.
1. Manual: Template row check creates generated action immediately; uncheck future deletes; uncheck today marks Cancelled.
1. Manual: Confirm template-generated actions are absent from movable rows for all buckets.
1. Manual: Confirm per-cell card ordering is descending by estimate.
1. Automated: Run targeted tests for new service logic and servlet action handlers, then full Maven test suite.

**Decisions**
- Visible window: fixed 5 days.
- BillExpected work status: store short codes with UI label mapping.
- Today template uncheck: mark generated action as Cancelled.
- Row mapping confirmed: Meetings=WILL_MEET, Committed=COMMITTED_TO, Will=WILL-family, Might=MIGHT.
- Template-generated actions: hidden from all movable rows.
- Card modal scope: full edit fields.
- Refresh strategy: update only affected headers/cells, not full-board rerender.

**Implementation Contracts (Next Step Detail)**

1. Servlet and route contract
1. New servlet name and mapping: PlanAheadServlet at /PlanAheadServlet, implemented as standalone servlet in org.dandeliondaily.servlet.
1. Action dispatch mirrors DandelionDashboardServlet pattern: read action parameter first, return JSON for mutation calls, otherwise render full page.
1. Shared JSON helper strategy: reuse the same manual JSON writer approach used by DandelionDashboardServlet.sendJsonResponse for consistency and low dependency impact.

1. Request parameters and session state
1. Forward window control: windowStart parameter in yyyy-MM-dd; default resolves to user today; if earlier than today clamp to today.
1. Optional quick advance control: action=shiftWindowForward with days=1 that returns redirect target or updated header payload.
1. Session key for last viewed start day: PLAN_AHEAD_WINDOW_START.
1. Session key for open modal context (optional): PLAN_AHEAD_EDIT_ACTION_ID for recoverable modal reopen on postback errors.

1. Work status code contract for BillExpected.workStatus
1. W = Working
1. N = Not Working
1. V = Vacation
1. H = Holiday
1. T = Traveling
1. S = Sick
1. Validation: reject unknown code with success=false and message value listing accepted codes.

1. Endpoint actions and payloads
1. action=loadBoard
1. Purpose: return partial HTML for board grid + header row for current window.
1. Request: windowStart
1. Response data: boardHtml, headerHtml, windowStart, windowEnd

1. action=saveDayCapacity
1. Purpose: upsert one BillExpected day and return just the changed day capacity cell + day header gauge.
1. Request: billDate, billMins, workStatus
1. Response data: dayKey, dayStatusHtml, dayHeaderGaugeHtml, dayAvailableMinutes, dayPlannedMinutes
1. Transaction boundary: single transaction for BillExpected read-or-create and update.

1. action=moveCard
1. Purpose: drag/drop move to target row and day.
1. Request: actionNextId, targetActionType, targetDate
1. Server updates: ProjectActionNext.nextActionType, nextActionDate, nextChangeDate.
1. Response data: sourceCellHtml, targetCellHtml, updatedHeaderGauges array for affected day keys.
1. Transaction boundary: single transaction on one ProjectActionNext row with ownership/provider validation.

1. action=loadCardEdit
1. Purpose: fetch full card data for modal.
1. Request: actionNextId
1. Response data: same fields currently used in dashboard edit flow (date, type, description, estimate, contact, target date, deadline date, link, note).

1. action=saveCardEdit
1. Purpose: persist modal edits including optional type/date move.
1. Request: actionNextId plus full edit fields.
1. Response data: affectedCellsHtml map keyed by row+day, updatedHeaderGauges array.
1. Transaction boundary: single action update transaction; no separate gauge transaction.

1. action=toggleTemplateDay
1. Purpose: immediate create/remove/cancel behavior for template-generated action per template/day checkbox.
1. Request: templateActionNextId, billDate, checked
1. Behavior: checked=true creates or refreshes generated action; checked=false deletes for future dates, sets status Cancelled for today.
1. Response data: templateCellHtml, affectedWillCellHtml, updatedHeaderGaugeHtml for day.
1. Transaction boundary: single transaction covering lookup and mutation of generated action.

1. action=refreshDayHeaders
1. Purpose: recalculate and return only header fragments after multi-change batches.
1. Request: dayKeys CSV
1. Response data: dayHeaders map dayKey -> headerHtml + numeric totals.

1. Query and filtering contract
1. Movable board rows include only actions where templateActionNextId is null and templateType is null.
1. Template row source includes only template actions where templateType is not null and action status is active.
1. Generated template instances are identified by templateActionNextId not null and are excluded from movable board rows.
1. Status filter for movable rows excludes completed and cancelled actions.

1. Sorting and grouping contract
1. Group key for card cells is date + action type bucket.
1. Within each cell sort by nextTimeEstimate descending, then priorityLevel descending, then nextChangeDate ascending for deterministic order.
1. Drag/drop does not persist manual order index for this page; target cell order is always recomputed by the sort above.

1. Day totals and gauge contract
1. Planned total per day = committed + will + meetings; might excluded from planned total.
1. Today planned calculation includes TimeTracker billable actuals through TimeAdder today mode.
1. Future planned calculation uses TimeAdder with evaluationDate per day.
1. Available minutes = BillExpected.billMins - plannedMinutes.
1. Gauge thresholds can reuse existing DashboardTimeGaugeService state logic; denominator is BillExpected.billMins for Plan Ahead.

1. Error handling contract
1. All mutation actions return success flag and message; UI keeps previous state on failure and shows inline page message.
1. No past-date moves or edits allowed; return validation message if target date is before user today.
1. Ownership validation: action contact must match user contact role expectations and provider must match current provider.

1. Test contract additions
1. Service tests for day defaulting of BillExpected with weekend and weekday behavior.
1. Service tests for movable-row filtering excluding template-generated actions.
1. Servlet tests for toggleTemplateDay behavior differences between today and future.
1. Service tests for per-cell sort order largest estimate first.
1. Servlet tests for moveCard returning only affected cells and headers.

**Further Considerations**

**Batch 1 Breakdown (File Order for Initial Coding Pass)**
1. [src/main/webapp/WEB-INF/web.xml](src/main/webapp/WEB-INF/web.xml)
1. Add servlet declaration + mapping for PlanAheadServlet first so routing is enabled early and manually testable.

1. [src/main/java/org/openimmunizationsoftware/pt/servlet/ClientServlet.java](src/main/java/org/openimmunizationsoftware/pt/servlet/ClientServlet.java)
1. Add menu link entry for Plan Ahead in makeMenu() immediately after Dashboard/Project Health links so navigation exists from day one.

1. [src/main/java/org/dandeliondaily/servlet/DandelionDashboardServlet.java](src/main/java/org/dandeliondaily/servlet/DandelionDashboardServlet.java)
1. Reference-only extraction step during coding: reuse JSON helper/action dispatch patterns (do not modify behavior unless needed for shared helper factoring).

1. New file in org/dandeliondaily/servlet: PlanAheadServlet.java
1. Implement minimal skeleton first: login check, action dispatch switch, page title, render call, and JSON helper methods copied/adapted from dashboard patterns.
1. Include no-op handlers returning success=false for unimplemented actions so frontend wiring can proceed incrementally.

1. New file in org/dandeliondaily/planahead/model: PlanAheadBoardModel.java
1. Add top-level board model and nested models for day header, row, cell, card, and template row.
1. Keep model read-only style with simple setters/getters matching existing dashboard model conventions.

1. New file in org/dandeliondaily/planahead/service: PlanAheadBoardService.java
1. Implement window resolution (today-clamped + 5-day span) and initial board data assembly.
1. Add query methods for movable actions and template actions with filtering contracts (exclude template-generated from movable rows).

1. [src/main/java/org/openimmunizationsoftware/pt/model/BillExpected.java](src/main/java/org/openimmunizationsoftware/pt/model/BillExpected.java)
1. No structural field change expected; only confirm current fields support service behavior.
1. Add companion constants in service layer for work-status codes instead of modifying this legacy model.

1. New file in org/dandeliondaily/planahead/service: PlanAheadDayCapacityService.java
1. Implement BillExpected upsert for visible days with weekday/weekend defaults and code mapping (W/N/V/H/T/S).
1. Return day capacity DTO needed by renderer + mutation responses.

1. [src/main/java/org/dandeliondaily/dashboard/render/TimeGaugeRenderer.java](src/main/java/org/dandeliondaily/dashboard/render/TimeGaugeRenderer.java)
1. Reuse as-is in Plan Ahead renderer; only extend if strictly required for billMins-based denominator display.

1. New file in org/dandeliondaily/planahead/render: PlanAheadPageRenderer.java
1. Build page shell and board grid with dashboard-style visual language, including modal scaffolding and drag/drop target attributes.
1. Render from model only (no DB access in renderer).

1. New file in org/dandeliondaily/planahead/service: PlanAheadMutationService.java
1. Add moveCard/saveCardEdit/toggleTemplateDay mutation methods with transaction-scoped updates and affected-day recalculation hooks.
1. Keep each mutation method returning an explicit result object for partial refresh payloads.

1. New file in org/dandeliondaily/planahead/service: PlanAheadGaugeService.java
1. Implement day planned/available calculations using TimeAdder and TimeTracker rules (today vs future), then map to TimeGaugeModel.

1. New file in org/dandeliondaily/planahead/model: PlanAheadMutationResult.java
1. Define response payload container for affected cells/day headers and validation messages.

1. Batch 1 stop point criteria
1. Page reachable from menu and route.
1. 5-day board renders with static data from real queries and sorting/filtering rules.
1. saveDayCapacity endpoint works end-to-end and updates one day header/cell.
1. Drag/drop/edit/template toggles may still be stubbed but endpoint contracts exist.

1. Create a dedicated PlanAheadWorkStatus enum (recommended) versus hardcoded constants in service. Option A: enum in model package. Option B: servlet-local constants. Option C: DB lookup table.
2. Keep Plan Ahead as a standalone servlet (recommended) versus adding actions into DandelionDashboardServlet. Option A: standalone servlet + renderer/service set. Option B: single servlet with mode switch.
3. Add optional optimistic UI move before server round-trip. Option A: server-confirmed only (safer, recommended). Option B: optimistic move with rollback on error.