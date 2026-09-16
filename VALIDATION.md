# Walidacja WolfTaxi 0.4

Wykonano lokalnie w środowisku projektu:

- `node --check` dla `src/index.js`, `src/auth.js`, `src/operator.js`, `scripts/create-user.js`, `scripts/create-driver.js` — OK.
- Kontrola składni Java przez `javac` do etapu brakujących klas Android SDK — brak błędów parsera (`expected`, `illegal start`, `unclosed`, `reached end`).
- Zweryfikowano, że workflow GitHub Actions ustawia `wolftaxi.apiUrl=https://hosting.starcore.pl/wolftaxi-api`.

Pełny build Android powinien zostać wykonany przez dołączony workflow GitHub Actions, ponieważ lokalne środowisko artefaktu nie ma Android SDK.
