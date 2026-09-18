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


## 0.8.4 checks
- tryb SMS -> Kierowca nie wywołuje stopSmsGateway
- ręczne WYŁĄCZ ustawia enabled=false i zatrzymuje usługę
- logout zatrzymuje usługę
- BootReceiver wymaga enabled + aktywnej sesji + roli sms_gateway + SEND_SMS
- usługa ma START_STICKY oraz stopWithTask=false
