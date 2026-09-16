# WolfTaxi 0.3 · migracja na Oracle

- całkowicie usunięto integrację Firebase z aplikacji Android,
- usunięto Google Services plugin, `google-services.json`, Firebase SDK, Functions i skrypty seedujące Firebase,
- dodano własny `OracleBackend` korzystający z REST/HTTPS,
- dodano lokalną sesję JWT,
- GPS wysyłany jest do własnego API,
- nowe zlecenie może wyświetlić lokalne powiadomienie podczas aktywnej zmiany,
- dodano Node.js/Express API,
- dodano PostgreSQL i migrację schematu,
- dodano logowanie bcrypt + JWT,
- dodano serwerową kontrolę zmian, kolejek, taryf i zleceń,
- dodano automatyczny timeout ofert,
- dodano seed `T1`, `T2`, `R1`, `S1`,
- dodano skrypt tworzenia kierowców bez zapisywania hasła w repozytorium,
- dodano instalator Oracle/Ubuntu, unit systemd i przykład Apache reverse proxy,
- `BUILD_AND_INSTALL.ps1` zachowuje konfigurację URL API zamiast ją nadpisywać.
