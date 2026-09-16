# WolfTaxi 0.4 — MultiRole / Oracle

Jedna aplikacja Android `pl.wolftaxi.app` obsługuje trzy tryby:

- `driver` — terminal kierowcy,
- `dispatcher` — mobilna dyspozytornia,
- `admin` — dyspozytornia + konfiguracja i konta.

Konto może mieć kilka ról. Wtedy po logowaniu aplikacja pokazuje wybór trybu bez ponownego logowania.

Backend działa na Oracle VM: Node.js + Express + PostgreSQL. Firebase nie jest używany.

## Nowe elementy 0.4

- tabela `users` i role wielokrotne,
- bezpieczna migracja istniejącego `t1@wolftaxi.pl` do roli `driver`,
- API dyspozytorni: snapshot taxi/zleceń, tworzenie/przypisanie/anulowanie zleceń i komunikaty,
- API administratora: konta, role, blokowanie kont, reset haseł, taryfy, regiony, strefy,
- `audit_log`,
- tryb Dyspozytor i Administrator w tej samej aplikacji Android,
- responsywny panel WWW z mapą OpenStreetMap/Leaflet pod `/dispatch/`,
- skrypt `CREATE_USER.sh` do tworzenia kont dispatcher/admin/driver,
- poprawione skrypty instalacji/aktualizacji (brak błędu `cd /opt/wolftaxi-api: Permission denied`).

## Aktualizacja istniejącego Oracle

Wgraj katalog `server` na serwer, a potem z katalogu przesłanego backendu:

```bash
chmod +x UPDATE_ORACLE_UBUNTU.sh scripts/*.sh
./UPDATE_ORACLE_UBUNTU.sh
curl http://127.0.0.1:8081/health
```

Po migracji istniejące Taxi 1 nadal loguje się tym samym e-mailem/hasłem.

## Konto centrali

```bash
sudo -u wolftaxi /opt/wolftaxi-api/scripts/CREATE_USER.sh
```

Przykład:

```text
E-mail: centrala@wolftaxi.pl
Nazwa: Centrala
Role: dispatcher,admin
Hasło: ********
```

## Apache

API:

```text
https://hosting.starcore.pl/wolftaxi-api/
```

Panel dyspozytorni:

```text
https://hosting.starcore.pl/dispatch/
```

Przykład konfiguracji jest w `server/apache/wolftaxi-api.conf.example`.

Po zmianie Apache:

```bash
sudo a2enmod proxy proxy_http headers
sudo apache2ctl configtest
sudo systemctl reload apache2
```

## APK z telefonu / GitHub Actions

Workflow `.github/workflows/android.yml` buduje APK z API:

```text
https://hosting.starcore.pl/wolftaxi-api
```

Po pushu do `main` pobierz artefakt `WolfTaxi-APK` przez `gh run download` w Termuxie.
