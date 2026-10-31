-- Action notes become one plain-text value per action; apply manually after v5.12.sql.
-- action_next_note is kept for one prod release as a safety copy and dropped in a later version.
-- MEDIUMTEXT because the 20,000-character cap can exceed TEXT's 65,535 bytes in utf8mb4.
ALTER TABLE action_next ADD COLUMN next_notes MEDIUMTEXT NULL;

SET SESSION group_concat_max_len = 1000000;
UPDATE action_next a
JOIN (SELECT action_next_id,
             GROUP_CONCAT(note_line ORDER BY note_date, action_next_note_id SEPARATOR '\n') AS txt
      FROM action_next_note GROUP BY action_next_id) n
  ON n.action_next_id = a.action_next_id
SET a.next_notes = n.txt;
