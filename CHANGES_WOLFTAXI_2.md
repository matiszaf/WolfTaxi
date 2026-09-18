# WolfTaxi 0.6 FULL RT3000 — zmiany

## Terminal kierowcy

- Zachowany ekran REJONY 0.5.4 bez zmian układu.
- Jawne kody numeryczne rejonów z backendu.
- KOD + OK / KURSEM / DOJAZD działa na identyfikatorze rejonu z bazy.
- Zlecenia, giełda, nakazy, komunikaty, TAK/NIE, TTS i SOS pozostają zintegrowane.
- Zakończenie kursu pyta o kwotę końcową i formę płatności.
- MENU pokazuje dzienne rozliczenie kierowcy.

## Kolejki / dispatch

- Pozycje w kolejce na żywo.
- Priorytet kierowcy i kolejki.
- Filtracja kandydatów po wymaganiach: karta, zwierzę, bagaż, angielski.
- Zlecenia planowane i automatyczne uwalnianie przed terminem.
- Giełda i nakaz centrali.

## Centrala WWW

- Mapa floty.
- CRM klientów.
- Firmy i limity.
- Vouchery.
- Pola firma/klient/voucher/centrum kosztów/referencja w zleceniu.
- Rozliczenia kursów i dzienne podsumowanie.
- Historia/audyt.
- Komunikacja celowana i pytania TAK/NIE.

## Backend / PostgreSQL

- `numeric_code` dla regionów.
- profile możliwości kierowców.
- `clients`, `companies`, `vouchers`, `settlements`, `shift_sessions`, `order_events`, `system_settings`.
- automatyczne tworzenie rozliczenia po zakończeniu kursu.
- naliczanie wykorzystania vouchera.
- statystyki klienta.
- raport dzienny `/api/v1/dispatch/reports/daily`.
- endpointy CRM, firm, voucherów i rozliczeń.
