# WolfTaxi 0.9.1

Poprawka taksometru do wersji 0.9 FULL PANEL APP.

## Logika taksometru

1. Rozpoczęcie kursu: naliczana jest `start_fee`.
2. Jazda: tylko dystans, w impulsach po 50 m.
3. `POSTÓJ`: ręcznie zgłoszony postój nalicza `waiting_price_per_hour`; w tym czasie dystans nie powiększa ceny.
4. `WZNÓW JAZDĘ`: kończy naliczanie postoju i wraca do dystansu.
5. Zakończenie: backend stosuje `minimum_fare`, jeśli wynik taksometru jest niższy.

Wymaga aktualizacji backendu i APK.
