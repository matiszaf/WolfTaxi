# WolfTaxi 0.6 FULL RT3000

Duży release integracyjny WolfTaxi. Łączy terminal kierowcy, centralę WWW, dyspozytornię w aplikacji, kolejki, priorytety, zlecenia, giełdę, nakazy, komunikację, SOS, CRM i rozliczenia na jednym backendzie Oracle/PostgreSQL.

## Najważniejsze

- ekran **REJONY** terminala kierowcy został zachowany bez przebudowy układu;
- stały podpis release APK pozostaje obsługiwany przez GitHub Secrets;
- kody rejonów są teraz jawnie przechowywane w bazie (`numeric_code`);
- seed ustawia: 21 Witomino, 23 Karwiny, 24 Wiczlino, 26 Dąbrowa, 37 Centrum, 39 Chylonia, 87 Trójmiasto, 89 Dom, 1 Region 1;
- kolejki i pozycje są liczone na żywo, z priorytetem kierowcy/kolejki;
- automatyczny przydział filtruje wymagania: karta, zwierzę, bagaż, angielski;
- zlecenia: automat/kolejka, giełda, nakaz, planowane, wymagania, ryzyko/mina;
- pełny przebieg kursu: oferta → przyjęcie → dojazd → na miejscu → kursem → zakończenie;
- przy zakończeniu kierowca podaje kwotę końcową i formę płatności;
- wiadomości, potwierdzenia odczytu, pytania TAK/NIE, TTS;
- SOS z lokalizacją i obsługą przez centralę;
- mapa floty na żywo w panelu WWW;
- klienci/CRM, firmy, vouchery, bezgotówka i centra kosztów;
- automatyczne rozliczenie zakończonego kursu;
- dzienne sumy kierowcy i centrali;
- raport dzienny API i historia/audyt;
- taryfy, strefy, regiony i konta administracyjne;
- WebSocket + awaryjny polling.

## Aktualizacja Oracle

Rozpakuj `WolfTaxi_0.6_SERVER_ONLY.zip` i uruchom:

```bash
chmod +x UPDATE_ORACLE_UBUNTU.sh scripts/*.sh
./UPDATE_ORACLE_UBUNTU.sh
```

Po aktualizacji:

```bash
curl https://wolftaxi.starcore.pl/health
```

Powinno zwrócić `"version":"0.6.0"`.

## APK

Workflow `.github/workflows/android.yml` buduje podpisany `assembleRelease`, sprawdza SHA-256 certyfikatu i publikuje artifact `WolfTaxi-APK`.

Wymagane sekrety GitHub pozostają te same:

- `WOLFTAXI_KEYSTORE_B64`
- `WOLFTAXI_KEYSTORE_PASSWORD`
- `WOLFTAXI_KEY_ALIAS`
- `WOLFTAXI_KEY_PASSWORD`
- `WOLFTAXI_CERT_SHA256`

## Panel centrali

`https://wolftaxi.starcore.pl/dispatch/`

Zakładki obejmują dyspozytornię, zlecenia, kierowców, komunikację, klientów/firmy, rozliczenia, historię oraz administrację.

> WolfTaxi odwzorowuje workflow, które zostały zdefiniowane dla tego projektu. Nie zakłada nieudokumentowanych zachowań zamkniętego systemu RT3000.
