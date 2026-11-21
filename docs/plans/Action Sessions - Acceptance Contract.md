# Action Sessions — Acceptance Contract (v1)

Written 2026-11-21 by the product reviewer, before any code. Ratified by the release lead.
Sources (absolute, in the original checkout; untracked there):
- `C:\dev\bunker\dandelion\docs\plans\Action Sessions - Claude release brief.txt` (Nathan's decisions; overrides the plan)
- `C:\dev\bunker\dandelion\docs\plans\Action Sessions and Agent Actions.md` (feature specification)

Baseline: master a74d132, version 5.17.0 local and production, last production migration v5.16.
No kickoff selections from Nathan, so the brief's proposed defaults apply: Codex Resume only if exact
binding is proven; failure notices in the same project; remote control off; Work Hygiene 48627 with
human template 778160; Personal Hygene (new) for Gmail prep that follows Finances 48709 template 787273.

Code location note: Focused Action, MCP services, ActionThreadService, ActionCompletionService,
TemplateGenerationService live under `org.dandeliondaily.*`; the model, hbm and `McpResource` live
under `org.openimmunizationsoftware.pt`.

## Release-lead rulings on design conflicts

1. Configured preps **attach** to the existing human instance; they create no review. Unconfigured agent actions create one same-project review.
2. Gmail preps: the server creates one prep in Personal Hygene per eligible "clear personal email" instance, idempotently (unique key on target action ID), at eligibility read or claim — never on the PC. "Prepare my workday" is a normal template.
3. Outward-action exception applies only to the Gmail workload's confirmed rules and is written explicitly into its installed instructions. Work Hygiene is proposal-only for calendar changes.
4. Bail = scratch notes + session only. Park = notes + exactly one ActionTaken (History), no BillEntry, moved to tomorrow/chosen day.
5. History must include the open action's own ActionTaken entries (all layouts), so Park is visible. Each Park becomes a report line (accepted; tell Nathan).
6. Codex Resume only if a main-session SessionStart hook proves exact binding (two same-folder launches + one unrelated CLI launch). Never "newest session in folder", never "globally current action".
7. Local and production share IDs. Launcher URIs carry a non-secret environment key; handler checks it against trusted config and a server identity read, refusing on mismatch. Session commands (/bail, /park, /close-out) use the invocation binding (API target, action, launch ID), never the global Dandelion MCP.
8. Launch URIs carry a single-use, short-lived launch nonce issued by the authenticated page, redeemed by the handler through the API.
9. Confirm workspace IDs on install; Personal Hygene goes in Finances' workspace; runner verifies access to each project independently.
10. Missed-action configs of 778160/787273 are not changed. Unresolved Gmail items stay in the inbox and resurface in the next review.
11. A target that already carries this prep's successful handoff is skipped quietly; only a foreign session link is a conflict failure.
12. "Prepare my workday" copies 778160's workday schedule; estimate 0; AUTO_CANCEL; no time.
13. Close-out excludes template roots; generated instances shown grouped by series, read-only, "edit via template".
14. Handoff writes only notes + session link on the target; never description, linkUrl, schedule, assignee, estimate, time, predecessor.
15. Reject at claim any agent action with SHARED siblings or that blocks other actions. Human close-out previews twins and unblocks.
16. Merge records a survivor reference; survivor's History reaches the absorbed chain.
17. Human close-out bills exactly as Done does today; MCP/agent close-out omits duration.
18. Close-out is one shared service, one transaction, used by web and MCP.
19. Default successor in web close-out: same project, tomorrow, editable description, notes prefilled from outcome + remaining scratch. "No next action" requires an explicit choice.
20. Launcher buttons render only on a non-mobile Windows browser that has opted in; never via the Sundial API; placed in each layout's title-card row.
21. Gmail partial outcomes are written (redacted) to the Finances instance as well as the Personal Hygene failure action.
22. Headless runs load no claude.ai connectors (strict MCP config). The Gmail workload requires a narrow validating Gmail tool with its own credentials; if that cannot be provided (OAuth consent needs Nathan), the Gmail workload stays DISABLED. J4 ships independently.
23. Gmail: per-rule support matrix; unsupported rules leave mail in place and are listed. The runner enforces the no-trash protections. Write destinations beyond those named in confirmed rules need Nathan's confirmation.
24. Agent completions recorded under Nathan's contact with 0 minutes; reports unchanged.
25. Prove early that `claude --resume` of a headless session starts with interactive approvals and the review-bound target. Blocks Resume.
26. Project name "Personal Hygene" as written. Install creates both project folders. `linkUrl` stays http(s); launcher links are never stored in it.

## Acceptance checks

Markers: [LOCAL], [PROD], [BOTH]; **B** = release-blocking.

### J1 Launch from Focused Action
- AC-1 [BOTH] **B** Windows desktop, project with primary folder: title-card buttons in Classic, Timeline, Wrap-up: Folder, Terminal, Start (default agent, Claude Code by default), chooser (other-agent Start, New Claude, New Codex). Reschedule / Edit Action / This project unchanged.
- AC-2 [LOCAL] No project folder: Folder/Terminal/Start/New hidden or disabled with reason; nothing else changes.
- AC-3 [BOTH] **B** Folder opens Explorer at resolved primary folder; menu lists related folders in stored order with labels; each opens.
- AC-4 [LOCAL] Terminal opens Windows Terminal in primary folder, no agent.
- AC-5 [BOTH] **B** Start opens Claude Code in folder; first turn reads action ID, scratch notes, accessible predecessor chain (inaccessible private predecessor omitted). Page reload shows "Resume Claude · 0d" while window still open.
- AC-6 [LOCAL] **B** Start Codex launches Codex in folder with same instruction.
- AC-7 [LOCAL] Blank New: empty prompt, in folder, session bound to this action.
- AC-8 [BOTH] **B** Launcher URIs contain only verb, numeric IDs and env key / nonce. Never path, command, prompt, credential.
- AC-9 [LOCAL] **B** Malformed URIs (unknown verb, non-numeric ID, extra params, encoded traversal) show a message, launch nothing.
- AC-10 [LOCAL] **B** Inaccessible IDs refused.
- AC-11 [LOCAL] **B** Folders escaping roots via `..` or foreign junction refused; real C:\brain and C:\dropbox junctions resolve.
- AC-12 [PROD] First click's native "Open dandelion?" prompt documented as Nathan's step.
- AC-13 [LOCAL] Default agent = Codex flips Start and chooser. Model unset → no model flag on command line.
- AC-14 [LOCAL] No launched process has `--remote-control`.
- AC-15 [LOCAL] **B** Two Starts on two actions in one folder bind their own session IDs. Hand-launched `claude` binds nothing.
- AC-16 [BOTH] Project editor + get_project_context: primary folder (brain/dropbox/dev + relative) and ordered labeled related folders set/read. Unknown roots and absolute paths rejected.
- AC-17 [LOCAL] **B** Action + template editors set "Performed by" (none/claude-code/codex) separately from assignee; template edits reach future instances per existing propagation policy; individually edited instances per current policy.
- AC-18 [LOCAL] Preferences store default agent and optional per-agent model.

### J2 Work, stop, resume
- AC-19 [BOTH] **B** /bail appends 2–3 lines after existing scratch text (intact). No History, completion, date change or time. Session saved.
- AC-20 [BOTH] **B** /park appends handoff to notes; exactly one History entry on the still-open action in all layouts; moved to tomorrow (local) or chosen day; zero BillEntry.
- AC-21 [LOCAL] Retried Park after lost response creates no second History entry.
- AC-22 [LOCAL] Bail/Park work from Codex via its supported command/skill mechanism.
- AC-23 [BOTH] **B** Abrupt window close leaves Resume available; notes and History unchanged.
- AC-24 [BOTH] **B** Resume shows agent + age; opens in saved folder even after project folder edit; marker phrase visible.
- AC-25 [LOCAL] Resuming a bailed/parked action first offers finish or close out.
- AC-26 [LOCAL] **B** Resume shown at 29 days, hidden at >=30, age by user local date.
- AC-27 [LOCAL] Missing transcript before 30 days: readable "pick up from notes" message, no hang, no writes.
- AC-28 [LOCAL] **B** Start A, Start B on same action, close A: B's session link survives.
- AC-29 [LOCAL] **B** Codex Resume appears only after binding proof passes; otherwise hidden and release notes say so.
- AC-30 [LOCAL] Server down: callback queued, delivered on return; queue file holds no key.

### J3 Close out
- AC-31 [BOTH] **B** Close out reachable from title card in all layouts and via /close-out.
- AC-32 [LOCAL] **B** Completion description + outcome fields; every other accessible open project action incl. undated/future; template rows excluded; generated series grouped and marked.
- AC-33 [BOTH] **B** One successor proposed by default with notes brief; accepting creates it with followsActionNextId = closed action.
- AC-34 [LOCAL] **B** "No next action" requires explicit choice, then completes without successor.
- AC-35 [LOCAL] Multiple successors, each linked.
- AC-36 [LOCAL] **B** Rewrite/reschedule/cancel show before/after per affected row; nothing written before Confirm.
- AC-37 [LOCAL] Merge: pick survivor + combined text; absorbed cancelled with History naming survivor; survivor History reaches absorbed earlier steps.
- AC-38 [LOCAL] **B** Injected failure of 3rd of 5 changes: nothing applied.
- AC-39 [LOCAL] **B** Edit of affected row after preview → submit fails naming row; nothing applied.
- AC-40 [LOCAL] **B** Linked (shared) action: preview lists twins; DB diff shows only previewed rows changed.
- AC-41 [LOCAL] **B** Human close-out bills exactly as Done; MCP close-out without durationMinutes creates no BillEntry.
- AC-42 [LOCAL] Daily report shows completion description and outcome; never scratch notes.

### J4 Work Hygiene "Prepare my workday"
- AC-43 [BOTH] **B** Install log records 48627, 778160, its missed-action and schedule config, generatedFromTemplateId on instances, primary folder brain:projects/work-hygiene.
- AC-44 [BOTH] **B** Prep template (performed by claude-code) generates on exactly 778160's days; "plan for next work day" untouched.
- AC-45 [PROD] **B** Task Scheduler job under Nathan's profile, installed disabled, shortly after midnight, every 20 min, catch-up; an observed scheduled execution recorded (not manual).
- AC-46 [BOTH] **B** After run: prep completed with outcome, 0 minutes; existing 778160 instance keeps ID, prior notes, date, assignee, estimate; gains summary, proposed Outlook batch, unresolved questions; Resume primary. No new review action.
- AC-47 [LOCAL] **B** Prep twice for same date: one handoff, same ID, no duplicated notes.
- AC-48 [BOTH] **B** Overnight DB diff: zero WILL_MEET / linked-meeting changes.
- AC-49 [LOCAL] **B** Unavailable feed → "reconciliation incomplete", never "no meetings".
- AC-50 [LOCAL] Fixture report shows moved recurring meeting, overlap with minutes, short attendance flagged, missing link, cancellation; OOO excluded.
- AC-51 [BOTH] **B** Between prep and Resume add an action and change a meeting; Resume on 778160 instance shows same conversation (marker), refreshes both, names changes, asks approval for current batch.
- AC-52 [LOCAL] **B** Only selected items applied in one batch, local start order, links upserted, verified by re-read; second reconciliation proposes no duplicates; declined/empty batch goes to planning.
- AC-53 [BOTH] **B** Capacity: available minutes from day capacity data, meeting minutes with overlaps once, remaining estimated work incl. new action, unknown-estimate actions, unresolved attendance, overcommitment size; deferral suggestions with reasons/dates; nothing changes without approval.
- AC-54 [LOCAL] Tomorrow prep per obligation: evidence, one step today, open question; Friday covers Monday.
- AC-55 [BOTH] **B** Bail/Park in resumed session update the 778160 instance, never the completed prep.
- AC-56 [LOCAL] **B** Missing/closed target → failure; Nathan's own session on instance → prep fails, his link untouched.
- AC-57 [LOCAL] **B** Yesterday's prep run today binds only yesterday's target or fails; never replaces today's handoff.

### J5 Personal Gmail
- AC-58 [BOTH] **B** Personal Hygene exists once, reused on rerun, in Finances' workspace, folder brain:projects/personal-hygene; 787273 and its schedule/assignee unchanged.
- AC-59 [LOCAL] **B** Exactly one prep per open scheduled occurrence; none on unscheduled days; adding weekends to fixture template yields exactly one prep + handoff on the added day with no runner edit.
- AC-60 [LOCAL] **B** Rescheduled occurrence moves its prep; cancelled has none; cancel between claim and first mail mutation leaves mail untouched.
- AC-61 [LOCAL] **B** Fixtures: routine archived; label/extract gets note entry then archived; dated task in correct project with Gmail link and correct date; protected/ambiguous left and listed.
- AC-62 [LOCAL] **B** Failed extract prevents archive.
- AC-63 [LOCAL] **B** Interrupt after task, before archive, retry → one task, one note entry, one archive, one open review.
- AC-64 [LOCAL] Second message on processed thread handled as new; thread labels change only after checking unprocessed messages.
- AC-65 [BOTH] **B** Empty inbox still produces a review with zero counts.
- AC-66 [BOTH] **B** Review notes stand alone: counts, created-action links with dates, note destinations, unresolved items grouped by decision with Gmail link, reason, suggestion. No raw bodies or account numbers.
- AC-67 [BOTH] Review stays open until Nathan completes it; if AUTO_CANCEL removes it, unresolved items appear in next review.
- AC-68 [BOTH] Resume opens same conversation; rules change only when Nathan says so.

### J6 Failure
- AC-69 [BOTH] **B** Forced failure: source open with redacted error appended, claim released, exactly one same-project human failure action dated today (local).
- AC-70 [LOCAL] **B** No reclaim on later polls; Retry visible on failure action and source; after Retry next poll runs it.
- AC-71 [LOCAL] **B** Crash after completion commit → no failure action; retry returns committed result.
- AC-72 [LOCAL] **B** Killed runner → attempt shown abandoned/expired after lease; late write from old attempt rejected.
- AC-73 [LOCAL] **B** 5 eligible, overlapping polls → never >3 active claims; two runners on one action → exactly one wins.
- AC-74 [LOCAL] Missed trigger runs on wake; undelivered report delivered after server returns; missing folder → visible failure.
- AC-75 [LOCAL] Partway Gmail failure shows redacted partial outcomes on Finances instance.
- AC-76 [LOCAL] **B** At 23:30 local (after UTC midnight) tomorrow's prep not eligible; at 00:05 local it is.

### J7 Existing flows unchanged
- AC-77 [BOTH] **B** Switch bar + Quick Capture identical in all layouts.
- AC-78 [BOTH] **B** Save progress, Done, Blocked, Delete keep notes/History/time behavior; automatic next-action selection unchanged.
- AC-79 [BOTH] **B** Sundial flows unchanged, no launcher UI.
- AC-80 [LOCAL] **B** Existing apply_changes callers and reads unchanged; new fields additive; linkUrl rejects non-http(s).
- AC-81 [LOCAL] Reports for a day with no agent activity identical before/after.
- AC-82 [LOCAL] **B** Migration applies to a copy of the existing DB with nullable defaults; previous WAR runs on migrated schema.
- AC-83 [LOCAL] **B** mvn clean package + tests pass; changed classes in WAR; template generation and missed-action behavior unchanged for templates without agent fields.

### Safety and boundaries
- AC-84 [BOTH] **B** Effective headless tool list recorded: no broad Dandelion MCP, no claude.ai connectors, no unrestricted shell, no deploy credentials.
- AC-85 [LOCAL] **B** Work prep profile's Gmail write attempts fail.
- AC-86 [LOCAL] **B** Gmail profile cannot send/reply/draft/forward/permanently delete/unsubscribe/open links; Trash only under explicit confirmed Trash rule and never for a person, Primary, protected attachment.
- AC-87 [LOCAL] **B** Fixture email instructing other actions is treated as data; writes only to mapped projects/destinations.
- AC-88 [BOTH] **B** BillEntry counts and timer state unchanged by every runner/agent flow.
- AC-89 [LOCAL] **B** Unattended run DB diff touches only source, configured target and failure action.
- AC-90 [BOTH] **B** No API key, ICS URL, or OAuth token in URIs, command lines, logs, queue files, transcripts, notes.
- AC-91 [BOTH] **B** Same ID local/prod: local writes only local, prod profile only prod; mismatch refused; every test mutation preceded by a read confirming the server.
- AC-92 [LOCAL] **B** Headless worker cannot modify runner scripts, config, credentials.
- AC-93 [LOCAL] **B** Plain CLI sessions with no binding write nothing to Dandelion.
- AC-94 [LOCAL] **B** Resumed interactive session asks for normal approvals; no unattended allowlist inherited.
- AC-95 [LOCAL] Launcher link fired by another page cannot silently start a session.
- AC-96 [LOCAL] Runner uses its own API client; revoking it stops the runner without affecting Nathan's MCP.
- AC-97 [BOTH] **B** Workload with unverifiable boundaries stays disabled; disable + PC undo restores handler, hooks, tasks, settings.
- AC-98 [PROD] Release evidence has no raw mail, private extracts or personal links; gives Nathan review links.

## Out of scope for v1
Cowork/pull agents beyond the agent-key work-list read; headless Codex; remote control; launcher on
phone/Sundial/dashboard/Plan Ahead; overnight calendar writes; Gmail send/forward/draft/unsubscribe/
permanent delete; unattended rule changes; Gmail filters; text/weekly/captures/journal triage; weekly
report drafting; meeting research; report changes; Project Captures; multiple sessions per action;
non-http linkUrl; recurrence/missed-action rule changes; second PC or macOS.
