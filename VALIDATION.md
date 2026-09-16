# Walidacja WolfTaxi Oracle Edition

W paczce wykonano kontrole statyczne:

- brak zależności i importów Firebase w module Android,
- `node --check` dla kodu backendu i skryptów,
- walidacja JSON `package.json`,
- kontrola kompletności endpointów używanych przez Androida,
- kontrola manifestu Android po usunięciu usługi FCM.

Pełny `gradlew installDebug` należy wykonać na komputerze z Android SDK, a migrację PostgreSQL na docelowym serwerze Oracle.
