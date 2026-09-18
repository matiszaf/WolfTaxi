# WolfTaxi 0.9 — FULL PANEL APP

WolfTaxi 0.9 przenosi funkcje panelu WEB dyspozytora i administratora do tej samej aplikacji Android, bez zmiany stylu terminala kierowcy.

## Najważniejsze
- ekran `REJONY` kierowcy pozostaje bez zmian wizualnych,
- role kierowca / dyspozytor / administrator / bramka SMS nadal działają w jednej aplikacji,
- dyspozytor i administrator dostają panel w stylu istniejącej aplikacji, podzielony na zakładki,
- funkcje są podpięte do istniejącego Oracle API — nie są atrapami.

## Zakładki panelu w aplikacji
- **DYSPO** — statystyki, SOS, szybkie akcje, kierowcy, bieżące zlecenia, podsumowanie rozliczeń,
- **ZLEC.** — wszystkie zlecenia, tworzenie, przypisywanie, nakaz, anulowanie, link śledzenia klienta,
- **KIER.** — kierowcy, status, rejon/kolejka, taryfa, GPS, priorytety,
- **MAPA** — mapa floty LIVE,
- **KOMUN.** — komunikaty, ostrzeżenia, pilne, systemowe i pytania TAK/NIE; adresowanie do wszystkich/kierowcy/rejonu; ACK i TTS,
- **CRM** — klienci, firmy, vouchery oraz tworzenie nowych rekordów,
- **ROZL.** — podsumowanie dnia i zamykanie rozliczeń kursów,
- **HIST.** — audyt i historia systemu,
- **ADMIN** — konta, blokowanie/odblokowanie, taryfy, regiony i strefy taryfowe.

## Zlecenia
Formularz w aplikacji obsługuje pola z panelu WEB: adres odbioru/cel, region, taryfa, kolejka/giełda, źródło, termin, dane klienta, CRM, firma, voucher, centrum kosztów, numer rezerwacji, liczba pasażerów, kwota orientacyjna, karta, bagaż, zwierzę, język angielski, mina/ryzyko i uwagi.

## Zachowane funkcje wcześniejszych wersji
- logika regionów: `OK = obecny rejon`, `KURSEM/DOJAZD = rejon docelowy`,
- tracking klienta z mapą LIVE,
- taksometr,
- automatyka SMS,
- bramka SMS działająca w tle,
- WebSockety,
- stały podpis APK.

## Instalacja
Backend nie wymaga osobnej aktualizacji dla funkcji panelu 0.9 — aplikacja korzysta z endpointów już dostępnych na serwerze WolfTaxi. Zbuduj APK przez istniejący workflow GitHub Actions ze stałym kluczem podpisu.
