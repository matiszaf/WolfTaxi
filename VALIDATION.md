# Walidacja 0.8

Sprawdzone statycznie:

- `node --check` dla backendu i panelu WWW,
- rola `sms_gateway` jest akceptowana przez JWT i admina,
- schema migracyjna używa `CREATE TABLE IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`,
- przyjęcie zlecenia w czterech ścieżkach uruchamia `queueTrackingSms`,
- prywatny gateway wymaga roli `sms_gateway`,
- numer odbiorcy jest walidowany przed umieszczeniem w kolejce,
- podpis release pozostaje obsługiwany przez dotychczasowy workflow GitHub Actions.

Finalny compile Androida wykonuje GitHub Actions z Android SDK 35.
