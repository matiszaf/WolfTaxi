#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
read -rp "E-mail kierowcy [t1@wolftaxi.pl]: " EMAIL
EMAIL=${EMAIL:-t1@wolftaxi.pl}
read -rp "ID taxi [TX1]: " TAXI
TAXI=${TAXI:-TX1}
read -rp "Numer taxi [1]: " NUMBER
NUMBER=${NUMBER:-1}
read -rp "Nazwa [Taxi 1]: " NAME
NAME=${NAME:-"Taxi 1"}
read -rsp "Hasło (min. 8 znaków): " PASSWORD
echo
DRIVER_EMAIL="$EMAIL" DRIVER_PASSWORD="$PASSWORD" DRIVER_TAXI_ID="$TAXI" DRIVER_NUMBER="$NUMBER" DRIVER_NAME="$NAME" node scripts/create-driver.js
unset PASSWORD DRIVER_PASSWORD
