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
  (Windows OpenSSH has the key; Git Bash `scp` doesn't). This only stages it; Nathan runs
  `.\deploy\deploy.ps1 dandelion [-Migration vN.M]` (or `sudo deploy-dandelion` on the server) when
  ready to go live. Server scripts are versioned in `deploy/server/`. Report which version is staged. Details:
  `docs/Dandelion-Deployment.md`, "Local Deployment Command".
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
