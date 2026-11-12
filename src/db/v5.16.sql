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
