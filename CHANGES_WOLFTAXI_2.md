# Zmiany WolfTaxi 0.4.0

## Architektura
- Jedna aplikacja Android dla driver / dispatcher / admin.
- Wspólny login i JWT z tablicą ról.
- Nowa tabela `users`; `drivers` pozostaje profilem kierowcy.
- Migracja istniejących kont kierowców bez kasowania danych.

## Kierowca
- Zachowana logika zmian, statusów, regionów, kolejek, taryf, GPS i kursów.

## Dyspozytor
- Podgląd wszystkich taxi, statusów, kolejek i GPS.
- Tworzenie zleceń.
- Automatyczne oferowanie pierwszemu taxi w kolejce.
- Ręczne przypisanie taxi.
- Anulowanie zleceń.
- Komunikaty centrali.

## Administrator
- Tworzenie kont z wieloma rolami.
- Blokowanie/odblokowywanie kont.
- Endpointy do zmiany ról i resetu haseł.
- Dodawanie/edycja taryf, regionów i stref.
- Audit log.

## WWW
- Panel `/dispatch/`.
- Responsywny widok desktop/mobile.
- Mapa taxi z OpenStreetMap/Leaflet.
- Zlecenia, kierowcy, komunikaty i administracja.
