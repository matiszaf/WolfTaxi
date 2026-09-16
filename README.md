# WolfTaxi 0.3 · Oracle Edition

WolfTaxi został odłączony od usług chmurowych Firebase. Źródłem prawdy jest teraz własny backend uruchamiany na serwerze Oracle Cloud.

## Architektura

```text
Android WolfTaxi
      │ HTTPS + JWT
      ▼
Node.js / Express API (Oracle VM)
      │
      ▼
PostgreSQL (ta sama Oracle VM)
```

W aplikacji nie ma Firebase Auth, Firestore, Realtime Database, FCM ani Cloud Functions. GPS jest wysyłany bezpośrednio do własnego API, a aktualny stan terminala jest synchronizowany z serwerem co ok. 2,5 s. Podczas aktywnej zmiany foreground service wysyła GPS i potrafi wyświetlić lokalne powiadomienie o nowym zleceniu.

## Co już obsługuje backend

- logowanie kierowców i tokeny JWT,
- rozpoczęcie i zakończenie zmiany,
- statusy kierowcy,
- regiony `R1...` i kolejki,
- taryfy `T1...`,
- strefy `S1...`,
- GPS i wykrywanie polygonów regionów/stref,
- ofertę zlecenia z timeoutem,
- przyjmowanie / odrzucanie / wygasanie oferty,
- przebieg kursu do `completed`,
- historię kursów,
- komunikaty centrali,
- testowe zlecenie w trybie developerskim.

## Dane startowe

`npm run seed` tworzy/aktualizuje:

- `T1` i `T2`,
- `R1`,
- `S1`,
- pierwszy komunikat systemowy.

Konto kierowcy tworzy się osobno skryptem `server/scripts/CREATE_DRIVER.sh`, dzięki czemu hasło nie trafia do repozytorium.

## Android

Publiczny adres API zapisuje się lokalnie, poza Git:

```powershell
.\SET_API_URL.ps1 -Url "https://TWOJA-DOMENA/wolftaxi-api"
```

Następnie:

```powershell
.\BUILD_AND_INSTALL.ps1
```

Jeżeli `wolftaxi.apiUrl` nie jest ustawiony, aplikacja celowo uruchamia tryb DEMO.

## Serwer Oracle

Skopiuj katalog `server` na VM i uruchom:

```bash
cd server
chmod +x INSTALL_ORACLE_UBUNTU.sh scripts/CREATE_DRIVER.sh
./INSTALL_ORACLE_UBUNTU.sh
```

Skrypt instaluje PostgreSQL, w razie potrzeby Node.js 20, tworzy bazę, losowe sekrety, uruchamia migracje i usługę systemd. API nasłuchuje wyłącznie na `127.0.0.1:8081`; do Internetu powinno być wystawione przez istniejący Apache + HTTPS.

Potem utwórz Taxi 1:

```bash
sudo -u wolftaxi /opt/wolftaxi-api/scripts/CREATE_DRIVER.sh
```

Możesz użyć e-maila `t1@wolftaxi.pl`, `TX1`, numeru `1` i wybranego hasła.

## Apache

W istniejącym VirtualHost HTTPS wstaw zawartość:

`server/apache/wolftaxi-api.conf.example`

Przykładowy publiczny endpoint po tym ustawieniu:

```text
https://twoja-domena.pl/wolftaxi-api/health
```

Powinien zwrócić JSON z `"ok": true`.

## Aktualizacja backendu

Po kolejnych zmianach kodu serwera:

```bash
./UPDATE_ORACLE_UBUNTU.sh
```

## Bezpieczeństwo

- PostgreSQL nie jest wystawiany do Internetu.
- API domyślnie słucha tylko na localhost.
- Hasła są hashowane bcrypt.
- Sesje używają JWT.
- Logowanie ma rate limit.
- Produkcyjnie używaj wyłącznie HTTPS.
- Po zakończeniu testów ustaw `DEV_SIMULATION=false` w `/opt/wolftaxi-api/.env` i zrestartuj usługę.
