-- Action notes cleanup: the per-line action_next_note table was replaced by
-- action_next.next_notes in v5.13.sql. Safe to run before or after deploying 5.14.0;
-- neither 5.13.x nor 5.14.0 reads the table.
DROP TABLE IF EXISTS action_next_note;

-- Outcome notes: the detail behind an action taken (what happened, decisions,
-- where it was left). action_description stays a short "what was done"
-- fragment; notes written before or during the work stay on
-- action_next.next_notes. Must be applied before deploying 5.15.0, which maps
-- the column.
ALTER TABLE action_taken ADD COLUMN action_note TEXT NULL AFTER action_description;

-- Action threads: an action created as the next step of another points back to
-- it, so the work can be followed step by step. Each twin of a linked-project
-- action points to the step in its own project. Must be applied before
-- deploying 5.15.0, which maps the column.
ALTER TABLE action_next ADD COLUMN follows_action_next_id INT NULL;
CREATE INDEX idx_action_next_follows ON action_next (follows_action_next_id);
