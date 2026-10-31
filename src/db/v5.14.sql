-- Action notes cleanup: the per-line action_next_note table was replaced by
-- action_next.next_notes in v5.13.sql. Safe to run before or after deploying 5.14.0;
-- neither 5.13.x nor 5.14.0 reads the table.
DROP TABLE IF EXISTS action_next_note;
