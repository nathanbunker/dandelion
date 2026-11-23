# Action Sessions — Release Record (2026-11-21)

Production: https://dandelion-daily.org, version 5.18.0, commit efc3e18 (master; migration v5.17 applied).
Acceptance criteria: `docs/plans/Action Sessions - Acceptance Contract.md`. Brief: `docs/plans/Action Sessions - Claude release brief.txt`.

## What is live

- **Work Hygiene (project 48627)**, folder `brain:projects/work-hygiene`.
  - Template **798209 "Prepare my workday"**: type D, AUTO_CANCEL, estimate 0, performed by claude-code.
  - Handoff ATTACH_TEMPLATE_INSTANCE onto template 778160 ("plan work for today") in 48627; workload `work-hygiene-prep`.
  - Template notes = `C:\brain\scripts\dandelion-sessions\workloads\work-hygiene-prep.md` (verbatim).
  - Generated preps: 11-23 (798213), 11-24 (798212), 11-25 (798211), 11-30 (798210), 12-01 (798217), 12-02 (798216), 12-03 (798215), 12-04 (798214), matching 778160 instances 797616, 797615, 797614, 797949, 797991, 798027, 798097, 798158.
- **Personal Hygene (project 49101)**, folder `brain:projects/personal-hygene`, no templates. Gmail triage is **disabled** in the PC config.
- **PC runner**: scheduled task "Dandelion Agent Runner", every 20 min, as NathanBunker, profile `prod`, identity check on.
  - Config: `%LOCALAPPDATA%\Dandelion\config\config.json` (backups `config.json.bak-*`).
  - Keys (DPAPI): `%LOCALAPPDATA%\Dandelion\keys\prod-*.dpapi`; server-side they're listed in Agent settings as "PC launcher 2026-11-21".
  - Scripts: `C:\brain\scripts\dandelion-sessions\`.
  - Logs: `%LOCALAPPDATA%\Dandelion\logs\` (runner.log, handler.log, hook.log).

## Evidence

- **Manual run** (14:42, `runner.py --action 798206`, disposable fixture project 49102):
  - 14 s; tools Edit/Glob/Grep/Read/Write; no MCP.
  - One review action, 798208; 0 time entries.
- **Resume** (14:45): handler.log "resume action 798208 launch 1 (prod)".
  - The same session recalled the marker "amber pelican 73", which appears only in the transcript.
  - Its Dandelion MCP reads went to production.
- **Scheduled run** (15:10): "pass start (scheduled) profile=prod", eligible [], result 0.
- **Fixture cleanup**: template 798204 cancelled; 798205 and 798207 deleted. Review 798208 left open.

## Next checks

- After 00:10 Mon 2026-11-23: prep 798213 completes with 0 minutes.
  - 797616 keeps its ID and notes and gains the summary and proposed Outlook batch.
  - Resume is the primary button; no new review action; no WILL_MEET changes.
- Morning journey: https://dandelion-daily.org/FocusedActionServlet?action=switchAction&completingActionNextId=797616 → Resume.

## Open items (Nathan)

- Codex: run `/hooks` once to trust the hooks.
- Rotate DANDELION_MCP_API_KEY (client 7).
- Optionally close review 798208 and project 49102.
- Gmail triage, before enabling:
  - Gmail OAuth credential (`gmail.modify` only).
  - G1: make gmail-triage.md and rules.md readable inputs.
  - G2: fix inconsistent task dates.
  - Then a production fixture, then a real run.
- Known quirk: MCP `activeTemplates` still lists cancelled templates.
- master is ahead of origin by 28 commits (not pushed).

## Disable / rollback

- Stop runs: `Disable-ScheduledTask "Dandelion Agent Runner"`
- Stop new preps: cancel template 798209 in Plan Ahead.
- Undo PC setup: `.\undo.ps1` in `C:\brain\scripts\dandelion-sessions`.
- Server: `ssh deploy@dandelion-daily.org "sudo cp /home/deploy/ROOT.war.prev /opt/tomcat/webapps/ROOT.war && sudo systemctl restart tomcat"` (sudo password).
- DB backup: `/var/backups/dandelion/pre-v5.17_2026-11-21_19-44.sql.gz` (restore only with Nathan's approval).
