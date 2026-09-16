#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
sudo rsync -a --delete --exclude '.env' --exclude 'node_modules' "$ROOT_DIR/" /opt/wolftaxi-api/
sudo chown -R wolftaxi:wolftaxi /opt/wolftaxi-api
sudo -u wolftaxi /bin/bash -c 'cd /opt/wolftaxi-api && npm install --omit=dev && npm run migrate && npm run seed'
sudo systemctl restart wolftaxi-api
sudo systemctl --no-pager --full status wolftaxi-api | head -n 20
