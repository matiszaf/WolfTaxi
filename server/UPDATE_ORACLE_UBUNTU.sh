#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
sudo rsync -a --delete --exclude '.env' --exclude 'node_modules' "$ROOT_DIR/" /opt/wolftaxi-api/
sudo chown -R wolftaxi:wolftaxi /opt/wolftaxi-api
cd /opt/wolftaxi-api
sudo -u wolftaxi npm install --omit=dev
sudo -u wolftaxi npm run migrate
sudo -u wolftaxi npm run seed
sudo systemctl restart wolftaxi-api
sudo systemctl --no-pager --full status wolftaxi-api | head -n 20
