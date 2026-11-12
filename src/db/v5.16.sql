-- Devices for Dandelion Sundial (the Android app). Each installed app is a
-- web_api_client of type DEVICE. A device signs in with a username and password,
-- starts PENDING, and only works after its owner approves it on the Devices page
-- (DevicesServlet). Revoking it stops it on its next request.
-- Device keys are stored as "sha256:<hex>" in api_key; the raw key is only ever on
-- the phone. Existing agent keys are untouched (client_type defaults to AGENT).
-- Must be applied before deploying 5.16.x, which maps the columns.
ALTER TABLE web_api_client
  ADD COLUMN client_type VARCHAR(10) NOT NULL DEFAULT 'AGENT',
  ADD COLUMN device_status VARCHAR(10) NULL,
  ADD COLUMN device_name VARCHAR(80) NULL,
  ADD COLUMN approved_date DATETIME NULL,
  ADD COLUMN revoked_date DATETIME NULL;

-- Sundial step 3: undo receipts and idempotency keys for the phone's writes.
-- action_undo: the before/after state of every action a phone change touched, so
-- POST /api/v1/personal/undo/{token} can put it back (only if nothing changed since,
-- only by the same client, only for 5 minutes).
CREATE TABLE action_undo (
  undo_id INT NOT NULL AUTO_INCREMENT,
  client_id INT NOT NULL,
  token VARCHAR(64) NOT NULL,
  summary VARCHAR(250) NULL,
  snapshot MEDIUMTEXT NOT NULL,
  created_date DATETIME NOT NULL,
  expires_date DATETIME NOT NULL,
  used_date DATETIME NULL,
  PRIMARY KEY (undo_id),
  UNIQUE KEY action_undo_token (token),
  KEY action_undo_expires (expires_date)
);

-- api_idempotency: the response to each phone write, by the Idempotency-Key the phone
-- sent, so a retried tap gets the first answer instead of changing anything twice.
-- Rows older than a day are deleted on the next write.
CREATE TABLE api_idempotency (
  idempotency_id INT NOT NULL AUTO_INCREMENT,
  client_id INT NOT NULL,
  idem_key VARCHAR(80) NOT NULL,
  request_path VARCHAR(200) NOT NULL,
  response_status INT NOT NULL,
  response_body MEDIUMTEXT NULL,
  created_date DATETIME NOT NULL,
  PRIMARY KEY (idempotency_id),
  UNIQUE KEY api_idempotency_key (client_id, idem_key),
  KEY api_idempotency_created (created_date)
);
