# Feature: Google Calendar als zweiter, koexistierender Quellkalender-Typ

Kein GitHub-Issue: Diese Spec setzt einen direkten Nutzerauftrag um, keine
zuvor dokumentierte Roadmap-Position. Es gibt kein `CLAUDE.md`-Zitat, das
dieses Feature bereits als "deferred" vormerkt — `business-calendar-relay`
war bislang exklusiv auf CalDAV (heute produktiv: Nextcloud) als
Quellkalender-Protokoll ausgelegt. Diese Spec ist die primäre Quelle des
technischen Entwurfs für Google Calendar als **zweiten, gleichberechtigt
koexistierenden** Quellkalender-Typ, nicht die Verschriftlichung eines
längeren Gesprächs.

## Nicht verhandelbare Anforderung: Null funktionale Beeinträchtigung des etablierten CalDAV-Pfads

Der Nutzer war hier explizit: *"alles so dass das etablierte mit Nextcloud
und CalDAV nicht funktional behindert wird. Am Ende brauchen wir beide
zusammen."* Das ist keine Präferenz, sondern eine harte Randbedingung, die
jede Design-Entscheidung dieser Spec durchzieht:

- **Koexistenz, nicht Migration.** Eine einzige `relay.calendars[]`-Liste
  muss gleichzeitig einen Nextcloud/CalDAV-Eintrag und einen
  Google-Calendar-Eintrag enthalten können, beide unabhängig voneinander
  gepollt, beide relayend in dieselbe oder unterschiedliche dienstliche
  Postfächer — exakt wie heute schon mehrere CalDAV-Kalender koexistieren
  (`personal-nextcloud` + ein weiterer Eintrag), nur jetzt mit
  unterschiedlichen Quellprotokollen im selben Deployment.
- **Zero-Config-Migration für bestehende Deployments.** Eine bereits
  produktive `relay.calendars[]`-Konfiguration ohne jedes neue Feld (kein
  `type`-Diskriminator) muss nach dem Upgrade auf diese Feature
  **unverändert und ohne jede manuelle Config-Anpassung** weiterlaufen — das
  neue Diskriminator-Feld muss auf das heutige CalDAV-Verhalten defaulten,
  wenn es fehlt.
- **Kein gemeinsamer Code-Pfad, der CalDAV-Verhalten verändert.** Jede
  Google-spezifische Ergänzung (neuer Port, neue Konfigurationsfelder, neue
  Adapter-Klasse) muss additiv sein. Wo immer diese Spec zwischen "bestehenden
  Code anfassen" und "neuen, parallelen Code hinzufügen" wählen kann, wird
  explizit die additive Option gewählt und begründet — siehe insbesondere
  die Port-Entscheidung unten.

## Recherche-Stand — bereits verifiziert, hier als gesetzt behandelt

Die folgenden Punkte wurden vom Auftraggeber bereits gegen Googles aktuelle,
autoritative Dokumentation verifiziert und werden hier zitiert, nicht erneut
hergeleitet:

- **Googles CalDAV-Endpunkt
  (`https://apidata.googleusercontent.com/caldav/v2/<calendar-id>/events`)
  existiert weiterhin und unterstützt RFC-6578-`sync-collection`**, verweigert
  aber seit dem 14. März 2025 kategorisch HTTP Basic Auth — OAuth 2.0 ist
  zwingend. Quelle: `developers.google.com/workspace/calendar/caldav/v2/guide`.
- **Entscheidung bereits getroffen: Google Calendar REST API v3 direkt, nicht
  CalDAV-über-OAuth.** OAuth muss ohnehin gebaut werden; CalDAV gewinnt
  dadurch nichts zusätzlich, während die REST API `syncToken`-basierte
  inkrementelle Synchronisation nativ in `events.list` mitbringt — ein
  direktes Analogon zum bereits bestehenden `sync-collection`-Delta-Sync
  dieses Projekts (`docs/features/delta-sync.md`) — und der aktiv gepflegte
  Primärweg von Google selbst ist. Home Assistants offizielle
  Google-Calendar-Integration (`home-assistant.io/integrations/google`)
  bestätigt das als Standardweg: REST API über die Google Developers
  Console (`Google Calendar API` aktivieren, OAuth-Credentials anlegen),
  nicht CalDAV.
- **Für ein persönliches (Nicht-Workspace-)Gmail-Konto** gilt laut Googles
  Dokumentation und Home Assistants eigenen Troubleshooting-Docs (dort
  wörtlich: *"Under Publishing status > Testing, select Publish app.
  Otherwise, your credentials will expire every 7 days."*): Eine OAuth-App
  vom Typ "External", die im Publishing-Status "Testing" verbleibt, erhält
  einen Refresh-Token, der nach 7 Tagen abläuft (zusätzlich ein Limit von 100
  Test-Nutzern). Das Umschalten des Publishing-Status auf **"In production"**
  (ein einzelner, kostenloser Button in der Google Cloud Console, **keine**
  formale Google-Verifizierungsprüfung erforderlich) entfernt sowohl die
  7-Tage-Ablauffrist als auch das Nutzerlimit; Refresh-Tokens leben danach
  unbegrenzt (bis Widerruf oder ca. 6 Monate Inaktivität). Der Preis dafür
  ist ein einmaliger "Google hat diese App nicht verifiziert"-Warnbildschirm
  beim allerersten Consent, weggeklickt über
  Advanced → "Go to [app] (unsafe)". Diese Spec dokumentiert das explizit als
  erwarteten/erforderlichen Einrichtungsschritt für jeden, der einen
  Google-Kalender konfiguriert (ein Google-Cloud-Projekt + OAuth-Client, den
  der **Deployer** selbst anlegt und besitzt — exakt wie bei Home Assistants
  eigenem Setup).

## Feature-Zusammenfassung

`RelayProperties.CalendarConfig` bekommt ein neues, optionales
Diskriminator-Feld `type` (`caldav` | `google`, Default `caldav`). Ein
Eintrag mit `type: google` trägt einen eigenen Satz Google-spezifischer
Felder (Kalender-ID, OAuth-Client-Credentials, ein einmalig beschaffter,
langlebiger Refresh-Token) statt der CalDAV-Felder. `RelayWiringConfiguration`
verzweigt beim Bauen jeder Use-Case-Instanz auf Basis von `calendar.type()`
und konstruiert entweder die bestehende, unveränderte
`CalDavCalendarSourceAdapter` oder eine neue `GoogleCalendarSourceAdapter` —
**beide implementieren weiterhin unverändert denselben `CalendarSource`-Port**.
Der neue Adapter liest Termine über die Google Calendar REST API v3
(`events.list`), nutzt `singleEvents=true` für serverseitig bereits
expandierte Wiederholungs-Vorkommen (kein clientseitiges RRULE-Rebuilding
für Googles JSON-Form nötig) und `syncToken`-basierte inkrementelle
Synchronisation nach demselben architektonischen Muster wie
`docs/features/delta-sync.md`s `CalendarReplicaStore` — allerdings über
einen **eigenen, dedizierten Port** statt einer Wiederverwendung von
`CalendarReplicaStore` selbst (Begründung siehe Design-Entscheidung 4 unten).

**Diese Feature ändert keine einzige Zeile in `core/domain` oder
`core/app`.** `CalendarSource.readEvents(): List<SourceEvent>` bleibt
unverändert der einzige Vertrag, den `PollAndRelaySourceCalendarService`
kennt — welcher konkrete Adapter (CalDAV oder Google) diesen Vertrag erfüllt,
ist für die Anwendungsschicht ununterscheidbar, exakt wie schon
`docs/features/delta-sync.md`s Design-Kernentscheidung für den CalDAV-Adapter
selbst. Das ist genau der Punkt, an dem sich zeigt, dass der Port bereits
heute implementierungsagnostisch geschnitten ist — diese Feature bestätigt
das, statt es zu erzwingen.

## Akteure

Unverändert gegenüber `relay-orchestration.md` und jeder nachfolgenden
Feature-Spec: **Scheduler** ist der einzige Akteur, der einen Poll-Zyklus
anstößt. Ein neuer, einmaliger, außerhalb der laufenden Anwendung
stattfindender Akteur kommt hinzu: der **Deployer**, der pro
Google-Quellkalender einmalig den OAuth-Consent-Flow durchläuft, um den
initialen Refresh-Token zu beschaffen (siehe Design-Entscheidung 2).

## Design-Entscheidung 1: Konfigurationsschema für Koexistenz

### Heutiger Stand (`RelayProperties.CalendarConfig`)

Jedes Feld von `CalendarConfig` ist heute Pflicht (`@NotBlank`) und entweder
CalDAV-spezifisch (`caldavUrl`, `caldavUsername`, `caldavPassword`) oder
geteilt (`organizerEmail`, `attendeeEmail`, `fromAddress`, `replyToAddress`,
`deltaSyncEnabled`). Es gibt kein Diskriminator-Feld, weil es bislang nur
einen Quellkalender-Typ gibt.

### Neues Schema

```java
public record CalendarConfig(
        @NotBlank String id,
        @NotNull @DefaultValue("caldav") CalendarSourceType type,

        // CalDAV-spezifisch -- Pflicht nur wenn type == CALDAV, siehe
        // @ConsistentCalendarSourceFields unten
        String caldavUrl,
        String caldavUsername,
        String caldavPassword,

        // Google-spezifisch -- Pflicht nur wenn type == GOOGLE
        String googleCalendarId,
        String googleClientId,
        String googleClientSecret,
        String googleRefreshToken,

        // geteilt, für beide Typen weiterhin Pflicht
        @NotBlank String organizerEmail,
        @NotBlank String attendeeEmail,
        @NotBlank String fromAddress,
        @NotBlank String replyToAddress,

        // geteilt, semantisch pro Typ leicht unterschiedlich (siehe unten)
        @DefaultValue("true") boolean deltaSyncEnabled) {

    public enum CalendarSourceType {
        CALDAV, GOOGLE
    }
}
```

Kernpunkte:

- **`type` defaultet auf `CALDAV`.** Spring Boots relaxed Binding bindet
  einen fehlenden `type`-Schlüssel automatisch auf den
  `@DefaultValue("caldav")`-String, konvertiert zu
  `CalendarSourceType.CALDAV` (Enum-Binding ist bereits nativ
  case-insensitiv). **Das ist der gesamte Mechanismus, der die
  Zero-Config-Migrationsanforderung erfüllt** — eine bestehende
  `relay.calendars[]`-Konfiguration ohne `type`-Zeile bindet exakt wie heute,
  ohne dass ein Bit an der bestehenden Datei geändert werden muss.
- **CalDAV- und Google-spezifische Felder verlieren ihre
  `@NotBlank`-Annotation** und werden stattdessen über einen neuen,
  klassen-level Bean-Validation-Constraint bedingt geprüft (siehe unten) —
  ein einzelnes `@NotBlank` auf Record-Ebene kann nicht ausdrücken "Pflicht
  nur, wenn `type == X`".
- **`deltaSyncEnabled`** bleibt ein einziges, geteiltes Feld für beide Typen
  (kein `caldavDeltaSyncEnabled`/`googleDeltaSyncEnabled`-Paar) — die
  Semantik "Notausschalter für den Delta-Sync-Mechanismus dieses einzelnen
  Kalender-Eintrags, fällt bei `false` auf eine stets vollständige Abfrage
  zurück" ist für beide Typen identisch, nur der konkrete Fallback-Mechanismus
  unterscheidet sich adapter-intern (siehe Design-Entscheidung 3).

### Bedingte Pflichtfelder: `@ConsistentCalendarSourceFields`

Neue, klassen-level Bean-Validation-Annotation, analog zum bereits
etablierten Muster geteilter, aber typspezifisch unterschiedlicher
Validierungsanforderungen in diesem Projekt:

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ConsistentCalendarSourceFieldsValidator.class)
public @interface ConsistentCalendarSourceFields {
    String message() default "calendar config fields inconsistent with its type";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

`ConsistentCalendarSourceFieldsValidator implements
ConstraintValidator<ConsistentCalendarSourceFields, CalendarConfig>` prüft:

- `type == CALDAV` → `caldavUrl`, `caldavUsername`, `caldavPassword` dürfen
  nicht blank sein.
- `type == GOOGLE` → `googleCalendarId`, `googleClientId`,
  `googleClientSecret`, `googleRefreshToken` dürfen nicht blank sein.

**Bewusst ein klassen-level Constraint statt einer manuellen Prüfung im
kompakten Konstruktor**, obwohl `RelayProperties`s eigener kompakter
Konstruktor bereits an anderer Stelle (Default-Werte für `calendars`/
`initialization`) manuell prüft: Ein im kompakten Konstruktor geworfener
Fehler bricht die Objekterzeugung beim **ersten** Verstoß ab, bevor Spring
die übrige Bean-Validation-Baumprüfung (`@NotBlank` auf
`organizerEmail` etc., rekursiv über `@Valid List<CalendarConfig>`) überhaupt
durchführen kann — der Nutzer sähe bei mehreren gleichzeitigen
Konfigurationsfehlern nur den ersten, nicht den vollständigen Bericht. Ein
Bean-Validation-Constraint reiht sich dagegen nahtlos in Springs
`@Validated`-Mechanismus ein und erscheint zusammen mit jeder anderen
Verletzung in einem einzigen, vollständigen
`ConstraintViolationException`-Bericht beim Anwendungsstart — konsistent mit
der bestehenden Fail-Fast-Philosophie dieses Projekts für Konfigurationsfehler.

`CalendarConfig` bekommt die Annotation auf Klassenebene:

```java
@ConsistentCalendarSourceFields
public record CalendarConfig(...) { ... }
```

### `buildUseCase`-Verzweigung

`RelayWiringConfiguration.buildUseCase(...)` verzweigt vor dem bisherigen
`CalDavCalendarSourceAdapter`-Konstruktoraufruf:

```java
CalendarSource calendarSource = switch (calendar.type()) {
    case CALDAV -> buildCalDavCalendarSource(calendar, httpClient, clock, recurringEventHorizon, ...);
    case GOOGLE -> buildGoogleCalendarSource(calendar, httpClient, clock, recurringEventHorizon, ...);
};
```

Jede der beiden `build*CalendarSource`-Hilfsmethoden konstruiert exakt die
Instanz, die heute (CalDAV-Zweig) bzw. neu (Google-Zweig, siehe
Port-Änderungen unten) für diesen Kalender-Typ benötigt wird — der Rest von
`buildUseCase(...)` (`stateStore`, `pendingCreationQueue`,
`PollAndRelaySourceCalendarService`-Konstruktion) bleibt **vollständig
typ-agnostisch und unverändert**, weil er ausschließlich gegen den
`CalendarSource`-Port programmiert, nie gegen eine konkrete Implementierung.

### Bestätigung: keine Änderung an `core/domain`/`core/app`

Explizit verifiziert (nicht nur vermutet): `PollAndRelaySourceCalendarService`
hält eine Referenz vom Typ `CalendarSource`, ruft ausschließlich
`readEvents()` auf und verzweigt nirgends auf die konkrete Implementierung.
`RelayDiffPlanner`, `SourceEvent`, `RelayState`, `RelayAction` kennen keinen
Adapter, kein Protokoll, keinen Kalender-Typ. Diese Feature fügt in
`core/domain`/`core/app` **buchstäblich keine einzige Zeile** hinzu — der
gesamte fachliche Effekt liegt vollständig hinter dem bereits bestehenden
`CalendarSource`-Vertrag verborgen, exakt wie schon bei `delta-sync.md`
für den CalDAV-Adapter demonstriert.

## Design-Entscheidung 2: OAuth-Token-Lebenszyklus

Dieser Service ist ein **headless Hintergrund-Poller ohne eingehende
HTTP-Endpunkte, ohne Web-UI** (`CLAUDE.md`: *"No Outlook-side API, no Graph
integration — plain SMTP"*). Es existiert heute kein Mechanismus für einen
interaktiven Consent-Flow, und diese Feature führt bewusst keinen ein — das
wäre eine massive architektonische Erweiterung für ein Bedürfnis, das nur
einmal pro Google-Kalender-Konfiguration auftritt.

### Entscheidung: initialer Consent ist ein einmaliger, manueller Schritt außerhalb der laufenden Anwendung

Der **Deployer** beschafft den ersten Refresh-Token selbst, außerhalb der
laufenden Anwendung, per Google OAuth 2.0 Playground
(`developers.google.com/oauthplayground`):

1. In einem selbst angelegten Google-Cloud-Projekt (siehe Recherche-Stand
   oben) die **Google Calendar API** aktivieren und einen OAuth-2.0-Client
   (Typ "Desktop app" oder "Web application" mit `https://developers.google.com/oauthplayground`
   als registriertem Redirect-URI) anlegen.
2. Im OAuth-Consent-Screen des Projekts den Publishing-Status auf
   **"In production"** setzen (siehe Recherche-Stand — vermeidet die
   7-Tage-Ablauffrist).
3. Im OAuth-Playground über das Zahnrad-Icon "Use your own OAuth
   credentials" aktivieren und Client-ID/-Secret des eigenen Clients
   eintragen.
4. Scope `https://www.googleapis.com/auth/calendar.readonly` auswählen (der
   Scope für **lesenden** Zugriff — bewusst nicht `.../auth/calendar`, siehe
   "Weitere Entscheidungen" unten für die Begründung des minimalen Scopes),
   Schritt 1 ("Authorize APIs") durchlaufen, dabei den einmaligen
   "Google hat diese App nicht verifiziert"-Warnbildschirm über
   Advanced → "Go to [App] (unsafe)" bestätigen.
5. Schritt 2 ("Exchange authorization code for tokens") durchlaufen — die
   Playground-Antwort enthält den `refresh_token`, der ab hier einmalig in
   die Konfiguration (`GOOGLE_..._REFRESH_TOKEN`) übernommen wird.

Dieser Ablauf wird beim Implementieren als kurzes, eigenständiges
Setup-Dokument (`docs/technical/google-calendar-setup.md`, analog zu
`docs/technical/caldav.md`/`docs/technical/smtp.md`) ausformuliert — diese
Spec beschreibt den Mechanismus, nicht die fertige Bedienungsanleitung.

### Laufzeitverhalten: ausschließlich Access-Token-Erneuerung, vollständig autonom

Die laufende Anwendung hat zur Laufzeit **eine einzige** OAuth-Aufgabe:
den gespeicherten, statischen Refresh-Token gegen einen kurzlebigen
Access-Token tauschen, bevor ein `events.list`-Aufruf stattfindet — ohne
jede Nutzerinteraktion, passt nahtlos in das bestehende Poll-Zyklus-Modell:

```
POST https://oauth2.googleapis.com/token
Content-Type: application/x-www-form-urlencoded

client_id=...&client_secret=...&refresh_token=...&grant_type=refresh_token
```

Die Antwort enthält `access_token`, `expires_in` (üblicherweise `3600`
Sekunden), `scope`, `token_type` — **keinen neuen `refresh_token`** (siehe
unten). `GoogleCalendarSourceAdapter` cached den `access_token` zusammen mit
seinem Ablaufzeitpunkt (`clock.instant().plusSeconds(expiresIn)`, abzüglich
eines kleinen Sicherheitspuffers, z. B. 60 Sekunden) als Instanzfeld und
erneuert ihn nur, wenn kein gültiger Token vorliegt — bei
`RELAY_POLL_INTERVAL`-Default `5m` und einer Access-Token-Lebensdauer von
`~1h` reduziert das die Zahl der Token-Exchange-Aufrufe um den Faktor 12
gegenüber "bei jedem Poll neu tauschen", ohne zusätzliche Komplexität
(kein neuer Port, keine Persistenz — reiner In-Memory-Zustand, exakt wie
`CalDavCalendarSourceAdapter`s `deltaSyncPermanentlyDisabled`-Flag).

### Die entscheidende, jetzt beantwortete Frage: Rotiert Google den Refresh-Token bei einem Refresh-Aufruf?

**Nein — nicht bei einem einfachen `grant_type=refresh_token`-Austausch.**
Laut Googles OAuth-2.0-Dokumentation
(`developers.google.com/identity/protocols/oauth2`, Abschnitt zu
"Refreshing an access token") enthält die Antwort auf einen
`grant_type=refresh_token`-Aufruf **keinen** neuen `refresh_token`-Wert; ein
neuer `refresh_token` wird ausschließlich beim ursprünglichen
`grant_type=authorization_code`-Austausch mit `access_type=offline`
ausgestellt (oder bei einem erneuten Consent mit `prompt=consent`). Der
ursprünglich beschaffte Refresh-Token bleibt gültig und wird für jeden
künftigen Access-Token-Austausch unverändert wiederverwendet, bis:

- der Nutzer den Zugriff manuell widerruft,
- der Token **6 Monate** ununterbrochen ungenutzt bleibt,
- das Google-Konto sein Limit gleichzeitig gültiger Refresh-Tokens pro
  Client+Nutzer überschreitet (ein neuer Grant für dieselbe
  Client-ID+Scope-Kombination invalidiert dabei still den ältesten — relevant
  nur, falls für **dasselbe** Google-Konto+Client wiederholt neue Consents
  durchgeführt werden, nicht bei normalem Betrieb dieser Anwendung mit einem
  einzigen, dauerhaft genutzten Refresh-Token), oder
- der OAuth-Consent-Screen des Projekts material geändert wird (z. B.
  Scope-Erweiterung).

**Konsequenz: Statische Konfiguration ist tatsächlich ausreichend — es wird
kein neuer Persistenz-Port für den Refresh-Token gebraucht.** Der
Refresh-Token wird exakt wie ein CalDAV-Passwort behandelt: einmalig
außerhalb der Anwendung beschafft, als Umgebungsvariable
(`GOOGLE_..._REFRESH_TOKEN`) an die laufende Anwendung übergeben, dort nie
verändert oder neu geschrieben. Die einzige Laufzeit-Fehlerbehandlung, die
diese Entscheidung nötig macht, ist der Fall "Refresh-Token wurde extern
widerrufen/ist abgelaufen" (Antwort `400 invalid_grant` auf den
Token-Exchange) — siehe "Fehlerfälle" unten; kein automatischer
Wiederherstellungsmechanismus dafür, exakt analog zu einem abgelaufenen
CalDAV-Passwort heute (manueller Eingriff des Deployers nötig).

## Design-Entscheidung 3: Rekursionsauflösung über `singleEvents=true`

### Warum bevorzugt gegenüber eigener RRULE-Expansion

`CalDavCalendarSourceAdapter.expandRecurringSeries` implementiert RRULE-
Expansion inklusive `EXDATE`-/`RECURRENCE-ID`-Behandlung selbst (mit
`ical4j`s `Recur`), weil RFC 4791/6578 unexpandierte `VEVENT`s zurückgeben.
Google Calendar API v3s `events.list` unterstützt den Query-Parameter
`singleEvents=true`, der bereits **einzeln expandierte Vorkommen** direkt
von Google zurückliefert — jedes Vorkommen als eigenes Event-JSON-Objekt,
inklusive bereits aufgelöster `RECURRENCE-ID`-artiger Overrides. Diese Spec
bevorzugt diesen Weg klar gegenüber einer eigenen Nachbildung der
RRULE-Auflösung für Googles JSON-Form: kein neuer Expansionscode, keine
zweite, parallel gepflegte Implementierung derselben fachlichen Logik für
ein zweites Datenformat.

### Wie sich das zusammengesetzte `sourceUid`-Schema abbildet

`docs/domain.md`s zusammengesetztes `sourceUid`-Schema
(`<Serien-UID>#<ursprünglicher Vorkommen-Instant>`) verlangt, dass sich zu
jedem expandierten Vorkommen sowohl seine Serienherkunft als auch sein
ursprünglicher, serienberechneter (nicht der ggf. verschobene tatsächliche)
Startzeitpunkt ermitteln lässt. Google liefert genau diese beiden Werte pro
expandiertem Vorkommen mit:

- **`recurringEventId`** — die Event-ID des Serien-Master-Events, auf jedem
  expandierten Vorkommen gesetzt (Analogon zur CalDAV-Serien-`UID`).
- **`originalStartTime`** — der Zeitpunkt, zu dem dieses Vorkommen laut
  Wiederholungsregel ursprünglich stattgefunden hätte, **vor** jeder
  individuellen Verschiebung (Analogon zu CalDAVs `RECURRENCE-ID`).

`sourceUid` für ein Google-Vorkommen wird also analog zu CalDAV zusammengesetzt:
`recurringEventId + "#" + originalStartTime` für ein Vorkommen aus einer
Serie, oder schlicht die Event-`id` selbst für einen echten Einzeltermin
(kein `recurringEventId` gesetzt). **Das bestätigt explizit, dass Googles
`singleEvents=true`-Antwortform nichts verliert, was das bestehende
zusammengesetzte `sourceUid`-Schema braucht** — dieselbe fachliche Garantie
(stabile Identität über eine spätere Verschiebung hinweg, siehe
`docs/domain.md`) bleibt erfüllt, nur aus anderen JSON-Feldern statt
ICS-Properties gespeist.

### Ein Risiko, das CalDAVs Design nicht hat, und seine Entschärfung

`docs/features/delta-sync.md` begründet ausführlich, **warum**
`CalendarReplicaStore` bewusst **rohe, unexpandierte** CalDAV-Ressourcen
cached statt bereits expandierter Vorkommen: Das konfigurierte
`recurring-event-horizon`-Zeitfenster gleitet relativ zu `now` bei jedem
Poll-Zyklus nach vorne; eine unveränderte, aber unbegrenzt wiederkehrende
Serie muss trotzdem bei jedem Zyklus **neue** Vorkommen offenbaren können,
sobald das Fenster weiter fortschreitet. CalDAV löst das, weil die
RRULE-Expansion clientseitig, lokal und kostenlos (kein Netzwerk-I/O) bei
jedem `readEvents()`-Aufruf komplett neu läuft — unabhängig davon, ob ein
`sync-collection`-Delta überhaupt etwas gemeldet hat.

Würde eine Google-Replik stattdessen bereits von `singleEvents=true`
expandierte Einzelvorkommen cachen und ausschließlich über
`syncToken`-Deltas aktualisieren, entstünde exakt das Problem, das
`delta-sync.md` für CalDAV bewusst vermeidet: Ein `syncToken`-Delta meldet
nur tatsächliche Erstellungen/Änderungen/Löschungen — niemals "hier ist ein
Vorkommen, das erst durch das reine Fortschreiten der Zeit neu ins
Horizont-Fenster gerutscht ist". Eine langlaufende, unveränderte
wiederkehrende Serie würde bei reiner `syncToken`-Delta-Pflege für immer bei
den zum Zeitpunkt des letzten tatsächlichen Deltas sichtbaren Vorkommen
einfrieren — ein stiller Regressionsfehler gegenüber dem heutigen
CalDAV-Verhalten, das diese Spec's Randbedingung ("nicht funktional
behindert") zwar nicht direkt für CalDAV, aber sinngemäß auch für den neuen
Google-Pfad verletzen würde.

**Entschärfung:** Bei **jedem** `readEvents()`-Aufruf führt
`GoogleCalendarSourceAdapter` zusätzlich zum `syncToken`-Delta-Abgleich
(siehe Design-Entscheidung 4) einen zweiten, bewusst eng begrenzten,
`syncToken`-unabhängigen Request aus:

```
GET .../events?singleEvents=true&showDeleted=false
    &timeMin=<now>&timeMax=<now + recurringEventHorizon>
```

Dieser Request ist **nicht** Teil der persistierten Replik-Fortschreibung
(kein `syncToken`, kein `applyDelta`) — sein einziger Zweck ist,
Vorkommen zu erfassen, die ausschließlich durch das Fortschreiten von `now`
neu ins Horizont-Fenster gerutscht sind, ohne dass sich am zugrunde liegenden
Google-Event je etwas änderte. Sein Ergebnis wird mit den aus der Replik
rekonstruierten Vorkommen **vereinigt** (dedupliziert über die
Event-`id`), bevor die endgültige `SourceEvent`-Liste zurückgegeben wird.
Kosten: ein zusätzlicher, auf das Horizont-Fenster begrenzter (nicht
historien-vollständiger) Request pro Poll-Zyklus — spürbar kleiner als ein
vollständiger Resync, und ein bewusst akzeptierter, expliziter
Mehraufwand gegenüber dem reinen CalDAV-Pfad, der diese Fensterverschiebung
komplett kostenlos lokal löst. Siehe Open Questions für die noch offene
Verifikation, ob `timeMin`/`timeMax` und `syncToken` in einer einzigen
Anfrage kombinierbar sind (falls ja, ließe sich dieser zusätzliche Request
möglicherweise ganz einsparen — siehe dort).

## Design-Entscheidung 4: Delta-Sync / Replika-Speicherung — eigener, dedizierter Port statt `CalendarReplicaStore`-Wiederverwendung

### Die Frage

Kann Googles `syncToken`+`events.list`-Delta-Antwort denselben
`CalendarReplicaStore`-Port/dieselbe Abstraktion wiederverwenden (jedes
Google-Event als opake "Ressource" derselben Form wie ein gecachter
CalDAV-ICS-Blob behandeln, z. B. durch Speichern seiner JSON-Repräsentation
anstelle von `rawCalendarData`), oder rechtfertigt Googles strukturell
andere (JSON, nicht rohes ICS) Antwortform einen parallelen,
Google-spezifischen Replika-Port?

### Entscheidung: eigener Port, `GoogleCalendarReplicaStore`

**Beide Optionen sind vertretbar — diese Spec entscheidet sich für den
eigenen Port**, aus denselben Gründen, aus denen `docs/features/delta-sync.md`
selbst bereits `CalendarReplicaStore` als eigenen Port statt einer
`StateStore`-Erweiterung eingeführt hat (dort mit Verweis auf ADR-008), hier
angewendet auf die Beziehung zwischen `CalendarReplicaStore` und einem neuen
Google-Äquivalent:

- **Semantisch unehrliche Typ-Wiederverwendung.** `CachedCalendarResource.
  rawCalendarData` ist explizit als "vollständiger roher `calendar-data`-
  Inhalt … wie vom Server geliefert" dokumentiert und wird ausschließlich
  von `CalDavCalendarSourceAdapter.parseVEvents(...)` (ical4j-ICS-Parsing)
  konsumiert. Google JSON in dasselbe `String`-Feld zu legen würde den Typ
  wiederverwenden, ohne dass die dazugehörige Verarbeitungspipeline
  irgendetwas teilt — eine Wiederverwendung ohne fachlichen Vorteil, nur
  einer zusätzlichen gedanklichen Unschärfe ("was genau steht in diesem
  Feld, hängt vom Kalender-Typ ab").
- **Unterschiedliche Cache-Granularität, nicht nur unterschiedliches
  Format.** CalDAVs Replik ist pro `href` **serienweise** granular (ein
  `href` = eine gesamte Serie inklusive aller `RECURRENCE-ID`-Overrides in
  einer Datei, siehe `delta-sync.md`). Googles natürliche Ressourcen-Identität
  unter `singleEvents=true` ist dagegen **pro einzelnem, bereits expandiertem
  Vorkommen** (jedes Vorkommen hat seine eigene, von Google vergebene,
  stabile Event-`id`). Ein Port, dessen Vertrag ("Aufrufer gruppieren nach
  UID, nicht nach Einfüge-Reihenfolge") stillschweigend CalDAVs
  Serien-Granularität voraussetzt, würde für Google entweder ignoriert oder
  fehlinterpretiert.
- **Unterschiedliche Cache-Philosophie erzwingt eigene Erweiterung.** Wie in
  Design-Entscheidung 3 hergeleitet, cached die Google-Replik zusätzlich zum
  reinen `syncToken`-Delta einen begrenzten, horizon-gebundenen
  Ergänzungs-Fetch — ein Verhalten, das `CalendarReplicaStore`s heutiger
  Vertrag (`loadSyncToken`/`loadAllResources`/`applyDelta`/`resetTo`, ohne
  jedes Konzept eines Zeitfensters) nicht abbildet und dessen Erweiterung um
  ein Google-spezifisches Zeitfenster-Konzept genau die Art von
  CalDAV-Konzept-Leckage in die andere Richtung wäre, die diese
  Design-Frage explizit vermeiden will.
- **Ein dedizierter Port hält `CalDavCalendarSourceAdapter` komplett
  unberührt.** Keine Signatur, kein Javadoc-Absatz von
  `CalendarReplicaStore`/`CachedCalendarResource` muss angefasst werden, um
  Google zu unterstützen — direkte Umsetzung der nicht verhandelbaren
  Koexistenz-Anforderung dieser Spec auf Port-Ebene.

### `GoogleCalendarReplicaStore` (neuer, dedizierter Outbound-Port)

Strukturell parallel zu `CalendarReplicaStore`, aber mit einer zusätzlichen
Methode für den horizon-begrenzten Ergänzungs-Fetch aus Design-Entscheidung 3:

```java
package ms.rohde.businesscalendarrelay.ports.outbound;

import java.util.List;
import ms.rohde.hexagonalarch.annotations.InfrastructureServicePort;
import org.jspecify.annotations.Nullable;

@InfrastructureServicePort
public interface GoogleCalendarReplicaStore {

    /**
     * Returns the persisted sync-token for this Google source calendar, or {@code null} if
     * none is stored yet. A {@code null} return means the next {@code events.list} exchange
     * must omit {@code syncToken} entirely (full sync).
     */
    @Nullable String loadSyncToken();

    /**
     * Returns every currently cached Google Calendar event instance for this source
     * calendar. Order is not part of the contract.
     */
    List<CachedGoogleCalendarEvent> loadAllEvents();

    /**
     * Applies one incremental {@code events.list(syncToken=...)} delta in a single
     * persistence operation: upserts {@code upserted} (keyed by
     * {@link CachedGoogleCalendarEvent#eventId()}), removes every entry whose
     * {@code eventId} is in {@code removedEventIds} (Google-side cancellations, reported via
     * {@code showDeleted=true}), and advances the stored sync-token to {@code newSyncToken}.
     *
     * @throws GoogleCalendarReplicaStoreException if the underlying persistence operation fails
     */
    void applyDelta(String newSyncToken, List<CachedGoogleCalendarEvent> upserted, List<String> removedEventIds);

    /**
     * Replaces the entire cached event set and sync-token for this source calendar in one
     * shot. Used for an initial {@code events.list} exchange (no {@code syncToken}) and for a
     * forced full resync after Google invalidates a previously stored token ({@code 410 Gone}).
     *
     * @throws GoogleCalendarReplicaStoreException if the underlying persistence operation fails
     */
    void resetTo(String newSyncToken, List<CachedGoogleCalendarEvent> events);
}
```

- **Ein konfiguriertes Instanz pro Google-Quellkalender**, exakt wie jeder
  andere Port dieses Projekts.
- Bewusst **keine** eigene Methode für den horizon-begrenzten
  Ergänzungs-Fetch aus Design-Entscheidung 3 — dieser Fetch ist bewusst
  **nicht** Teil der persistierten Replik (kein `syncToken`-Fortschritt,
  kein `applyDelta`), sondern lebt vollständig innerhalb von
  `GoogleCalendarSourceAdapter` selbst als zusätzlicher,
  syncToken-unabhängiger HTTP-Aufruf, dessen Ergebnis nur transient mit dem
  Replik-Inhalt vereinigt wird, bevor `SourceEvent`s daraus gebaut werden.

### `CachedGoogleCalendarEvent` (neuer, port-begleitender Werttyp)

```java
package ms.rohde.businesscalendarrelay.ports.outbound;

import java.util.Objects;

/**
 * One Google Calendar event instance as last reported by {@code events.list}, keyed by its
 * Google-assigned {@code eventId} -- either a genuinely single event, or one already-expanded
 * occurrence of a recurring series ({@code singleEvents=true}). A transport-/protocol-level
 * value type living beside {@link GoogleCalendarReplicaStore}, analogous to
 * {@code CachedCalendarResource} beside {@link CalendarReplicaStore} -- it carries pure Google
 * Calendar API protocol knowledge (raw event JSON, event ID, ETag), not domain meaning.
 *
 * @param eventId Google's own, stable identifier for this event or event instance -- the key
 *     the local replica is indexed by
 * @param etag the resource's last known ETag, stored purely for operational diagnosis, never
 *     compared by the adapter itself, mirroring {@code CachedCalendarResource#etag}
 * @param rawEventJson the full raw Event resource JSON, as delivered by Google, unparsed
 */
public record CachedGoogleCalendarEvent(String eventId, String etag, String rawEventJson) {

    public CachedGoogleCalendarEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(etag, "etag must not be null");
        Objects.requireNonNull(rawEventJson, "rawEventJson must not be null");

        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (rawEventJson.isBlank()) {
            throw new IllegalArgumentException("rawEventJson must not be blank");
        }
    }
}
```

### `GoogleCalendarReplicaStoreException`

Analog zu `CalendarReplicaStoreException`:

```java
package ms.rohde.businesscalendarrelay.ports.outbound;

public class GoogleCalendarReplicaStoreException extends RuntimeException {

    public GoogleCalendarReplicaStoreException(String message) {
        super(message);
    }

    public GoogleCalendarReplicaStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### `GoogleCalendarSourceAdapter implements CalendarSource`

```java
public GoogleCalendarSourceAdapter(
        HttpClient httpClient,
        String googleCalendarId,
        String googleClientId,
        String googleClientSecret,
        String googleRefreshToken,
        Clock clock,
        Period recurringEventHorizon,
        GoogleCalendarReplicaStore googleCalendarReplicaStore,
        boolean deltaSyncEnabled)
```

Strukturell parallel zu `CalDavCalendarSourceAdapter`s Konstruktor. Intern:

- **JSON-Parsing über Jackson (`ObjectMapper`/`JsonNode`), nicht das
  offizielle `google-api-client`/`google-api-services-calendar`-SDK.**
  `spring-boot-starter-web` bringt Jackson bereits transitiv mit — kein
  neues Artefakt. Das offizielle Google-API-Java-SDK zieht dagegen eine
  erhebliche eigene Abhängigkeitskette (`google-http-client`, Guava, `gson`,
  teils `opencensus`) für einen einzigen REST-Endpunkt, den dieser Adapter
  ohnehin nur lesend über wenige Query-Parameter anspricht — konsistent mit
  `CLAUDE.md`s *"Add dependencies only with clear justification — no large
  libraries for trivial tasks"* und mit dem bereits etablierten Muster, dass
  `CalDavCalendarSourceAdapter` CalDAV ebenfalls über plain
  `java.net.http.HttpClient` statt eines dedizierten CalDAV-SDKs anspricht.
- **`readEvents()`-Ablauf:** Access-Token besorgen/aus Cache lesen (siehe
  Design-Entscheidung 2) → falls `deltaSyncEnabled`: `syncToken` laden,
  `events.list` mit oder ohne `syncToken` ausführen, Replik entsprechend
  fortschreiben (`applyDelta`/`resetTo`), **plus** den horizon-begrenzten
  Ergänzungs-Fetch aus Design-Entscheidung 3 ausführen und mit dem
  Replik-Ergebnis vereinigen → aus jedem gecachten/ergänzten
  `CachedGoogleCalendarEvent` ein `SourceEvent` bauen (Feld-Mapping siehe
  unten) → vollständige Liste zurückgeben.
- Falls `deltaSyncEnabled == false`: bei jedem Aufruf ein einziger,
  vollständiger `events.list(singleEvents=true, showDeleted=false,
  timeMax=now+recurringEventHorizon)`-Request ohne `syncToken`, Replik wird
  nie berührt — funktional äquivalent zu CalDAVs Legacy-`calendar-query`-
  Fallback.
- **Feld-Mapping Google-Event → `SourceEvent`:**

  | `SourceEvent`-Feld | Herkunft aus Google-Event-JSON |
  |---|---|
  | `sourceUid` | `recurringEventId + "#" + originalStartTime` (Serien-Vorkommen) bzw. `id` (Einzeltermin) |
  | `start`, `end` | `start.dateTime`/`end.dateTime` (mit `start.timeZone` als Zone) bzw. `start.date`/`end.date` für ganztägig |
  | `allDay` | `true`, wenn `start.date` statt `start.dateTime` gesetzt ist |
  | `busy` | `transparency != "transparent"` (Google-Feld `transparency`, Default `"opaque"` wenn nicht gesetzt — direktes Analogon zu CalDAVs `TRANSP`) |
  | `recurring` | `true`, wenn `recurringEventId` gesetzt ist |
  | `cancelled` | `status == "cancelled"` auf dem Master-Event bzw. dem Vorkommen selbst |

  Dieses Mapping bildet jedes `SourceEvent`-Feld auf ein direktes
  Google-Analogon zum bereits bestehenden CalDAV-Mapping ab — keine neue
  fachliche Bedeutung, nur eine andere Quelle derselben fünf Fakten.
- **Kein `PROPFIND`-artiger Fähigkeits-Check, keine XML-Verarbeitung** — die
  gesamte HTTP-/XML-Infrastruktur (`newSecureDocumentBuilder`,
  `extractCalendarDataBlobs`, …) aus `CalDavCalendarSourceAdapter` wird
  **nicht** wiederverwendet, weil sie XML-spezifisch ist; der neue Adapter
  bringt seine eigene, deutlich schlankere JSON-Verarbeitung mit.

### `StateStore`, `PendingCreationQueue`, `BurstBudget`, `CalendarSource`, `CalendarReplicaStore` (bestehend)

**Alle fünf bleiben vollständig unverändert** — weder Methodensignaturen
noch transportierte Datenformen wachsen durch diese Feature.
`PollAndRelaySourceCalendarUseCase` (inbound) bleibt ebenfalls unverändert
(`pollAndRelay()` bleibt parameterlos) — `GoogleCalendarReplicaStore` und
die übrigen Google-Konfigurationswerte sind ausschließlich Konstruktions-
parameter der `GoogleCalendarSourceAdapter`-Instanz, die hinter dem
`CalendarSource`-Port für die Use-Case-Schicht unsichtbar bleibt.

## Design-Entscheidung 5: Paket-Platzierung

- **`adapters/outbound/google/`** für `GoogleCalendarSourceAdapter` und
  jeden dazugehörigen, google-spezifischen Hilfscode (z. B. den
  OAuth-Token-Exchange, das JSON→Event-Mapping) — spiegelt exakt
  `adapters/outbound/caldav/`s bestehende Platzierung, konsistent mit
  `CLAUDE.md`s Paketstruktur-Vorgabe (`adapters/` für
  Framework-/Infrastruktur-Implementierungen, ein Unterpaket pro
  Protokoll/Provider).
- **`adapters/outbound/persistence/`** für alle neuen JPA-Bestandteile
  (`GoogleCalendarReplicaResourceEntity`, `GoogleCalendarSyncTokenEntity`,
  `GoogleCalendarReplicaResourceJpaRepository`,
  `GoogleCalendarSyncTokenJpaRepository`,
  `JpaGoogleCalendarReplicaStoreAdapter`) — spiegelt exakt, wie bereits
  `CalendarReplicaResourceEntity`/`CalendarSyncTokenEntity`/
  `JpaCalendarReplicaStoreAdapter` **nicht** unter
  `adapters/outbound/caldav/`, sondern zusammen mit jeder anderen
  Persistenz-Komponente unter `adapters/outbound/persistence/` liegen — das
  bestätigt das bereits etablierte Muster "Protokoll-/Provider-spezifisches
  Verhalten lebt im Protokoll-Unterpaket, jede JPA-Persistenz lebt
  zusammen, unabhängig davon, welchen Port sie erfüllt".
- **`ports/outbound/`** für `GoogleCalendarReplicaStore`,
  `CachedGoogleCalendarEvent`, `GoogleCalendarReplicaStoreException` — exakt
  wie ihre CalDAV-Äquivalente.

## Domain model additions

**Keine.** `SourceEvent`, `RelayState`, `RelayAction`, `RelayDiffPlanner`
bleiben byte-identisch zum heutigen Stand — siehe Design-Entscheidung 1s
explizite Bestätigung. Jedes Google-spezifische Protokolldetail, das diese
Feature einführt (OAuth-Token, `syncToken`, Event-`id`, `recurringEventId`,
`originalStartTime`), ist reines Adapter-/Port-Wissen, exakt wie bereits
`RRULE`/`EXDATE`/`RECURRENCE-ID` (`event-filtering.md`) und Sync-Token/
`href`/ETag (`delta-sync.md`) konsequent an der Adapter-Grenze gehalten
wurden.

## Persistenz

### Tabelle `google_calendar_replica_event`

Neue Entity `GoogleCalendarReplicaResourceEntity`, strukturell ein direktes
Geschwister von `CalendarReplicaResourceEntity`:

| Spalte | Typ (Java) | Nullable | Beschreibung |
|---|---|---|---|
| `source_calendar_id` | `String` | nein, Teil des zusammengesetzten PK | wie bei jeder anderen Kalender-gescopten Tabelle |
| `event_id` | `String` | nein, Teil des zusammengesetzten PK | Googles Event-`id`, Analogon zu `href` |
| `etag` | `String` | nein | rein informativ, wie bei `calendar_replica_resource` |
| `raw_event_json` | `String` (`@Lob`) | nein | vollständiges rohes Event-JSON |

Primärschlüssel zusammengesetzt aus `(source_calendar_id, event_id)`, per
`@IdClass`.

### Tabelle `google_calendar_sync_token`

Neue Entity `GoogleCalendarSyncTokenEntity`, strukturell ein direktes
Geschwister von `CalendarSyncTokenEntity`:

| Spalte | Typ (Java) | Nullable | Beschreibung |
|---|---|---|---|
| `source_calendar_id` | `String` | nein, PK | wie oben |
| `sync_token` | `String` | ja | `null` bedeutet "kein initialer Sync bisher erfolgreich abgeschlossen" |

Beide Tabellen entstehen über `hibernate.ddl-auto: update` automatisch,
keine manuelle Migration nötig, exakt wie bei jeder vorherigen Feature
dieses Projekts.

### `JpaGoogleCalendarReplicaStoreAdapter implements GoogleCalendarReplicaStore`

Strukturell exakt `JpaCalendarReplicaStoreAdapter`s Aufbau: Konstruktor
nimmt zwei neue, geteilte Spring-Data-Repositories
(`GoogleCalendarReplicaResourceJpaRepository`,
`GoogleCalendarSyncTokenJpaRepository`), die pro-Kalender-`sourceCalendarId`
und einen `PlatformTransactionManager` entgegen; `applyDelta(...)` und
`resetTo(...)` sind `@Transactional`. Wie jeder andere pro-Kalender-Adapter
**kein** auto-gescannter Spring-Singleton-Bean —
`RelayWiringConfiguration` konstruiert eine Instanz pro Google-Kalender von
Hand, **nur wenn `calendar.type() == GOOGLE`** (kein Leerlauf-Overhead für
rein-CalDAV-Deployments).

### `PerCalendarComponentBeanDefinitionPruner` (ADR-006) — Konsequenz

Wie bereits von `delta-sync.md` für seine eigenen neuen Klassen
vorausgesehen: `GoogleCalendarSourceAdapter` und
`JpaGoogleCalendarReplicaStoreAdapter` müssen der
`PER_CALENDAR_COMPONENT_CLASS_NAMES`-Liste hinzugefügt werden — sonst
schlägt der Kontext-Start mit derselben `UnsatisfiedDependencyException`
fehl, die ADR-006 für die ursprünglichen drei (jetzt fünf) pro-Kalender-
parametrisierten Klassen beschreibt.

### Wiring (`RelayWiringConfiguration`)

`buildUseCases(...)`/`pollAndRelaySourceCalendarUseCases(...)` bekommen zwei
neue, geteilte Repository-Parameter
(`GoogleCalendarReplicaResourceJpaRepository`,
`GoogleCalendarSyncTokenJpaRepository`), exakt nach demselben Muster wie
die beiden bereits bestehenden `CalendarReplicaStore`-Repositories. Ein
neuer `@Bean HttpClient`? **Nein** — `relayCalDavHttpClient()` wird trotz
seines CalDAV-spezifischen Namens unverändert für beide Adapter-Typen
wiederverwendet (ein `java.net.http.HttpClient` ist protokollagnostisch);
eine Umbenennung zu `relayHttpClient` ist eine risikolose,
verhaltensneutrale Aufräum-Gelegenheit beim Implementieren, aber nicht
zwingend Teil dieser Spec.

## Konfiguration

> **Stand nach `docs/features/relay-config-consolidation.md`:** Das unten
> gezeigte Konfigurationsschema (`organizer-email`/`attendee-email`/
> `from-address`/`reply-to-address` sowie `google-client-id`/
> `google-client-secret`/`google-refresh-token` pro `relay.calendars[]`-
> Eintrag) ist die Design-Rationale zum Zeitpunkt dieser Spec, inzwischen
> aber überholt: die iMIP-Identität ist auf globale `relay.*`-Felder
> gehoben, und die drei Google-Credential-Felder sind durch eine einzelne
> `google-credentials-id`-Referenz auf eine neue, geteilte
> `relay.google-credentials[]`-Liste ersetzt. Die historische Herleitung
> unten bleibt unverändert stehen; die aktuell gültige Konfigurationsreferenz
> steht in [`relay-config-consolidation.md`](relay-config-consolidation.md)
> und [`docs/technical/google-calendar-setup.md`](../technical/google-calendar-setup.md).

### `application.yml` / README-Konfigurationstabelle — neue Umgebungsvariablen

Analog zum bestehenden Muster pro `relay.calendars[]`-Eintrag (kein neuer
globaler `RELAY_*`-Eintrag, da jedes neue Feld pro Kalender-Eintrag
konfigurierbar ist, nicht global):

| Feld | Beschreibung |
|---|---|
| `type` | `caldav` (Default) oder `google`. Fehlt das Feld, bindet Spring Boot automatisch auf `caldav` — Zero-Config-Migration für jedes bestehende Deployment. |
| `google-calendar-id` | Google-Kalender-ID (bei einem persönlichen Konto meist die Gmail-Adresse selbst, oder eine über Google Calendars "Kalender integrieren"-Einstellungsseite ermittelte ID für einen sekundären Kalender). Pflicht nur bei `type: google`. |
| `google-client-id`, `google-client-secret` | Die vom Deployer selbst in der Google Cloud Console angelegten OAuth-2.0-Client-Credentials (siehe Design-Entscheidung 2). Pflicht nur bei `type: google`. |
| `google-refresh-token` | Der einmalig per OAuth-Playground-Consent beschaffte, langlebige Refresh-Token (siehe Design-Entscheidung 2) — wie ein CalDAV-Passwort ausschließlich über eine Umgebungsvariable, nie im Klartext eingecheckt. Pflicht nur bei `type: google`. |

`caldav-url`, `caldav-username`, `caldav-password` bleiben unverändert
Pflicht nur bei `type: caldav` (heute implizit, da es keinen anderen Typ
gab — jetzt explizit über `@ConsistentCalendarSourceFields`).

### Beispielblock in `application.yml`

```yaml
relay:
  poll-interval: 5m
  recurring-event-horizon: P6M
  calendars:
    - id: personal-nextcloud
      # type: caldav   -- optional, das ist ohnehin der Default; bestehende
      # Deployments ohne dieses Feld sind von dieser Feature unberührt
      caldav-url: https://cloud.example.com/remote.php/dav/calendars/user/personal/
      caldav-username: ${CALDAV_PERSONAL_USERNAME}
      caldav-password: ${CALDAV_PERSONAL_PASSWORD}
      organizer-email: ${RELAY_PERSONAL_ORGANIZER_EMAIL}
      attendee-email: ${RELAY_PERSONAL_ATTENDEE_EMAIL}
      from-address: ${RELAY_PERSONAL_FROM_ADDRESS}
      reply-to-address: ${RELAY_PERSONAL_REPLY_TO_ADDRESS}
    - id: personal-google
      type: google
      google-calendar-id: ${GOOGLE_PERSONAL_CALENDAR_ID}
      google-client-id: ${GOOGLE_PERSONAL_CLIENT_ID}
      google-client-secret: ${GOOGLE_PERSONAL_CLIENT_SECRET}
      google-refresh-token: ${GOOGLE_PERSONAL_REFRESH_TOKEN}
      organizer-email: ${RELAY_GOOGLE_ORGANIZER_EMAIL}
      attendee-email: ${RELAY_GOOGLE_ATTENDEE_EMAIL}
      from-address: ${RELAY_GOOGLE_FROM_ADDRESS}
      reply-to-address: ${RELAY_GOOGLE_REPLY_TO_ADDRESS}
```

Beide Einträge werden vom selben `PollAndRelaySchedulerAdapter` unabhängig
im konfigurierten `relay.poll-interval` gepollt — **das ist die direkte,
konkrete Erfüllung der nicht verhandelbaren Koexistenz-Anforderung dieser
Spec**, ohne dass der Scheduler selbst irgendetwas über den Unterschied
zwischen den beiden Einträgen wissen muss (er iteriert nur über
`List<PollAndRelaySourceCalendarUseCase>`, exakt wie heute schon bei
mehreren CalDAV-Kalendern).

## Fehlerfälle — Ergänzungen

- **Access-Token-Austausch schlägt fehl mit `400 invalid_grant`.** Bedeutet:
  der Refresh-Token wurde extern widerrufen, ist abgelaufen (6 Monate
  Inaktivität) oder wurde durch einen neueren Grant für dieselbe
  Client-ID+Scope-Kombination invalidiert (siehe Design-Entscheidung 2).
  Kein automatischer Wiederherstellungsmechanismus — wird als
  `GoogleCalendarSourceException` (neue, adapter-lokale Exception-Klasse,
  strukturell parallel zu `CalDavCalendarSourceException`) unverpackt bis
  zum Poll-Zyklus durchgereicht, der komplett abbricht, genau wie ein
  fehlgeschlagener CalDAV-Request heute. Der Deployer muss den
  OAuth-Consent-Flow (Design-Entscheidung 2) manuell wiederholen und den
  neuen `google-refresh-token`-Wert einspielen — analog zu einem
  abgelaufenen CalDAV-Passwort.
- **`events.list` antwortet mit `410 Gone`** (Googles dokumentiertes Signal
  für einen ungültig gewordenen `syncToken`, direktes Analogon zu CalDAVs
  `403`/`507`-Fällen). Löst einen erzwungenen vollständigen Resync aus
  (`resetTo(...)` mit frisch geladenem `syncToken` aus einer Anfrage ohne
  `syncToken`-Parameter) — kein Datenverlust, exakt dieselbe Behandlung wie
  in `delta-sync.md` für CalDAV beschrieben.
- **`events.list`/Token-Exchange antwortet mit `403`/`429` (Quota
  überschritten).** Wird wie jeder andere unerwartete Statuscode als
  `GoogleCalendarSourceException` behandelt, die nur den aktuellen
  Poll-Zyklus scheitern lässt — der nächste Zyklus versucht es mit
  demselben, weiterhin gültigen `syncToken`/Refresh-Token erneut. Kein
  automatischer Backoff-Mechanismus über den bestehenden
  `relay.poll-interval`-Zyklus hinaus — konsistent mit CalDAVs Umgang mit
  einem transienten `503` (ADR-011).
- **`googleCalendarReplicaStore.*` schlägt fehl.** Wird als
  `GoogleCalendarReplicaStoreException` unverpackt bis `readEvents()`
  durchgereicht, dort in eine `GoogleCalendarSourceException` gewrappt —
  exakt parallele Fehlerbehandlung zu `CalendarReplicaStore` in
  `delta-sync.md`.

## Weitere Entscheidungen — eigene Einschätzung

- **Nur der `calendar.readonly`-Scope, nicht `calendar` (Lese-/Schreibzugriff).**
  `GoogleCalendarSourceAdapter` implementiert ausschließlich
  `CalendarSource.readEvents()` — es gibt keinen fachlichen Grund, jemals
  in den Google-Quellkalender zu schreiben. Der engste passende Scope
  minimiert den Schaden eines kompromittierten Refresh-Tokens und macht den
  einmaligen Consent-Bildschirm für den Deployer transparenter ("nur
  lesend").
- **Kein neuer, dedizierter OAuth-Port.** Der Access-Token-Austausch ist
  reine, protokollinterne Implementierungslogik von
  `GoogleCalendarSourceAdapter`, kein eigenständiges, fachlich bedeutsames
  Konzept, das ein eigener Hexagonal-Port abbilden müsste — analog dazu,
  dass die Basic-Auth-Header-Konstruktion in `CalDavCalendarSourceAdapter`
  ebenfalls reine Adapter-interne Logik ist, kein eigener Port.
- **Kein neuer globaler `HttpClient`-Bean, keine Aufteilung nach Protokoll.**
  Ein `java.net.http.HttpClient` ist bereits protokollagnostisch — dieselbe
  geteilte Instanz für CalDAV und Google zu verwenden spart Ressourcen ohne
  Nachteil, siehe Wiring-Abschnitt oben.
- **Kein Verzicht auf den horizon-begrenzten Ergänzungs-Fetch aus
  Design-Entscheidung 3, trotz des zusätzlichen Netzwerk-Aufrufs pro
  Zyklus.** Die Alternative (ihn wegzulassen) würde einen stillen
  Regressionsfehler für langlaufende, unveränderte wiederkehrende
  Google-Serien einführen — genau die Art von Fehler, vor der
  `delta-sync.md`s eigene, ausführliche Herleitung für CalDAV bereits warnt.
  Ein zusätzlicher, bewusst eng auf das Horizont-Fenster begrenzter Request
  pro Zyklus ist der günstigere Preis.
- **Keine Unterstützung für Google-Workspace-Domain-Wide-Delegation oder
  Service-Account-basierte Authentifizierung.** Diese Spec deckt
  ausschließlich den in der Recherche verifizierten Fall eines persönlichen
  (Nicht-Workspace-)Gmail-Kontos mit einem selbst angelegten OAuth-Client ab
  — der einzige vom Auftraggeber tatsächlich benötigte Fall. Eine
  Workspace-Domain mit Service-Account-Delegation wäre ein eigenständiges,
  hier nicht betrachtetes Erweiterungsszenario.
- **Kein `docs/adr/`-Eintrag in dieser Spec.** Konsistent mit dem bereits
  etablierten Muster dieses Projekts (siehe `delta-sync.md`,
  `burst-filter-initialization.md`): Eine ADR wird erst geschrieben, wenn
  diese Feature tatsächlich implementiert ist, nicht vorab als Teil des
  Designs.

## Out of scope

- **Automatische Erneuerung eines widerrufenen/abgelaufenen Refresh-Tokens.**
  Erfordert zwingend erneute Nutzerinteraktion (Consent) — kein
  vollautomatischer Mechanismus ist möglich, ohne die "headless
  Hintergrund-Poller"-Eigenschaft dieser Anwendung aufzugeben. Siehe
  Design-Entscheidung 2/Fehlerfälle.
- **Interaktiver, in die laufende Anwendung eingebauter OAuth-Consent-Flow**
  (z. B. ein temporärer HTTP-Endpunkt, der den Deployer durch Google-Login
  führt). Bewusst durch den manuellen, außerhalb der Anwendung
  stattfindenden OAuth-Playground-Schritt ersetzt (Design-Entscheidung 2) —
  jede Alternative hätte eine massive architektonische Erweiterung (erster
  eingehender HTTP-Endpunkt dieser Anwendung überhaupt) für ein Bedürfnis
  erfordert, das nur einmal pro Google-Kalender auftritt.
- **Google-Workspace-Domain-Wide-Delegation / Service-Account-Auth.** Siehe
  "Weitere Entscheidungen" oben.
- **Schreibzugriff auf den Google-Quellkalender** (Erstellung, Änderung,
  Löschung von Google-seitigen Events durch diese Anwendung). Diese
  Anwendung liest ausschließlich; siehe Scope-Entscheidung oben.
- **Pro-Kalender-Override der OAuth-Client-Credentials über mehrere
  Google-Kalender desselben Deployments hinweg.** Diese Spec geht davon
  aus, dass jeder konfigurierte Google-Kalender-Eintrag seine eigenen,
  vollständigen `google-client-id`/`google-client-secret`/
  `google-refresh-token`-Werte trägt (auch wenn zwei Einträge zufällig
  denselben OAuth-Client teilen könnten) — kein zusätzlicher
  Konfigurationsmechanismus zum Teilen dieser Werte über mehrere Einträge
  hinweg.
- **Änderungen an `StateStore`, `PendingCreationQueue`, `BurstBudget`,
  `CalendarSource`, `CalendarReplicaStore` oder irgendeiner bestehenden
  CalDAV-Adapter-Datei.** Alle bleiben unangetastet — siehe Port-Änderungen
  oben und die nicht verhandelbare Koexistenz-Anforderung dieser Spec.
- **Migration bestehender CalDAV-Kalender-Konfigurationen zu `type: google`
  oder umgekehrt.** Kein Migrationswerkzeug, kein Umschalt-Mechanismus für
  einen bereits laufenden Kalender-Eintrag von einem Typ zum anderen — ein
  Typwechsel für einen bereits produktiven `id`-Eintrag würde ohnehin dessen
  gesamten `RelayState`-Bestand fachlich neu interpretieren müssen (ein
  anderer Quellkalender unter derselben `id`), was bereits durch
  `README.md`s bestehende Warnung ("`id` darf nach dem ersten Relay-Lauf
  niemals umbenannt werden") implizit ausgeschlossen ist.

## Open questions

- **Sind `timeMin`/`timeMax` mit dem `syncToken`-Parameter auf
  `events.list` kombinierbar?** Diese Spec geht in Design-Entscheidung 3
  vorsichtshalber davon aus, dass **nicht** — und begegnet der daraus
  folgenden Horizont-Fensterverschiebungs-Lücke mit einem zusätzlichen,
  `syncToken`-unabhängigen Ergänzungs-Fetch. Sollte sich beim Implementieren
  gegen die tatsächliche API herausstellen, dass eine Kombination doch
  möglich ist, ließe sich dieser zusätzliche Request vermutlich einsparen
  (ein einziger `events.list(syncToken=..., timeMax=...)`-Aufruf würde dann
  reichen) — eine Vereinfachung, kein Bruch dieser Spec's Architektur.
- **Wie repräsentiert Google die Löschung/Stornierung einer ganzen
  wiederkehrenden Serie unter `singleEvents=true` in einer inkrementellen
  `syncToken`-Antwort** — als ein einziges cancelled Master-Event-Objekt,
  oder als ein cancelled Eintrag pro zuvor bekanntem Einzelvorkommen? Diese
  Spec nimmt an, dass jedes vom Server tatsächlich als `status: "cancelled"`
  gemeldete Objekt (mit `showDeleted=true`) unabhängig von dieser Frage
  korrekt als `removedEventIds`-Eintrag behandelt werden kann (siehe
  `GoogleCalendarReplicaStore.applyDelta`), aber die genaue Zahl und Form
  dieser Objekte für den Serien-Löschungsfall ist nicht gegen die reale API
  verifiziert und sollte vor der Implementierung geprüft werden.
- **Praktische Obergrenze der `singleEvents=true`-Expansion einer
  unbegrenzt wiederkehrenden Serie ohne `timeMax`** (relevant für den
  initialen Vollsync ohne `syncToken`, siehe Design-Entscheidung 3/4): Legt
  Google selbst eine implizite Obergrenze fest (z. B. eine maximale Zahl
  expandierter Instanzen pro Serie), oder muss der initiale Vollsync
  zwingend mit einem `timeMax` begrenzt werden, um praktikabel zu bleiben?
  Nicht verifiziert — zu klären vor der Implementierung; falls eine
  Begrenzung nötig ist, wäre `now + recurringEventHorizon` der naheliegende
  Wert, konsistent mit dem bereits für den Ergänzungs-Fetch verwendeten
  Fenster.
- **Exaktes Format von `originalStartTime` für ganztägige wiederkehrende
  Vorkommen** (`date` vs. `dateTime`-Feld, analog zur bestehenden
  CalDAV-Unterscheidung `VALUE=DATE` vs. Zeitstempel) — beeinflusst die
  genaue String-Repräsentation, die in den zusammengesetzten `sourceUid`
  eingeht (Design-Entscheidung 3). Muss beim Implementieren gegen echte
  Google-API-Antworten für ganztägige wiederkehrende Termine geprüft werden.
- **Ob ein `docs/technical/google-calendar-setup.md` (das ausformulierte
  OAuth-Playground-Vorgehen aus Design-Entscheidung 2) Teil derselben
  Implementierungs-PR oder eines separaten `tech-documenter`-Laufs danach
  wird.** Diese Spec beschreibt den Mechanismus vollständig, überlässt aber
  die redaktionelle Aufbereitung als Schritt-für-Schritt-Anleitung dem
  Implementierungs-Workflow (`tech-documenter`-Agent, per `CLAUDE.md`s
  Vorgabe "nach Implementieren eines technisch komplexen Subsystems").
