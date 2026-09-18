#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
sudo rsync -a --delete --exclude '.env' --exclude 'node_modules' "$ROOT_DIR/" /opt/wolftaxi-api/
bash "$ROOT_DIR/apache/ENSURE_TRACKING_ASSETS.sh"
sudo chown -R wolftaxi:wolftaxi /opt/wolftaxi-api
sudo -u wolftaxi /bin/bash -c 'cd /opt/wolftaxi-api && npm install --omit=dev && npm run migrate && npm run seed'
sudo systemctl restart wolftaxi-api
for vhost in \
  /etc/apache2/sites-available/wolftaxi.starcore.pl.conf \
  /etc/apache2/sites-available/wolftaxi.starcore.pl-le-ssl.conf; do
  if [[ -f "$vhost" ]]; then
    bash "$ROOT_DIR/apache/ENABLE_TRACKING_PROXY.sh" "$vhost"
  fi
done
sudo systemctl --no-pager --full status wolftaxi-api | head -n 20
