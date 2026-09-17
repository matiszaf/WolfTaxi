# WolfTaxi 0.5 — RT3000 Core / Oracle

WolfTaxi 0.5 rozwija wersję MultiRole w stronę pełnego workflow RT3000. Nadal jest to **jedna aplikacja Android** (`pl.wolftaxi.app`) dla kierowcy, dyspozytora i administratora, plus panel WWW dyspozytorni.

## Role
- `driver` — terminal kierowcy,
- `dispatcher` — dyspozytornia,
- `admin` — dyspozytornia + administracja.

Konto może mieć kilka ról i przełączać tryb bez ponownego logowania.

## Najważniejsze elementy 0.5
- WebSocket live (`/ws`) zamiast polegania wyłącznie na pollingu,
- regiony, kolejki, pozycje i priorytety,
- statystyki regionów,
- giełda zleceń,
- zlecenia z nakazu,
- SOS kierowcy,
- komunikaty centrali z ACK,
- TTS komunikatów i ofert,
- rozszerzone wymagania kursu,
- mobilny tryb kierowcy / dyspozytora / admina w jednej aplikacji,
- panel WWW pod `/dispatch/`.

## Backend
Oracle VM + Node.js + Express + PostgreSQL. Firebase nie jest używany.

Docelowa domena:

```text
https://wolftaxi.starcore.pl
```

Endpointy:

```text
https://wolftaxi.starcore.pl/health
https://wolftaxi.starcore.pl/api/v1/...
https://wolftaxi.starcore.pl/dispatch/
wss://wolftaxi.starcore.pl/ws
```

## Aktualizacja istniejącego Oracle 0.4 → 0.5
Wyślij katalog `server` na Oracle i uruchom z katalogu przesłanego backendu:

```bash
chmod +x UPDATE_ORACLE_UBUNTU.sh scripts/*.sh apache/*.sh
./UPDATE_ORACLE_UBUNTU.sh
curl http://127.0.0.1:8081/health
```

Oczekiwany healthcheck zawiera:

```text
"version":"0.5.0"
```

Migracja zachowuje istniejących użytkowników, Taxi 1, role i hasła.

## Apache / WebSocket
Włącz wymagane moduły:

```bash
sudo a2enmod proxy proxy_http proxy_wstunnel headers rewrite ssl
```

Przykładowy vhost jest w:

```text
server/apache/wolftaxi.starcore.pl.conf.example
```

Jeśli masz już działający vhost z Certbotem, dodaj do niego przede wszystkim proxy `/api/`, `/dispatch/`, `/health` i `/ws`, a następnie:

```bash
sudo apache2ctl -t
sudo systemctl reload apache2
```

## GitHub Actions / APK z telefonu
Workflow `.github/workflows/android.yml` wpisuje podczas builda:

```text
wolftaxi.apiUrl=https://wolftaxi.starcore.pl
```

Po pushu do `main` pobierz artefakt `WolfTaxi-APK` przez `gh run download` w Termuxie.

## Uwaga o zgodności z RT3000
0.5 implementuje rdzeń funkcjonalny, ale nie oznacza jeszcze 100% zgodności RT3000. Kolejne wersje mają domknąć pozostałe funkcje i coraz dokładniej odwzorowywać wygląd oraz workflow ekran po ekranie.

## Stały podpis APK / aktualizacje bez reinstalacji

WolfTaxi używa jednego, prywatnego klucza release przechowywanego w GitHub Actions Secrets. Workflow buduje `assembleRelease`, automatycznie zwiększa `versionCode` i przed publikacją sprawdza SHA-256 certyfikatu APK.

Pierwsza migracja ze starego APK podpisanego tymczasowym kluczem debug może wymagać jednorazowego odinstalowania starej aplikacji. Po zainstalowaniu pierwszego APK z nowym stałym podpisem kolejne wersje instalują się jako zwykłe aktualizacje, bez odinstalowywania danych aplikacji.

Konfiguracja z Termuxa:

```bash
cd ~/WolfTaxi
./scripts/SETUP_SIGNING_TERMUX.sh matiszaf/WolfTaxi
```

Klucza `.jks` ani haseł nigdy nie dodawaj do Git.
