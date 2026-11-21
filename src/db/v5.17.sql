-- Action Sessions and Agent Actions (5.18.0). See docs/plans/Action Sessions and Agent Actions.md
-- and "Action Sessions - Acceptance Contract.md".
-- Additive only: every new column is nullable or defaulted, so 5.17.x keeps running on the
-- migrated schema (it maps none of these). Must be applied before deploying 5.18.x.

-- Agents: the software that does the work (Claude Code, Codex). Dandelion stores only these
-- keys; the commands for each key live in the PC's trusted config, never here.
-- capabilities is a comma-separated list: new, start-with-prompt, session-id-at-launch,
-- resume-by-id, headless, pull.
CREATE TABLE agent (
  agent_key VARCHAR(30) NOT NULL,
  display_name VARCHAR(60) NOT NULL,
  integration_style VARCHAR(12) NOT NULL DEFAULT 'LAUNCHED',
  capabilities VARCHAR(200) NOT NULL DEFAULT '',
  active CHAR(1) NOT NULL DEFAULT 'Y',
  sort_order INT NOT NULL DEFAULT 0,
  PRIMARY KEY (agent_key)
);
INSERT INTO agent (agent_key, display_name, integration_style, capabilities, active, sort_order) VALUES
  ('claude-code', 'Claude Code', 'LAUNCHED', 'new,start-with-prompt,session-id-at-launch,resume-by-id,headless', 'Y', 1),
  ('codex', 'Codex', 'LAUNCHED', 'new,start-with-prompt,resume-by-id', 'Y', 2);

-- Project folders: one primary and any number of ordered, labeled related folders, each a known
-- root (brain, dropbox, dev) plus a relative path. primary_project_id is set (= project_id) only on
-- the PRIMARY row, so the unique key allows one primary per project.
CREATE TABLE project_folder (
  project_folder_id INT NOT NULL AUTO_INCREMENT,
  project_id INT NOT NULL,
  folder_role VARCHAR(8) NOT NULL,
  primary_project_id INT NULL,
  root_key VARCHAR(10) NOT NULL,
  relative_path VARCHAR(500) NOT NULL DEFAULT '',
  label VARCHAR(80) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  PRIMARY KEY (project_folder_id),
  UNIQUE KEY uk_project_folder_primary (primary_project_id),
  KEY idx_project_folder_project (project_id, sort_order),
  CONSTRAINT fk_project_folder_project FOREIGN KEY (project_id) REFERENCES project (project_id) ON DELETE CASCADE
);

-- "Performed by" agent, separate from the human assignee. Set on templates and copied to the
-- instances they generate (TemplateGenerationService). Null means a person does it.
ALTER TABLE action_next ADD COLUMN performed_by_agent VARCHAR(30) NULL;
CREATE INDEX idx_action_next_performed_by ON action_next (performed_by_agent);

-- How an agent template hands its work to a person. Keyed by the agent action's template root.
--   SAME_PROJECT_REVIEW (default): a review action is created in the same project.
--   ATTACH_TEMPLATE_INSTANCE: the summary and session attach to the open instance of
--     handoff_template_id (in handoff_project_id) on the prep's planning date.
--   PER_TARGET_INSTANCE: Dandelion creates one prep per open instance of handoff_template_id
--     (agent_prep_target) and attaches to that instance.
-- workload_key selects the PC's trusted permission profile (e.g. work-hygiene-prep).
CREATE TABLE action_agent_config (
  action_next_id INT NOT NULL,
  workload_key VARCHAR(40) NULL,
  handoff_mode VARCHAR(24) NOT NULL DEFAULT 'SAME_PROJECT_REVIEW',
  handoff_project_id INT NULL,
  handoff_template_id INT NULL,
  PRIMARY KEY (action_next_id),
  CONSTRAINT fk_action_agent_config_action FOREIGN KEY (action_next_id) REFERENCES action_next (action_next_id) ON DELETE CASCADE
);

-- One prep per human target instance (PER_TARGET_INSTANCE). The unique target key makes creation
-- idempotent across overlapping polls.
CREATE TABLE agent_prep_target (
  prep_action_next_id INT NOT NULL,
  target_action_next_id INT NOT NULL,
  prep_template_id INT NOT NULL,
  created_date DATETIME NOT NULL,
  PRIMARY KEY (prep_action_next_id),
  UNIQUE KEY uk_agent_prep_target_target (target_action_next_id)
);

-- One current working session per action: the agent, its session id and the folder it ran in
-- (needed to resume). launch_id is the launch that owns the link; a callback from an older launch
-- can't replace a newer one. Retention (Resume for 30 days) is computed from saved_date.
CREATE TABLE action_session (
  action_next_id INT NOT NULL,
  agent_key VARCHAR(30) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  root_key VARCHAR(10) NOT NULL,
  relative_path VARCHAR(500) NOT NULL DEFAULT '',
  saved_date DATETIME NOT NULL,
  launch_id INT NULL,
  run_id INT NULL,
  session_state VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  source VARCHAR(10) NOT NULL DEFAULT 'LAUNCH',
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY (action_next_id),
  CONSTRAINT fk_action_session_action FOREIGN KEY (action_next_id) REFERENCES action_next (action_next_id) ON DELETE CASCADE
);

-- A launch: minted by the signed-in page (single use, about 60 seconds), redeemed once by the PC
-- link handler with its own LAUNCHER key. Only the SHA-256 of the nonce is stored. The folder and
-- agent the handler was told to use are snapshotted at redeem.
CREATE TABLE action_launch (
  launch_id INT NOT NULL AUTO_INCREMENT,
  nonce_hash CHAR(64) NOT NULL,
  web_user_id INT NOT NULL,
  workspace_id INT NOT NULL,
  action_next_id INT NOT NULL,
  verb VARCHAR(10) NOT NULL,
  agent_key VARCHAR(30) NULL,
  agent_model VARCHAR(80) NULL,
  project_folder_id INT NULL,
  env_key VARCHAR(12) NOT NULL,
  issued_date DATETIME NOT NULL,
  expires_date DATETIME NOT NULL,
  redeemed_date DATETIME NULL,
  redeemed_client_id INT NULL,
  folder_root_key VARCHAR(10) NULL,
  folder_relative_path VARCHAR(500) NULL,
  pending_session_id VARCHAR(64) NULL,
  bound_session_id VARCHAR(64) NULL,
  bound_date DATETIME NULL,
  ended_date DATETIME NULL,
  PRIMARY KEY (launch_id),
  UNIQUE KEY uk_action_launch_nonce (nonce_hash),
  KEY idx_action_launch_action (action_next_id)
);

-- Unattended runs: one row per attempt. active_action_id is generated (only while CLAIMED or
-- RUNNING) and unique, so two runners can never hold the same action. Claim/attempt state is kept
-- apart from session retention (action_session).
CREATE TABLE agent_run (
  run_id INT NOT NULL AUTO_INCREMENT,
  attempt_token CHAR(36) NOT NULL,
  action_next_id INT NOT NULL,
  web_user_id INT NOT NULL,
  client_id INT NOT NULL,
  run_status VARCHAR(10) NOT NULL,
  active_action_id INT GENERATED ALWAYS AS (CASE WHEN run_status IN ('CLAIMED', 'RUNNING') THEN action_next_id ELSE NULL END) STORED,
  planning_date DATE NOT NULL,
  target_action_next_id INT NULL,
  session_id VARCHAR(64) NULL,
  root_key VARCHAR(10) NULL,
  relative_path VARCHAR(500) NULL,
  claimed_date DATETIME NOT NULL,
  lease_expires_date DATETIME NOT NULL,
  heartbeat_date DATETIME NULL,
  finished_date DATETIME NULL,
  result_json MEDIUMTEXT NULL,
  error_text TEXT NULL,
  failure_action_next_id INT NULL,
  retry_requested_date DATETIME NULL,
  PRIMARY KEY (run_id),
  UNIQUE KEY uk_agent_run_token (attempt_token),
  UNIQUE KEY uk_agent_run_active (active_action_id),
  KEY idx_agent_run_action (action_next_id, run_id),
  KEY idx_agent_run_user_status (web_user_id, run_status)
);

-- Park writes one History entry; request_key makes a retried Park (lost response, queued
-- callback) find the first one instead of adding another. Merge records the survivor on the
-- absorbed action's History entry, so the survivor's History can reach the absorbed chain.
ALTER TABLE action_taken
  ADD COLUMN request_key VARCHAR(80) NULL,
  ADD COLUMN survivor_action_next_id INT NULL,
  ADD UNIQUE KEY uk_action_taken_request_key (request_key),
  ADD KEY idx_action_taken_survivor (survivor_action_next_id);

-- web_api_client.client_type gains RUNNER (the PC's unattended runner) and LAUNCHER (the PC's
-- link handler and session commands). Each has its own key, stored hashed, so either can be
-- revoked without touching the others. No schema change: client_type is VARCHAR(10).
