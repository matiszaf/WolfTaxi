# WolfTaxi 0.8.1 — automatyczne SMS

- SMS z linkiem śledzenia po przyjęciu/przypisaniu kursu.
- Automatyczny SMS `NA MIEJSCU` po zmianie statusu na `arrived`.
- Automatyczny SMS po zakończeniu kursu z podziękowaniem i kwotą, jeśli jest dostępna.
- Wiadomości są idempotentne: jeden SMS danego typu na jedno zlecenie.
- Retry bramki pozostaje do 3 prób.
- Tracking klienta korzysta z lokalnego Leaflet JS/CSS.
- Instalator serwera automatycznie pobiera Leaflet i konfiguruje `/track/` oraz `/track-static/` zarówno dla HTTP, jak i SSL vhosta.
- Ekran `REJONY` kierowcy pozostaje bez zmian.
