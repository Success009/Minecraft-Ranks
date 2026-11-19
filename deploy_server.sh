#!/bin/bash
# MCR Matchmaking Server Deployment Script
# Uploads ALL server modules (the server imports them), backs up live data, restarts, and checks health.
# Auth stays in soft mode unless MCR_REQUIRE_AUTH=1 is set in the systemd unit.
set -e

SERVER_IP="100.120.244.95"
REMOTE_PATH="/opt/p2p_matchmaking"
SRC="matchmaking-server"
MODULES="matchmaking_server.py accounts.py challenges.py kits.py layouts.py presence.py skins.py social_routes.py spectate.py"

echo "=== STARTING MCR BACKEND DEPLOYMENT ==="
echo "Target: root@$SERVER_IP:$REMOTE_PATH"

echo "0. Compiling locally..."
(cd "$SRC" && python3 -m py_compile $MODULES)

echo "1. Backing up live code and data on the server..."
ssh -o StrictHostKeyChecking=no root@$SERVER_IP "cd $REMOTE_PATH && T=backups/pre-deploy-\$(date +%Y%m%d-%H%M%S) && mkdir -p \$T && cp -p $MODULES stats.json match_history.json accounts.json \$T/ 2>/dev/null; echo backup at \$T"

echo "2. Uploading modules..."
(cd "$SRC" && scp -o StrictHostKeyChecking=no $MODULES root@$SERVER_IP:$REMOTE_PATH/)

echo "3. Restarting p2p-matchmaking.service..."
ssh -o StrictHostKeyChecking=no root@$SERVER_IP "cd $REMOTE_PATH && python3 -m py_compile $MODULES && systemctl restart p2p-matchmaking.service"

echo "4. Health check..."
sleep 3
ssh -o StrictHostKeyChecking=no root@$SERVER_IP "systemctl is-active p2p-matchmaking.service && curl -s -m 5 -o /dev/null -w 'root=%{http_code}\n' localhost:8000/"

echo "=== DEPLOYMENT COMPLETED ==="
