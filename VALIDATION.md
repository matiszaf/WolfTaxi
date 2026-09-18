# Walidacja 0.6

Sprawdzone przed spakowaniem:

- `npm run check` — wszystkie pliki JS backendu przechodzą `node --check`;
- `server/public/dispatch/app.js` przechodzi `node --check`;
- workflow GitHub Actions zachowuje stabilny podpis release i parser SHA-256;
- wersja API: `0.6.0`;
- wersja APK: automatycznie `0.6.<GITHUB_RUN_NUMBER>`, versionCode `600000 + run_number`;
- ekran REJONY porównany z 0.5.4: układ i przyciski bez zmian; jedyna zmiana logiczna w tym bloku to użycie `numericCode` z serwera, jeśli istnieje;
- seed zawiera kody 21, 23, 24, 26, 37, 39, 87, 89 i 1.

Pełny test instalacyjny Androida wykonuje GitHub Actions po pushu, ponieważ środowisko paczki nie zawiera lokalnego Android SDK.
