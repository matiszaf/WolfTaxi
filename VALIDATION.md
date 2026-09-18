# Walidacja WolfTaxi 0.7

Wykonane przed spakowaniem:

- `node --check` dla głównych plików backendu i panelu WWW;
- kontrola wersji API 0.7.0;
- kontrola migracji pól `tracking_*` i `meter_*`;
- kontrola generowania tokenów śledzenia;
- kontrola automatycznego wygasania linku po zakończeniu/anulowaniu;
- kontrola mapowania pól taksometru do Android `Order`;
- kontrola zachowania workflow podpisu APK i versionCode 0.7;
- test integralności obu archiwów ZIP.

Finalna kompilacja APK odbywa się w GitHub Actions z Android SDK 35.
