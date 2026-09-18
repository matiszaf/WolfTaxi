# WolfTaxi 0.8 — zmiany

- dodano rolę `sms_gateway` do autoryzacji i panelu admina,
- dodano tryb BRAMKA SMS w tym samym APK,
- dodano `SEND_SMS` oraz foreground service `SmsGatewayService`,
- dodano kolejkę PostgreSQL `sms_outbox`, leasing i raportowanie wyników,
- automatycznie kolejkuje SMS tracking po przyjęciu/przypisaniu/nakazie/pobraniu z giełdy,
- status SMS jest widoczny przy zleceniu w centrali,
- dodano telefon i nazwę klienta do formularza zlecenia w aplikacji operatora,
- zachowano ekran REJONY bez zmian funkcjonalno-wizualnych,
- wersja backendu: 0.8.0.
