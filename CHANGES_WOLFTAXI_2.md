# WolfTaxi 0.8.3 — zmiany

- `OK` oznacza wyłącznie bieżący rejon.
- `KURSEM` i `DOJAZD` z wpisanym kodem oznaczają rejon docelowy.
- Aktywny kurs nie blokuje ustawiania bieżącego rejonu.
- Dodano endpoint `POST /api/v1/driver/region/current`.
- Bieżący i docelowy rejon są utrzymywane niezależnie.
- Dołączenie do kolejki po `OK` następuje wyłącznie w dozwolonym statusie.
- Osiągnięcie celu (`current == target`) automatycznie czyści cel.
- Dodano zdarzenia `region.current` i `target.reached` oraz wpisy audytu.
- Wygląd panelu REJONY nie został zmieniony.


### 0.8.4
- trwała bramka SMS niezależna od aktywnego trybu UI
- foreground service pozostaje aktywny po zmianie trybu
- automatyczny restart po BOOT_COMPLETED
- flaga enabled nie jest zerowana przez systemowe zniszczenie usługi
- stopWithTask=false
