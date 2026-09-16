#!/usr/bin/env bash
set -euo pipefail

if ! command -v sudo >/dev/null 2>&1; then echo "Brak sudo."; exit 1; fi
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "[WolfTaxi] Instalacja backendu na Oracle/Ubuntu"
sudo apt-get update
sudo apt-get install -y postgresql postgresql-contrib rsync openssl curl ca-certificates apache2

NEED_NODE=1
if command -v node >/dev/null 2>&1; then
  MAJOR=$(node -p "Number(process.versions.node.split('.')[0])" 2>/dev/null || echo 0)
  if [ "$MAJOR" -ge 18 ]; then NEED_NODE=0; fi
fi
if [ "$NEED_NODE" -eq 1 ]; then
  echo "[WolfTaxi] Instalowanie Node.js 20 (NodeSource)..."
  curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
  sudo apt-get install -y nodejs
fi

echo "[WolfTaxi] Node: $(node --version)"

if ! id wolftaxi >/dev/null 2>&1; then
  sudo useradd --system --home /opt/wolftaxi-api --shell /usr/sbin/nologin wolftaxi
fi

sudo mkdir -p /opt/wolftaxi-api
if [ ! -f /opt/wolftaxi-api/.env ]; then
  DB_PASS=$(openssl rand -hex 24)
  JWT_SECRET=$(openssl rand -hex 48)
  if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='wolftaxi'" | grep -q 1; then
    sudo -u postgres psql -c "CREATE USER wolftaxi WITH PASSWORD '$DB_PASS';"
  else
    sudo -u postgres psql -c "ALTER USER wolftaxi WITH PASSWORD '$DB_PASS';"
  fi
  if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='wolftaxi'" | grep -q 1; then
    sudo -u postgres createdb -O wolftaxi wolftaxi
  fi
  sudo tee /opt/wolftaxi-api/.env >/dev/null <<ENV
PORT=8081
HOST=127.0.0.1
DATABASE_URL=postgresql://wolftaxi:${DB_PASS}@127.0.0.1:5432/wolftaxi
JWT_SECRET=${JWT_SECRET}
JWT_EXPIRES_IN=7d
OFFER_TIMEOUT_SECONDS=20
DEV_SIMULATION=true
ENV
  sudo chmod 600 /opt/wolftaxi-api/.env
else
  echo "[WolfTaxi] Zachowuję istniejący /opt/wolftaxi-api/.env"
fi

sudo rsync -a --delete \
  --exclude '.env' --exclude 'node_modules' \
  "$ROOT_DIR/" /opt/wolftaxi-api/
sudo chown -R wolftaxi:wolftaxi /opt/wolftaxi-api
sudo chmod 600 /opt/wolftaxi-api/.env

sudo -u wolftaxi /bin/bash -c 'cd /opt/wolftaxi-api && npm install --omit=dev && npm run migrate && npm run seed'

sudo cp /opt/wolftaxi-api/systemd/wolftaxi-api.service /etc/systemd/system/wolftaxi-api.service
sudo systemctl daemon-reload
sudo systemctl enable --now wolftaxi-api

sleep 1
curl -fsS http://127.0.0.1:8081/health && echo

echo
cat <<MSG
[WolfTaxi] Backend działa lokalnie na 127.0.0.1:8081.
[WolfTaxi] Następnie:
  1) utwórz kierowcę: sudo -u wolftaxi /opt/wolftaxi-api/scripts/CREATE_DRIVER.sh
  2) dodaj reverse proxy Apache wg apache/wolftaxi-api.conf.example
  3) ustaw w Androidzie publiczny HTTPS URL przez SET_API_URL.ps1
MSG
