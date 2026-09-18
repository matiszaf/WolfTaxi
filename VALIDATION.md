# Walidacja WolfTaxi 0.9

Sprawdzone przed spakowaniem:
- składnia Java: brak błędów parsera w źródłach aplikacji; pełny compile Android wykonuje GitHub Actions,
- `node --check` dla plików backendu JS,
- `bash -n` dla skryptów instalacyjnych i pomocniczych,
- zgodność funkcji aplikacji z akcjami panelu WEB: zlecenia, kierowcy, komunikacja, CRM, rozliczenia, audyt, admin i mapa,
- OperatorSnapshot mapuje kierowców, zlecenia, regiony, taryfy, strefy, komunikaty, użytkowników, SOS, klientów, firmy, vouchery, rozliczenia i audyt,
- zachowany applicationId `pl.wolftaxi.app`,
- workflow nadal buduje podpisany release APK ze stałym certyfikatem,
- ekran kierowcy REJONY nie został przebudowany.

Finalny test kompilacji APK jest wykonywany przez GitHub Actions z Android SDK 35.
