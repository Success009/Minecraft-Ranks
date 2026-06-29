#!/bin/bash
# MCR Matchmaking Server Deployment Script
# This script copies the updated matchmaking_server.py to the remote server and restarts the service.

SERVER_IP="100.120.244.95"
REMOTE_PATH="/opt/p2p_matchmaking"

echo "=== STARTING MCR BACKEND DEPLOYMENT ==="
echo "Target: root@$SERVER_IP:$REMOTE_PATH"

# Copy local matchmaking_server.py to remote server
echo "1. Uploading updated matchmaking_server.py..."
scp -o StrictHostKeyChecking=no matchmaking-server/matchmaking_server.py root@$SERVER_IP:$REMOTE_PATH/matchmaking_server.py

if [ $? -eq 0 ]; then
    echo "Successfully uploaded matchmaking_server.py!"
else
    echo "ERROR: Failed to upload matchmaking_server.py!"
    exit 1
fi

# Restart the matchmaking service
echo "2. Restarting p2p-matchmaking.service..."
ssh -o StrictHostKeyChecking=no root@$SERVER_IP "systemctl restart p2p-matchmaking.service"

if [ $? -eq 0 ]; then
    echo "Successfully restarted p2p-matchmaking.service!"
else
    echo "ERROR: Failed to restart p2p-matchmaking.service!"
    exit 1
fi

# Display status of the service
echo "3. Querying service status..."
ssh -o StrictHostKeyChecking=no root@$SERVER_IP "systemctl status p2p-matchmaking.service --no-pager"

echo "=== DEPLOYMENT COMPLETED SUCCESSFUL ==="
