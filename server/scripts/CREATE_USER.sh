#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
read -r -p "E-mail [centrala@wolftaxi.pl]: " EMAIL; EMAIL=${EMAIL:-centrala@wolftaxi.pl}
read -r -p "Nazwa [Centrala]: " NAME; NAME=${NAME:-Centrala}
read -r -p "Role, przecinkami [dispatcher,admin]: " ROLES; ROLES=${ROLES:-dispatcher,admin}
read -r -s -p "Hasło (min. 8 znaków): " PASSWORD; echo
if [[ "$ROLES" == *driver* ]]; then
  read -r -p "ID taxi [TX1]: " TAXI; TAXI=${TAXI:-TX1}
  read -r -p "Numer taxi [1]: " NUMBER; NUMBER=${NUMBER:-1}
else
  TAXI=""; NUMBER="0"
fi
USER_EMAIL="$EMAIL" USER_PASSWORD="$PASSWORD" USER_NAME="$NAME" USER_ROLES="$ROLES" USER_TAXI_ID="$TAXI" USER_TAXI_NUMBER="$NUMBER" node scripts/create-user.js
