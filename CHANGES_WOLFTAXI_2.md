# WolfTaxi 0.5.2 — RT3000 REGION + ACTION

- Kod rejonu jest teraz argumentem dla przycisków ruchu.
- `2` + `KURSEM` = status KURSEM z celem ustawionym na rejon 2.
- `2` + `DOJAZD` = status DOJAZD z celem ustawionym na rejon 2.
- `OK` nadal oznacza zgłoszenie rejonu / wejście do kolejki.
- Cel rejonu jest osobnym polem `target_region_id`; nie udajemy, że kierowca już znajduje się w rejonie docelowym.
- Cel jest widoczny w terminalu jako `→ REJON` i w panelu dyspozytora.
- WOLNY/PRZERWA/ZAJĘTY czyszczą cel przejazdu.
- Zachowane stałe podpisywanie APK i poprawiona weryfikacja `apksigner`.

# WolfTaxi 0.5.1 — RT3000 Terminal

Ta wersja rozwija 0.5 RT3000 Core przede wszystkim po stronie terminala kierowcy i workflow centrali.

## Terminal kierowcy
- dodano stałe zakładki u góry: `REJONY`, `ZLEC.`, `GIEŁDA`, `CENTR.`, `MENU`,
- dodano terminalową klawiaturę numeryczną `0–9` do zgłaszania rejonu,
- `OK` zgłasza wybrany kod rejonu / wchodzi do kolejki,
- `C` kasuje wpisany kod,
- dodano duże przyciski funkcji: `KURSEM`, `DOJAZD`, `WOLNY`, `PRZERWA`, `ZAJĘTY`, `NA MIEJSCU`, `TARYFA`, `SOS`,
- dodano aktywne `TAK / NIE` po otrzymaniu pytania od centrali,
- `TAK / NIE` są nieaktywne, gdy brak oczekującego pytania,
- dodano legendę kodów numerycznych regionów wraz ze stanem kolejki,
- dodano gęsty pasek stanu: status kierowcy, rejon/pozycja i taryfa/strefa,
- zakładka zlecenia pokazuje ofertę i aktywny kurs,
- zakładka giełdy pokazuje dostępne zlecenia,
- zakładka centrali pokazuje pytania, komunikaty i SOS,
- zakładka menu zawiera zmianę, historię, wybór regionu/taryfy i sesję.

## Statusy terminalowe
Backend 0.5.1 akceptuje dodatkowe statusy terminala:
- `course` — KURSEM,
- `busy` — ZAJĘTY,
- `driving_to_pickup` — DOJAZD bez aktywnego zlecenia,
- zachowane: `available`, `break`, `out_of_service`.

Przy aktywnym zleceniu etap kursu ma pierwszeństwo nad ręczną zmianą statusu.

## Pytania centrali TAK/NIE
- dyspozytor może wysłać typ `question`,
- kierowca odpowiada z głównego terminala lub zakładki `CENTR.`,
- odpowiedź jest zapisywana w PostgreSQL,
- panel operatora otrzymuje liczniki odpowiedzi `TAK / NIE`.

## UI
Interfejs został zagęszczony w stronę terminalowego workflow RT3000: ciemne tło, zielone nagłówki, monospace, kolorowe stany i funkcje dostępne bez przechodzenia przez rozbudowane menu.

## Podpis APK
Pozostaje stały podpis release przez GitHub Actions Secrets. Kolejne wersje po pierwszej instalacji podpisanego release APK aktualizują się bez reinstalacji.
