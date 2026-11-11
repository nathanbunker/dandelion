# Dandelion — development notes

## Local development environment

- **App server:** Tomcat 9, running as a Windows service on **port 8089**.
- **Local URL:** http://localhost:8089/dandelion/
- **Webapps folder:** `C:\Program Files\Apache Software Foundation\Tomcat 9.0\webapps`
- **Deploy:** `mvn package` builds `target/dandelion.war` (`finalName` is `dandelion`). Copy it over
  `webapps\dandelion.war`; Tomcat redeploys it on its own. Allow about **3 minutes** before testing.
  No service restart is needed. Build with `mvn clean package`: a plain `mvn package` has produced
  a WAR with the new `pom.properties` version but stale classes reused from `target/dandelion/`, so
  the version check alone can pass on an old build. Before copying, confirm a changed class carries
  the current build time (`unzip -l target/dandelion.war | grep <ChangedClass>.class`). After
  deploying, the page footer shows the running version (the public weekly report has no footer).
- **Stage for production after every build:** also copy the new WAR to the production server with
  `C:\WINDOWS\System32\OpenSSH\scp.exe target\dandelion.war deploy@dandelion-daily.org:/home/deploy/`
  (Windows OpenSSH has the key; Git Bash `scp` doesn't). This only stages it. Report which version
  is staged.
- **Deploy to production (when Nathan asks):** "deploy to production" / "push it live" is his go-ahead.
  1. Commit outstanding work; bump `<version>` in `pom.xml` if it still matches what production runs
     (production's login page shows `Version x.y.z`: `curl -s -L https://dandelion-daily.org/ | grep -o "Version [0-9.]*"`).
  2. Check for schema changes: any `src/db/vN.M.sql` added since the last deploy needs `-Migration vN.M`.
     If unsure whether one is already applied, ask; `/home/deploy/migrations/applied.log` lists those
     the script has run (older ones were applied by hand).
  3. Run from PowerShell in the repo: `.\deploy\deploy.ps1 dandelion [-Migration vN.M]`. It does a clean
     build, uploads, runs `sudo deploy-dandelion` (passwordless for these scripts only), and waits until
     the app answers. About 1-2 minutes of downtime.
  4. Confirm with the curl above that the live version is the new one, and report it.
  Rollback: `ssh deploy@dandelion-daily.org "sudo cp /home/deploy/ROOT.war.prev /opt/tomcat/webapps/ROOT.war && sudo systemctl restart tomcat"`
  (needs Nathan's sudo password). Bob's Adventure (`C:\dev\bunker\bobs-adventure`, Nathan's daughter's
  game, same Tomcat): `.\deploy\deploy.ps1 bobs-adventure`. Server scripts are versioned in `deploy/server/`.
  Details: `docs/Dandelion-Deployment.md`, "Local Deployment Command".
- **Local database:** MySQL 8, database `dandelion` (see the `interophub-dev-database` skill for access).
  It is a copy of production data.
- **Local MCP endpoint:** http://localhost:8089/dandelion/api/v1/mcp (client setup: `docs/MCP-Codex-Setup.md`).
  The `dandelion` MCP server configured in Claude Code points at **production**
  (https://dandelion-daily.org/api/v1/mcp), so MCP tool calls from a session hit production data, not
  the local deploy.
- Port 8080 on this machine is a different app (InteropHub), not Dandelion.

## Conventions

- Schema migrations: sequential `src/db/vN.M.sql`, applied by hand, each paired with `Model.java`,
  `Model.hbm.xml`, and a `<mapping resource=.../>` line in `hibernate.cfg.xml`.
- DAOs live in `org.openimmunizationsoftware.pt.doa` (not `dao`).
- The UI and the MCP tools should call the same service so their behavior can't drift.
- Bump `<version>` in `pom.xml` when preparing a deployable build.

## Key docs

- `docs/Dandelion_Daily_AI_Integration_Assessment.md`: MCP/AI integration design, phases, decisions.
- `docs/MCP-Feedback.md`: running list of MCP problems, ideas, and verification results.
- `docs/Dandelion-Deployment.md`: production deployment.
- `docs/Outstanding-Issues.md`: known issues logged for later (security alerts, dev tooling gaps).
