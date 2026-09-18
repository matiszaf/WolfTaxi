# Walidacja 0.8.3

Sprawdzone przed spakowaniem:
- `node --check` dla backendu,
- endpoint bieżącego rejonu istnieje w backendzie,
- Android Backend/OracleBackend/DemoBackend mają `setCurrentRegion`,
- `KOD + OK` używa `setCurrentRegion`,
- `KURSEM` i `DOJAZD` nadal korzystają z `setStatusForRegion`,
- panel REJONY zachowuje dotychczasowy layout,
- automatyka tracking/SMS pozostaje w paczce,
- finalny compile APK wykonuje GitHub Actions z tym samym stałym podpisem.
