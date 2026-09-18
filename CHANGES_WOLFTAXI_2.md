# WolfTaxi 0.7 — TAKSOMETR + LIVE MAP + LINK KLIENTA

## Terminal kierowcy

- Ekran **REJONY** z 0.6 pozostaje bez zmian układu i wyglądu.
- W aktywnym zleceniu pojawia się sekcja **TAKSOMETR**: bieżąca kwota, dystans i czas postoju.
- `KURSEM` / rozpoczęcie etapu `in_progress` uruchamia naliczanie taksometru.
- GPS aktualizuje dystans, postój i kwotę kursu po stronie serwera.
- Przy zakończeniu kursu kwota z taksometru jest domyślną kwotą końcową.
- Przycisk **MAPA LIVE** otwiera bieżącą mapę kursu.
- Przycisk **LINK KLIENTA** kopiuje bezpieczny link śledzenia do schowka.

## Taksometr

- Naliczanie jest wykonywane na backendzie, a nie wyłącznie w UI telefonu.
- Uwzględnia opłatę początkową, cenę za kilometr, postój, minimalną opłatę i mnożnik strefy taryfowej.
- Filtruje skrajnie nierealne skoki GPS i nie dolicza ich do dystansu.
- Stan taksometru jest zapisany w PostgreSQL i przeżywa restart aplikacji.

> To funkcjonalny taksometr aplikacyjny WolfTaxi. Nie jest deklarowany jako prawnie certyfikowany przyrząd metrologiczny.

## Śledzenie klienta

- Każde przypisane/przyjęte zlecenie może mieć losowy token śledzenia (`crypto.randomBytes`, base64url).
- Publiczna strona: `/track/<token>`.
- Publiczne API śledzenia nie wymaga logowania, ale działa wyłącznie dla poprawnego tokenu konkretnego kursu.
- Klient widzi status kursu, numer taxi, pozycję na mapie, czas ostatniej aktualizacji oraz dane taksometru.
- Link nie ujawnia telefonu kierowcy ani danych innych kursów.
- Po zakończeniu/anulowaniu link wygasa automatycznie po 24 godzinach i nie może być reaktywowany po wygaśnięciu.

## Centrala

- Na liście zleceń widać bieżący stan taksometru.
- Przycisk **LINK** generuje/kopiuje link klienta.
- Istniejąca mapa floty 0.6 nadal pokazuje pozycje kierowców na żywo.

## Backend

- Nowe pola `tracking_*` i `meter_*` w `orders`.
- Publiczne endpointy śledzenia i obsługa statycznej strony klienta.
- Wersja API: `0.7.0`.
- `PUBLIC_BASE_URL=https://wolftaxi.starcore.pl` określa bazę generowanych linków.
