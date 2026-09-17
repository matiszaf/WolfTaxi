# Walidacja WolfTaxi 0.5 RT3000 Core

Sprawdzono w środowisku artefaktu:

- składnię Node.js dla głównych plików backendu (`node --check`),
- obecność migracji PostgreSQL dla funkcji 0.5,
- konfigurację WebSocket `/ws`,
- konfigurację przykładowego vhosta `wolftaxi.starcore.pl` z `proxy_wstunnel`,
- workflow GitHub Actions z `wolftaxi.apiUrl=https://wolftaxi.starcore.pl`,
- wersję Android `0.5.0-rt3000-core` / versionCode 5.

Pełny build APK należy wykonać przez dołączony GitHub Actions lub lokalne Android SDK.
