#!/bin/bash
# One-time (and after any change) install of the deploy scripts on the server.
#   cd /home/deploy/deploy-scripts && sudo bash install.sh
set -euo pipefail
cd "$(dirname "$0")"

install -o root -g root -m 755 deploy-dandelion /usr/local/bin/deploy-dandelion
install -o root -g root -m 755 deploy-bobs-adventure /usr/local/bin/deploy-bobs-adventure
mkdir -p /home/deploy/migrations
chown deploy:deploy /home/deploy/migrations

# Let the deploy user run exactly these two scripts without a sudo password.
SUDOERS=/etc/sudoers.d/deploy-scripts
TMP=$(mktemp)
echo "deploy ALL=(root) NOPASSWD: /usr/local/bin/deploy-dandelion, /usr/local/bin/deploy-bobs-adventure" > "$TMP"
visudo -cf "$TMP" >/dev/null
install -o root -g root -m 440 "$TMP" "$SUDOERS"
rm -f "$TMP"

[ -f /root/.dandelion.cnf ] || echo "NOTE: create /root/.dandelion.cnf (chmod 600) before deploying a migration."
echo "Installed deploy-dandelion and deploy-bobs-adventure."
