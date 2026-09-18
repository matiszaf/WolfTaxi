#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO="${1:-matiszaf/WolfTaxi}"
KEY_DIR="$HOME/.wolftaxi-signing"
KEYSTORE="$KEY_DIR/wolftaxi-release.jks"
ALIAS="wolftaxi"

if ! command -v gh >/dev/null 2>&1; then
  echo "[WolfTaxi] Brak gh. Zainstaluj: pkg install -y gh"
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "[WolfTaxi] Najpierw zaloguj GitHub CLI: gh auth login"
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  if command -v pkg >/dev/null 2>&1; then
    echo "[WolfTaxi] Instaluję OpenJDK (keytool)..."
    pkg install -y openjdk-17
  else
    echo "[WolfTaxi] Brak keytool. Zainstaluj JDK 17."
    exit 1
  fi
fi

mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"

echo "[WolfTaxi] Stały podpis APK dla repo: $REPO"
echo "[WolfTaxi] UWAGA: utrata tego klucza oznacza brak możliwości aktualizacji istniejącej instalacji APK."

if [ -f "$KEYSTORE" ]; then
  echo "[WolfTaxi] Używam istniejącego klucza: $KEYSTORE"
  read -rsp "Hasło istniejącego klucza: " PASS
  echo
else
  while true; do
    read -rsp "Ustaw hasło klucza (min. 12 znaków): " PASS
    echo
    read -rsp "Powtórz hasło: " PASS2
    echo
    if [ "$PASS" != "$PASS2" ]; then
      echo "Hasła są różne. Spróbuj ponownie."
      continue
    fi
    if [ "${#PASS}" -lt 12 ]; then
      echo "Hasło musi mieć co najmniej 12 znaków."
      continue
    fi
    break
  done

  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storetype JKS \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -storepass "$PASS" \
    -keypass "$PASS" \
    -dname "CN=WolfTaxi, O=StarCore, C=PL"
  chmod 600 "$KEYSTORE"
fi

# Sprawdzenie, czy hasło/alias są poprawne.
LC_ALL=C keytool -list -keystore "$KEYSTORE" -alias "$ALIAS" -storepass "$PASS" >/dev/null

CERT_SHA256=$(LC_ALL=C keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" -storepass "$PASS" \
  | awk -F'SHA256: ' '/SHA256:/{print $2; exit}' \
  | tr -d '[:space:]' \
  | tr '[:lower:]' '[:upper:]')

if [ -z "$CERT_SHA256" ]; then
  echo "[WolfTaxi] Nie udało się odczytać SHA-256 certyfikatu."
  exit 1
fi

echo "[WolfTaxi] Wysyłam klucz do GitHub Actions Secrets..."
base64 "$KEYSTORE" | tr -d '\n' | gh secret set WOLFTAXI_KEYSTORE_B64 -R "$REPO"
printf '%s' "$PASS" | gh secret set WOLFTAXI_KEYSTORE_PASSWORD -R "$REPO"
printf '%s' "$ALIAS" | gh secret set WOLFTAXI_KEY_ALIAS -R "$REPO"
printf '%s' "$PASS" | gh secret set WOLFTAXI_KEY_PASSWORD -R "$REPO"
printf '%s' "$CERT_SHA256" | gh secret set WOLFTAXI_CERT_SHA256 -R "$REPO"

cat > "$KEY_DIR/README.txt" <<TXT
WolfTaxi signing key
====================
Keystore: $KEYSTORE
Alias: $ALIAS
Certificate SHA-256: $CERT_SHA256

HASŁO NIE JEST ZAPISANE W TYM PLIKU.
Zachowaj hasło w bezpiecznym miejscu.
Nie publikuj pliku .jks ani hasła w Git/GitHub.
TXT
chmod 600 "$KEY_DIR/README.txt"

echo
printf '[WolfTaxi] GOTOWE. Certyfikat SHA-256: %s\n' "$CERT_SHA256"
echo "[WolfTaxi] Klucz lokalny: $KEYSTORE"
echo "[WolfTaxi] Zrób prywatną kopię zapasową pliku .jks. Nie wrzucaj go do repozytorium."
echo "[WolfTaxi] Od następnego builda GitHub Actions będzie używał dokładnie tego samego podpisu."
