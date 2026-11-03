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
        dandelion.war
        /dandelion

/home/deploy
    dandelion.war
    backup-dandelion.sh

/var/backups/dandelion
    dandelion_YYYY-MM-DD.sql.gz

/usr/local/bin
    deploy-dandelion
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

Location:

```
/usr/local/bin/deploy-dandelion
```

Responsibilities:

- stop Tomcat
- remove old deployment
- copy WAR
- fix ownership
- start Tomcat
- wait for deploy

Deployment command:

```
sudo deploy-dandelion
```

---

# Local Deployment Command

Going to production is two separate steps.

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
migration (`src/db/vN.M.sql`), apply it to production before deploying.

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

1. Build WAR locally (`mvn clean package`)
2. Upload WAR to server (`scp` to `/home/deploy/`, done after every build; see Local Deployment Command)
3. Run deploy script when ready (`sudo deploy-dandelion`)
4. Tomcat restarts
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
