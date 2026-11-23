-- 5.18.1: data fix only, no schema change.
-- Some action-creation paths (MCP apply_changes, Action Sessions, close-out successors, a new
-- project's "setup new project" action) marked an action billable whenever its project had any
-- bill code, including the nonbillable "Personal" code. The phone's personal day leaves out
-- billable actions, so those personal actions never appeared in Sundial.
-- The code now uses ProjectActionDao.isBillable (the bill code itself must be billable). This
-- clears the flag on actions that the bug stamped: billable = 'Y' on a project whose bill code
-- is marked nonbillable. Actions on billable codes, and the reverse case, are left alone.
UPDATE action_next an
  JOIN project p ON p.project_id = an.project_id
  JOIN bill_code bc ON bc.bill_code = p.bill_code AND bc.workspace_id = p.workspace_id
SET an.billable = 'N'
WHERE an.billable = 'Y'
  AND bc.billable = 'N';
