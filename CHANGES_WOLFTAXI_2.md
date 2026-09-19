# WolfTaxi 0.9.2 — POSTÓJ

- Przycisk POSTÓJ jest zawsze widoczny podczas aktywnego kursu w zakładce ZLEC.
- Po aktywacji zmienia się na WZNÓW JAZDĘ i pokazuje minuty zgłoszonego postoju.
- Przycisk jest niezależny od warunku renderowania bloku taksometru, więc nie znika przy odświeżeniu snapshotu.
- Końcowa kwota sugerowana w oknie zakończenia kursu jest pokazywana w pełnych złotych.
- Ekran REJONY bez zmian.

## 0.9.3 — crash przy ZAKOŃCZ KURS
- Naprawiono runtime crash przy otwieraniu dialogu zakończenia kursu.
- Przyczyną było użycie formatu `%.0f` z wartością typu `long` zwracaną przez `Math.round`, co powodowało `IllegalFormatConversionException`.
- Kwota sugerowana jest teraz wpisywana jako pełna liczba przez `String.valueOf(Math.round(...))`.
- Backend i logika taksometru pozostają bez zmian.
