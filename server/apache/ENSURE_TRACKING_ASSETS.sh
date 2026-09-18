#!/usr/bin/env bash
set -euo pipefail
DIR=/opt/wolftaxi-api/public/track/leaflet
sudo mkdir -p "$DIR"
for asset in leaflet.js leaflet.css; do
  if [[ ! -s "$DIR/$asset" ]]; then
    echo "[WolfTaxi] Pobieram $asset..."
    sudo curl -fL --retry 3 --connect-timeout 10 "https://unpkg.com/leaflet@1.9.4/dist/$asset" -o "$DIR/$asset"
  fi
done
sudo test -s "$DIR/leaflet.js"
sudo test -s "$DIR/leaflet.css"
echo '[WolfTaxi] Lokalne pliki Leaflet OK.'
