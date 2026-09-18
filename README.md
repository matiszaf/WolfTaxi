# WolfTaxi 0.8 — prywatna bramka SMS w tej samej aplikacji

WolfTaxi 0.8 rozwija 0.7 (taksometr + mapa LIVE + publiczny link śledzenia) o darmową bramkę SMS działającą z prywatnego APK na Androidzie.

## Najważniejsze

- jedna aplikacja i jeden podpis APK,
- nowa rola `sms_gateway`,
- osobny tryb **BRAMKA SMS** po zalogowaniu — bez dodawania zakładki do terminala kierowcy,
- ekran `REJONY` kierowcy pozostaje bez zmian,
- Android wysyła SMS przez kartę SIM telefonu (`SEND_SMS`),
- backend ma kolejkę `sms_outbox`, leasing zadania i maks. 3 próby,
- po przyjęciu/przypisaniu/nakazie/pobraniu z giełdy zlecenia system automatycznie kolejkuje SMS z linkiem śledzenia, jeśli zlecenie ma poprawny numer klienta,
- polski 9-cyfrowy numer jest normalizowany do `+48...`,
- centrala widzi `queued / sent / failed / skipped` przy zleceniu,
- lokalny znacznik w telefonie ogranicza ryzyko duplikatu po zerwaniu sieci między wysłaniem SMS a raportem do Oracle.

## Urządzenie-bramka

Najlepiej użyć jednego telefonu Android z kartą SIM i pakietem SMS. Konto powinno mieć rolę `sms_gateway`. Po zalogowaniu wybierz **BRAMKA SMS**, nadaj aplikacji zgodę na SMS i zostaw bramkę włączoną. Działa jako foreground service z trwałym powiadomieniem.

W telefonie z dwiema kartami SIM używana jest domyślna karta SMS ustawiona w Androidzie.

## Automatyczna wiadomość

Po przypisaniu kursu system wysyła tekst w stylu:

`WolfTaxi: Twoja taksówka jest w drodze. Śledź kurs na żywo: https://wolftaxi.starcore.pl/track/...`

Jeśli podano imię klienta, jest ono używane w wiadomości.

## Konto bramki

Po wdrożeniu 0.8 zaloguj się jako administrator i utwórz konto z rolą **BRAMKA SMS**. Nie trzeba przypisywać mu profilu kierowcy ani numeru taxi.

## Bezpieczeństwo

Endpointy kolejki SMS wymagają zalogowanego JWT z rolą `sms_gateway`. Telefon nie przyjmuje dowolnej treści z internetu — pobiera tylko wiadomości przygotowane przez backend WolfTaxi.
