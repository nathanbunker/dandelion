# Dandelion Deployment Architecture

This document describes the production deployment for **Dandelion** and the workflow for updating it.

This intentionally omits credentials and sensitive configuration.

---

# Production Overview

Production stack:

```
Domain:     dandelion-daily.org
Server:     Hetzner VPS (Ubuntu 24.04)
Web:        Nginx (HTTPS via Let's Encrypt)
App:        Tomcat 9
Database:   MySQL 8
Email:      SMTP provider (Zoho)
Backups:    Nightly mysqldump + cron
Deploy:     WAR upload + restart script
```

Public URL:

```
https://dandelion-daily.org
```

Architecture:

```
Internet
   ↓
Nginx (80/443)
   ↓ reverse proxy
Tomcat (8080)
   ↓
Dandelion WAR
   ↓
MySQL
```

---

# Server Layout

```
/opt/tomcat
    /webapps
        ROOT.war              Dandelion, served at /
        /ROOT
        bobs-adventure.war    Bob's Adventure (static game), served at /bobs-adventure/
        /bobs-adventure

/home/deploy
    dandelion.war             staged Dandelion build
    bobs-adventure.war        staged game build
    ROOT.war.prev             previous Dandelion WAR (rollback)
    /migrations               staged vN.M.sql files + applied.log
    /deploy-scripts           copy of deploy/server/ from the repo
    backup-dandelion.sh

/root/.dandelion.cnf          MySQL credentials for migrations (chmod 600)

/var/backups/dandelion
    dandelion_YYYY-MM-DD.sql.gz
    pre-vN.M_YYYY-MM-DD_HH-MM.sql.gz   taken automatically before each migration

/usr/local/bin
    deploy-dandelion
    deploy-bobs-adventure
```

---

# Services

## Nginx

Handles:

- HTTPS termination
- LetsEncrypt certs
- reverse proxy to Tomcat

Flow:

```
443 → nginx → localhost:8080 → Tomcat
```

---

## Tomcat

Installed manually under:

```
/opt/tomcat
```

Runs as:

```
tomcat user
```

Managed by:

```
systemctl start tomcat
systemctl stop tomcat
systemctl status tomcat
```

---

## MySQL

Local MySQL instance:

```
localhost
```

Used only by Dandelion.

Backed up nightly.

---

# Deployment Workflow

Deployment is **WAR-based**.

Local build:

```
mvn package
```

Produces:

```
target/dandelion.war
```

Deployment flow:

```
Local machine
    ↓ scp
/home/deploy/dandelion.war
    ↓
deploy script
    ↓
/opt/tomcat/webapps/dandelion.war
    ↓
Tomcat restart
```

---

# Deployment Script (Server)

The server scripts live in the repo under `deploy/server/` and are installed to `/usr/local/bin`.

`deploy-dandelion [vN.M.sql]`:

- if a migration is named: refuse if `migrations/applied.log` already lists it
- stop Tomcat
- if a migration is named: back up the DB to `/var/backups/dandelion/pre-…sql.gz`, apply
  `/home/deploy/migrations/vN.M.sql`, record it in `applied.log` (on failure, restart the old
  version and print the restore command)
- save the current `ROOT.war` as `/home/deploy/ROOT.war.prev`
- replace `webapps/ROOT.war` with the staged WAR, fix ownership, start Tomcat
- wait (up to 3 minutes) until the app answers on `localhost:8080`

`deploy-bobs-adventure`: swaps `webapps/bobs-adventure.war` in place without restarting Tomcat
(Dandelion stays up) and waits until the page serves the new build stamp.

Rollback (Dandelion):

```
sudo cp /home/deploy/ROOT.war.prev /opt/tomcat/webapps/ROOT.war && sudo systemctl restart tomcat
```

## Installing or updating the server scripts

After changing anything in `deploy/server/`, from PowerShell in the repo:

```
C:\WINDOWS\System32\OpenSSH\ssh.exe deploy@dandelion-daily.org "mkdir -p deploy-scripts"
C:\WINDOWS\System32\OpenSSH\scp.exe deploy\server\deploy-dandelion deploy\server\deploy-bobs-adventure deploy\server\install.sh deploy@dandelion-daily.org:/home/deploy/deploy-scripts/
C:\WINDOWS\System32\OpenSSH\ssh.exe -t deploy@dandelion-daily.org "cd deploy-scripts && sudo bash install.sh"
```

`install.sh` also adds `/etc/sudoers.d/deploy-scripts`, letting the `deploy` user run only these two
scripts without a sudo password. Migrations need `/root/.dandelion.cnf`:

```
[client]
user=dandelion
password=...
```

---

# Local Deployment Command

**One command (normal path).** From PowerShell in the repo:

```
.\deploy\deploy.ps1 dandelion                     # clean build, upload, deploy, wait until up
.\deploy\deploy.ps1 dandelion -Migration v5.15    # same, plus DB backup + src\db\v5.15.sql first
.\deploy\deploy.ps1 dandelion -SkipBuild          # deploy target\dandelion.war as-is
.\deploy\deploy.ps1 dandelion -StageOnly          # upload only
.\deploy\deploy.ps1 bobs-adventure                # build ..\bobs-adventure and deploy it
```

The manual steps below are what the script does.

**1. Stage the WAR (after every build).** After each `mvn clean package`, copy the new WAR to the
server's staging spot. That way `/home/deploy/dandelion.war` is always the latest build, ready
whenever Nathan decides to deploy. Staging changes nothing in production; Tomcat keeps running the
WAR it already has.

```
scp target/dandelion.war deploy@dandelion-daily.org:/home/deploy/
```

On the Windows dev machine, use the Windows OpenSSH client
(`C:\WINDOWS\System32\OpenSSH\scp.exe`, for example from PowerShell). It has the `deploy` key;
Git Bash's `scp` does not, and fails with `Permission denied (publickey,password)`.

To confirm the upload, compare checksums:

```
ssh deploy@dandelion-daily.org "sha256sum /home/deploy/dandelion.war"
(Get-FileHash target\dandelion.war -Algorithm SHA256).Hash.ToLower()   # PowerShell, local
```

**2. Deploy (Nathan, when ready).**

```
ssh deploy@dandelion-daily.org "sudo deploy-dandelion"
```

This performs a full production update from the staged WAR. If the release includes a schema
migration, upload `src/db/vN.M.sql` to `/home/deploy/migrations/` and run
`sudo deploy-dandelion vN.M.sql`, which applies it while Tomcat is stopped.

---

# Backups

Backups stored in:

```
/var/backups/dandelion
```

Format:

```
dandelion_YYYY-MM-DD_HH-MM.sql.gz
```

Backup script:

```
/home/deploy/backup-dandelion.sh
```

Cron:

```
0 2 * * * /home/deploy/backup-dandelion.sh
```

Runs nightly at 2am.

---

# Restore Procedure

Manual restore:

```
gunzip < backup.sql.gz | mysql dandelion
```

Or:

```
mysql dandelion < backup.sql
```

---

# Email

SMTP-based sending configured.

Uses:

```
TLS SMTP
authenticated user
domain sender address
```

App configured with:

```
SMTP host
SMTP port
username
password
TLS enabled
```

Email sending handled by application.

---

# Firewall

Open ports:

```
22   SSH
80   HTTP
443  HTTPS
```

Tomcat (8080) internal use only.

---

# SSL

Managed via:

```
certbot
```

Certificates:

```
/etc/letsencrypt/live/dandelion-daily.org/
```

Auto-renew configured.

---

# Update Procedure (Full)

Production update:

`.\deploy\deploy.ps1 dandelion [-Migration vN.M]` does all of it:

1. Build WAR locally (`mvn clean package`)
2. Upload WAR (and migration, if any) to the server
3. Run `sudo deploy-dandelion [vN.M.sql]`
4. Tomcat restarts; the script waits until the app answers
5. App live

Total deploy time:

~10 seconds

No server login required beyond command.

---

# Operational Commands

Check app:

```
systemctl status tomcat
```

Check logs:

```
/opt/tomcat/logs/catalina.out
```

Restart:

```
sudo systemctl restart tomcat
```

Check nginx:

```
sudo systemctl status nginx
```

Reload nginx:

```
sudo systemctl reload nginx
```

---

# Result

You now have:

- production VPS
- domain
- HTTPS
- Tomcat deploy
- MySQL
- backups
- email
- one-command deployment

This is a full production-grade Java servlet deployment.
