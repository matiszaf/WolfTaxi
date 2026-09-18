# WolfTaxi 0.8.3 — LOGIKA REJONÓW

Ta wersja porządkuje semantykę klawiatury regionów bez zmiany wyglądu ekranu REJONY.

## Zasady terminala
- `KOD + OK` — zawsze ustawia **BIEŻĄCY rejon** kierowcy.
- `KOD + KURSEM` — ustawia **DOCELOWY rejon** i tryb KURSEM, jeśli nie ma aktywnego zlecenia; podczas aktywnego zlecenia zmienia tylko cel, nie etap kursu.
- `KOD + DOJAZD` — ustawia **DOCELOWY rejon** i tryb DOJAZD, jeśli nie ma aktywnego zlecenia; podczas aktywnego zlecenia zmienia tylko cel, nie etap kursu.
- Aktywne zlecenie z centrali nie blokuje `KOD + OK`.
- `current_region_id` i `target_region_id` są niezależne.
- Jeśli kierowca zatwierdzi przez `OK` rejon równy celowi, cel jest automatycznie zamykany.

## Kolejka
`OK` może automatycznie zapisać kierowcę do kolejki tylko wtedy, gdy:
- nie ma aktywnego zlecenia/oferty,
- status to `available` lub `in_queue`,
- rejon ma włączoną kolejkę,
- a przy rejonie z poligonem GPS potwierdza obecność w rejonie.

Podczas kursu `OK` zmienia wyłącznie bieżący rejon — nie zapisuje do kolejki i nie zmienia etapu zlecenia.

## Historia
Zmiany bieżącego rejonu są zapisywane jako `region.current`, a osiągnięcie wcześniej ustawionego celu jako `target.reached`. Trafiają również do audytu centrali.

## Pozostałe funkcje
Zachowane są funkcje 0.8.2/0.8.1: tracking klienta, mapa LIVE, taksometr, automatyka SMS, bramka SMS i stały podpis APK.


## 0.8.4 – SMS Gateway w tle
- Bramka SMS nie jest już zatrzymywana po zmianie trybu na Kierowca/Dyspozytor/Admin.
- Po ręcznym włączeniu działa jako foreground service także przy zminimalizowanej aplikacji i wygaszonym ekranie.
- Android może wznowić usługę po ubiciu procesu (`START_STICKY`).
- Po restarcie telefonu bramka jest automatycznie wznawiana po `BOOT_COMPLETED`, jeżeli była wcześniej włączona, sesja nadal ma rolę `sms_gateway` i jest przyznane `SEND_SMS`.
- Wyłączenie następuje wyłącznie przez przycisk WYŁĄCZ BRAMKĘ albo wylogowanie.
- Utrata internetu nie wyłącza bramki; kolejne odpytywanie backendu ponawia się automatycznie.
