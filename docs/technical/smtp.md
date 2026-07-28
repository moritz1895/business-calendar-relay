# SMTP/iMIP-Adapter (`BlockerSink`)

`SmtpBlockerSinkAdapter` (`adapters/outbound/mail/SmtpBlockerSinkAdapter.java`)
implementiert den Port `BlockerSink` (`ports/outbound/BlockerSink.java`) und
verschickt eine `BlockerMail` (`ports/outbound/BlockerMail.java`) als
iMIP-`text/calendar`-Mail per SMTP. Der ICS-Text selbst wird nicht hier,
sondern in der Domain-Schicht von `ImipCalendarRenderer`
(`core/domain/ImipCalendarRenderer.java`) erzeugt — dieser Adapter kennt nur
MIME-Struktur und Versand.

## Warum die exakte MIME-Struktur zählt

Die MIME-Form dieses Adapters ist nicht generisch gewählt, sondern
reproduziert exakt die Struktur dreier Nextcloud-generierter,
Outlook-verifizierter Referenzmails (`docs/reference/*.eml`, siehe
`CLAUDE.md`, Abschnitt „Reference findings“). Diese Referenzmails sind die
Abnahmebasis: Abweichungen von dieser Struktur haben in der Vergangenheit
dazu geführt, dass Outlook die Einladung als Dateianhang statt als
Kalenderkarte rendert.

## MIME-Struktur

```
multipart/mixed
 ├─ multipart/alternative
 │   ├─ text/plain
 │   └─ text/html
 └─ text/calendar   (method=REQUEST|CANCEL)
```

Der `text/calendar`-Teil ist ein **Geschwisterknoten** von
`multipart/alternative`, nicht darin verschachtelt. Aufgebaut wird das in
`send(BlockerMail)`:

```java
var mixed = new MimeMultipart("mixed");
mixed.addBodyPart(alternativeBodyPart());
mixed.addBodyPart(calendarBodyPart(mail));
message.setContent(mixed);
```

`alternativeBodyPart()` liefert eine feste, nicht aus dem Quell-Event
abgeleitete `text/plain`- und `text/html`-Variante (deutschsprachiger
Platzhaltertext: „Diese Nachricht enthaelt eine Kalender-Einladung.“) — der
Blocker selbst ist titellos, entsprechend enthält auch die Mail keinen
inhaltlichen Bezug zum Quell-Event.

## Der `text/calendar`-Teil im Detail

```java
var contentType = "text/calendar; method=" + mail.method() + "; charset=\"utf-8\"; name=" + ICS_FILE_NAME;
...
calendarPart.setHeader("Content-Type", contentType);
calendarPart.setHeader("Content-Transfer-Encoding", "base64");
calendarPart.setHeader("Content-Disposition", "inline; name=" + ICS_FILE_NAME + "; filename=" + ICS_FILE_NAME);
```

Drei Aspekte sind hier bewusst exakt festgelegt, per Referenzmail-Analyse:

- `Content-Type: text/calendar; method=REQUEST|CANCEL; charset="utf-8";
  name=event.ics` — der `method`-Parameter muss mit der `METHOD:`-Zeile im
  ICS-Text selbst übereinstimmen (siehe unten).
- `Content-Transfer-Encoding: base64`.
- `Content-Disposition: inline; name=event.ics; filename=event.ics` — **genau
  diese** Kombination (inline **und** beide Namen gesetzt) ist es, die
  Outlook dazu bringt, eine Einladungskarte statt eines Dateianhangs zu
  rendern. Fehlt `filename=`, oder steht `Content-Disposition: attachment`,
  bricht das Verhalten.

Der Dateiname `event.ics` ist als Konstante `ICS_FILE_NAME` fest verdrahtet.

## Wie `method=REQUEST|CANCEL` in beide Stellen propagiert

`BlockerMail.method()` ist ein `BlockerMailMethod`-Enum
(`ports/outbound/BlockerMailMethod.java`, Werte `REQUEST`/`CANCEL`), das die
Anwendungsschicht (`PollAndRelaySourceCalendarService`) explizit setzt — und
zwar konsistent mit dem ICS-Text, den `ImipCalendarRenderer` für denselben
Vorgang gerendert hat:

- `renderer.renderRequest(...)` erzeugt einen ICS-Text mit `METHOD:REQUEST`
  → `BlockerMail` wird mit `BlockerMailMethod.REQUEST` gebaut.
- `renderer.renderCancel(...)` erzeugt einen ICS-Text mit `METHOD:CANCEL` →
  `BlockerMail` wird mit `BlockerMailMethod.CANCEL` gebaut.

Der Adapter selbst entscheidet nicht, welche Methode gilt — er liest
`mail.method()` und setzt denselben Wert wörtlich in den
`Content-Type`-Parameter `method=...` ein. Die Konsistenz zwischen VCALENDAR-
`METHOD:`-Zeile und MIME-`method=`-Parameter ist damit strukturell durch den
Aufrufer garantiert, nicht durch den Adapter selbst geprüft.

## `ImipCalendarRenderer`: Aufbau des ICS-Texts

Kurz zusammengefasst (Domain-Service, `core/domain/ImipCalendarRenderer.java`),
da der ICS-Text die Grundlage für den `text/calendar`-Teil ist:

- `SUMMARY` ist immer das feste Literal `"Privater Blocker"` — nie aus dem
  Quell-Event abgeleitet (titelloser Blocker per Domain-Vorgabe).
- Jede Zeile wird nach RFC 5545 bei 75 Oktetts gefaltet (`fold(...)`,
  UTF-8-oktettgenau, respektiert Codepoint-Grenzen).
- Ein vollständiger `VTIMEZONE`-Block für `Europe/Berlin` (inkl. `DAYLIGHT`/
  `STANDARD`-Übergängen) wird in jede Nachricht eingebettet — Zeiten werden
  über `DTSTART;TZID=...`/`DTEND;TZID=...` ausgedrückt, nicht als reines
  `Z`/UTC, weil die Referenzmails das ebenfalls nie tun.
- `DTSTAMP` wird aus einem vom Aufrufer übergebenen `Instant` gerendert
  (`generatedAt`), nicht aus einer intern gelesenen Uhr — das hält den
  Renderer deterministisch testbar.
- Bei `renderCancel(...)` bleibt `STATUS:CONFIRMED` auf dem `VEVENT` erhalten
  — die Stornosemantik liegt ausschließlich in `METHOD:CANCEL` auf
  VCALENDAR-Ebene. Die `ATTENDEE`-Zeile verliert dabei `PARTSTAT`/`ROLE`/
  `RSVP` und behält nur `CUTYPE`/`mailto:`.
- `SEQUENCE` wird vom Aufrufer (`PollAndRelaySourceCalendarService`, über
  `RelayDiffPlanner`) monoton steigend vorgegeben, nie im Renderer selbst
  berechnet.

## `From`/`Reply-To`/Envelope-From

```java
message.setFrom(new InternetAddress(mail.fromAddress()));
message.setReplyTo(new InternetAddress[] {new InternetAddress(mail.replyToAddress())});
```

`From` wird genau einmal auf der Nachricht gesetzt, und es wird **nie** ein
`Sender`-Header gesetzt — dadurch entspricht das SMTP-Envelope-From, das der
Transport verwendet, exakt dem `From`-Header. Das hält SPF grün (siehe
`CLAUDE.md`: „`From`/envelope-from match exactly“). `Reply-To` wird separat
auf die menschliche Organizer-Adresse gesetzt, damit eine Antwort auf die
Blocker-Mail beim Menschen landet, nicht beim technischen Absender.

Beide Adressen kommen pro Kalender aus `relay.calendars[].from-address` bzw.
`relay.calendars[].reply-to-address` (siehe `RelayProperties.CalendarConfig`).

## Spring-Mail-Konfiguration

`application.yml`:

```yaml
spring:
  mail:
    host: ${SMTP_HOST}
    port: ${SMTP_PORT:587}
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
```

`SmtpBlockerSinkAdapter` bekommt einen fertig konfigurierten
`JavaMailSender` (Spring-Boot-Autokonfiguration aus
`spring-boot-starter-mail`, gesteuert über obige `spring.mail.*`-Properties)
injiziert — anders als `CalDavCalendarSourceAdapter` und
`JpaStateStoreAdapter` ist dieser Adapter **kein** per-Kalender-konstruiertes
Objekt: er hat keinen kalenderspezifischen Konstruktorzustand (Zieladresse,
Absender etc. kommen pro Aufruf über `BlockerMail` herein) und wird daher
ganz regulär als einziger, gemeinsam genutzter Spring-Singleton-Bean über
`@InfrastructureServiceAdapter`/`@ArchComponentScan` registriert.

## Fehlerbehandlung

Jede `MessagingException` oder `MailException` beim Aufbau oder Versand wird
in eine `BlockerSinkException` (`ports/outbound/BlockerSinkException.java`)
übersetzt. `PollAndRelaySourceCalendarService` fängt diese pro Quell-Event
ab: ein fehlgeschlagener Versand bricht nicht den gesamten Poll-Zyklus ab,
sondern wird als `RelayFailure` im `RelayCycleResult` gesammelt und vom
Scheduler als WARN geloggt (siehe `scheduling.md`).
