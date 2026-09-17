# Zmiany WolfTaxi 0.5.0 — RT3000 Core

## Kierunek wersji
0.5 traktuje WolfTaxi 0.4 jako bazę techniczną i przebudowuje rdzeń pracy pod workflow RT3000. Jedna aplikacja nadal obsługuje role `driver`, `dispatcher` i `admin`.

## Realtime
- WebSocket `/ws` z JWT.
- Natychmiastowe odświeżanie stanu kierowcy, dyspozytorni i administratora po zmianach serwerowych.
- Automatyczny reconnect po utracie połączenia.
- Apache vhost dla `wolftaxi.starcore.pl` z proxy WebSocket.

## Kierowca / RT3000 core
- statusy pracy i kolejki regionowe,
- statystyki regionów,
- giełda zleceń i przejęcie zlecenia,
- zlecenia z nakazu centrali,
- priorytet kierowcy w kolejce,
- SOS z lokalizacją kierowcy,
- komunikaty centrali z potwierdzeniem odczytu,
- TTS dla ofert, nakazów i komunikatów,
- dodatkowe wymagania zlecenia: bagaż, zwierzę, EN, oznaczenie ryzyka,
- pełniejszy przebieg aktywnego kursu.

## Dyspozytor / Administrator
- live snapshot floty i zleceń,
- obsługa alarmów SOS,
- ręczne zlecenie z nakazu dla konkretnego taxi,
- sterowanie priorytetem kierowcy,
- rozbudowane komunikaty kierowane do floty / regionu / taxi,
- zachowane funkcje MultiRole 0.4.

## Backend / PostgreSQL
- wersja API `0.5.0`,
- rozszerzony schemat kolejki, zleceń, komunikatów i profilu kierowcy,
- `safety_alerts`, `message_ack`, `driver_events`,
- migracje są idempotentne i zachowują istniejące konta oraz dane.

## Domena
Docelowy adres API i panelu:

```text
https://wolftaxi.starcore.pl
https://wolftaxi.starcore.pl/dispatch/
https://wolftaxi.starcore.pl/api/v1/...
wss://wolftaxi.starcore.pl/ws
```
