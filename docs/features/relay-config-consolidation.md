# Feature: Konfigurationskonsolidierung — einheitliche iMIP-Identität und geteilte Google-OAuth-Credentials

Kein GitHub-Issue: Diese Spec setzt einen direkten Auftrag des Projekteigentümers
um, keine zuvor dokumentierte Roadmap-Position. Zwei bislang unabhängige
Konfigurationsschwächen werden in einer Spec zusammengefasst, weil beide
denselben Kern haben — Werte, die heute pro `relay.calendars[]`-Eintrag
wiederholt werden müssen, obwohl sie fachlich nur einmal existieren sollten —
und beide ausschließlich das `config`-Paket sowie `RelayWiringConfiguration`
betreffen, keine einzige Zeile in `core/domain` oder `core/app`.

## Feature-Zusammenfassung

Heute trägt jeder `relay.calendars[]`-Eintrag seine eigene, vollständige Kopie
der iMIP-Identität (`organizer-email`, `attendee-email`, `from-address`,
`reply-to-address`) sowie — bei `type: google` — seine eigene vollständige
Kopie der Google-OAuth-Credentials (`google-client-id`, `google-client-secret`,
`google-refresh-token`). In der Praxis sind beide Wertegruppen aber für einen
einzelnen Deployer mit einem einzelnen dienstlichen Postfach und einem
einzelnen Google-Konto nicht pro Kalender unterschiedlich — sie werden
lediglich kopiert. Diese Feature entfernt beide Wiederholungen:

1. **Einheitliche iMIP-Identität.** `organizer-email`/`attendee-email`/
   `from-address`/`reply-to-address` werden von `relay.calendars[]` auf die
   globale `relay`-Ebene gehoben — ein einziger Satz, gültig für jeden
   konfigurierten Kalender, unabhängig von dessen Typ.
2. **Geteilte Google-OAuth-Credentials.** Eine neue globale Liste
   `relay.google-credentials[]` erlaubt es, ein OAuth-Credential-Set
   (`client-id`/`client-secret`/`refresh-token`) einmal pro Google-Konto zu
   benennen; jeder `type: google`-Kalendereintrag referenziert eines dieser
   Sets über eine neue `google-credentials-id` statt die drei Werte selbst zu
   tragen. `google-calendar-id` bleibt unverändert pro Kalendereintrag —
   mehrere Kalender innerhalb desselben Google-Kontos sind weiterhin
   unterschiedliche Kalender-IDs.

Beide Änderungen sind reine Konfigurationsschema- und Wiring-Änderungen. Kein
Port, keine Domänenklasse, keine Anwendungsklasse gewinnt oder verliert eine
Zeile — `PollAndRelaySourceCalendarService` und `GoogleCalendarSourceAdapter`
erhalten diese Werte weiterhin unverändert als Konstruktorparameter; nur wer
diese Parameter beim Zusammenbau in `RelayWiringConfiguration` liefert, ändert
sich.

## Nicht verhandelbare Anforderungen

- **Kein Per-Kalender-Override der iMIP-Identität.** Der Auftraggeber war
  explizit: die vier Felder sollen *"einmal ... für jeden Kalender
  einheitlich"* gelten. Diese Spec plant deshalb bewusst **keinen**
  optionalen Override-Mechanismus (z. B. ein weiterhin erlaubtes,
  aber optionales Feld auf `CalendarConfig`, das den globalen Wert
  überschreiben könnte) — die vier Felder existieren nach dieser Feature
  ausschließlich auf `RelayProperties`, nicht mehr auf `CalendarConfig`.
- **Bewusster Breaking Change, kein Kompatibilitäts-Shim.** Dies ist ein
  einzelnes, vom Auftraggeber selbst betriebenes Deployment, keine
  veröffentlichte Bibliothek mit fremden Konsumenten. Eine bereits
  produktive `relay-calendars.yml`/`.env`-Konfiguration im heutigen Format
  bindet nach diesem Upgrade **nicht** mehr unverändert — im Gegensatz zum
  Zero-Config-Migrationsanspruch von
  `docs/features/google-calendar-integration.md`s `type`-Feld ist hier
  explizit **kein** duales Binding (altes und neues Schema gleichzeitig
  akzeptieren) vorgesehen. Der Deployer migriert seine bestehende
  Konfiguration einmalig von Hand (siehe Migrationshinweis unten).

## Akteure

**Deployer** — derselbe Akteur wie in
`docs/features/google-calendar-integration.md`s Design-Entscheidung 2: bearbeitet
`config/relay-calendars.yml` und `.env` außerhalb der laufenden Anwendung. Für
diese Feature ist der Deployer der **einzige** Akteur — es gibt keinen neuen
Laufzeit-Akteur, **Scheduler** und jeder bestehende Poll-Zyklus-Ablauf sind von
dieser Feature vollständig unberührt.

## Anwendungsfälle

### UC1 — Deployer konfiguriert die einheitliche iMIP-Identität

**Ziel:** Ein einziger Satz `organizer-email`/`attendee-email`/`from-address`/
`reply-to-address`, gültig für jeden konfigurierten Kalender.

**Vorbedingungen:** Der Deployer betreibt eine oder mehrere konfigurierte
Kalender (beliebige Mischung aus `type: caldav` und `type: google`).

**Hauptablauf:**
1. Deployer trägt die vier Werte einmal auf `relay`-Ebene ein (`relay.organizer-email`
   usw., typischerweise über `${RELAY_ORGANIZER_EMAIL}` usw. aus `.env`
   referenziert).
2. Deployer entfernt die vier Felder aus jedem einzelnen
   `relay.calendars[]`-Eintrag, falls dort aus einer älteren Konfiguration
   noch vorhanden — sie werden dort nicht mehr gebunden.
3. Anwendung startet; jede gebaute `PollAndRelaySourceCalendarService`-Instanz
   erhält denselben, global konfigurierten Wertesatz, unabhängig vom
   `type()` ihres Kalendereintrags.

**Fehlerabläufe:**
- Einer der vier globalen Werte fehlt oder ist leer → bestehendes
  `@NotBlank`-Verhalten, jetzt auf `RelayProperties`-Ebene statt
  `CalendarConfig`-Ebene: Anwendungsstart bricht mit
  `ConstraintViolationException` ab, bevor auch nur ein Poll-Zyklus läuft.
- Ein `relay.calendars[]`-Eintrag trägt noch eines der vier alten,
  jetzt entfernten Felder (`organizer-email` usw. auf Kalenderebene) → wird
  von Spring Boots relaxed Binding als unbekanntes Property stillschweigend
  ignoriert, nicht als Fehler gemeldet (siehe Migrationshinweis unten für die
  Konsequenz dieses Verhaltens).

### UC2 — Deployer konfiguriert ein geteiltes Google-OAuth-Credential-Set für mehrere Kalender

**Ziel:** Ein OAuth-Credential-Set einmal pro Google-Konto benennen und von
beliebig vielen `type: google`-Kalendereinträgen desselben Kontos referenzieren
lassen, statt die drei Credential-Werte pro Eintrag zu wiederholen.

**Vorbedingungen:** Der Deployer hat mindestens einen OAuth-2.0-Client und
einen dazugehörigen Refresh-Token bereits beschafft (siehe
`docs/technical/google-calendar-setup.md`), und möchte diesen von mindestens
einem, typischerweise mehreren, Google-Kalendereinträgen (z. B. dem primären
Kalender und einem sekundären `@group.calendar.google.com`-Kalender)
gemeinsam nutzen.

**Hauptablauf:**
1. Deployer trägt einen Eintrag unter `relay.google-credentials[]` ein: eine
   stabile `id` (frei wählbar, z. B. `personal-google-account`) plus
   `client-id`/`client-secret`/`refresh-token`.
2. Für jeden `type: google`-Kalendereintrag, der dieses Credential-Set nutzen
   soll, trägt der Deployer `google-credentials-id: personal-google-account`
   ein und lässt `google-client-id`/`google-client-secret`/
   `google-refresh-token` auf Kalenderebene vollständig weg (dort nicht mehr
   gebunden).
3. `google-calendar-id` bleibt pro Kalendereintrag individuell gesetzt — auch
   zwei Kalender desselben Google-Kontos haben unterschiedliche
   `google-calendar-id`-Werte.
4. Anwendung startet; für jeden `type: google`-Kalendereintrag löst
   `RelayWiringConfiguration` dessen `google-credentials-id` gegen
   `relay.google-credentials[]` auf und konstruiert
   `GoogleCalendarSourceAdapter` mit dem aufgelösten `client-id`/
   `client-secret`/`refresh-token`-Tripel — unverändert gegenüber heute aus
   Sicht des Adapters selbst.

**Fehlerabläufe:**
- Ein `type: google`-Kalendereintrag hat keine (oder eine leere)
  `google-credentials-id` → weiterhin durch `@ConsistentCalendarSourceFields`
  abgedeckt (siehe Konfigurationsschema unten), Anwendungsstart bricht ab.
- Eine gesetzte `google-credentials-id` referenziert keine bekannte
  `relay.google-credentials[].id` (Tippfehler, vergessener Eintrag) → neue,
  in dieser Spec entworfene Validierung (siehe Konfigurationsschema unten),
  Anwendungsstart bricht mit klarer Fehlermeldung ab, **nicht** erst beim
  ersten Poll-Zyklus mit einer kryptischen `NullPointerException` oder einem
  fehlgeschlagenen OAuth-Token-Austausch.
- Zwei Einträge unter `relay.google-credentials[]` tragen dieselbe `id` →
  dieselbe neue Validierung schlägt ebenfalls fehl (siehe unten) — eine
  mehrdeutige Referenzierung wird nicht stillschweigend über "letzter Eintrag
  gewinnt" aufgelöst.

## Domain model additions

**Keine.** `BlockerEvent`, `PollAndRelaySourceCalendarService`,
`ImipCalendarRenderer` erhalten die iMIP-Identität weiterhin ausschließlich
als plain Konstruktorparameter, exakt wie heute — nur wer diese Parameter in
`RelayWiringConfiguration` befüllt, ändert sich. `GoogleCalendarSourceAdapter`
erhält `client-id`/`client-secret`/`refresh-token` ebenfalls weiterhin als
plain Konstruktorparameter, nur jetzt aus einem aufgelösten
`relay.google-credentials[]`-Eintrag statt direkt vom Kalendereintrag.

## Port additions

**Keine.** Kein neuer Port, kein geänderter Port. `CalendarSource`,
`BlockerSink`, `StateStore`, `CalendarReplicaStore`,
`GoogleCalendarReplicaStore` bleiben vollständig unangetastet.

## Configuration schema

### `RelayProperties` — neue globale Felder

```java
@Validated
@ConfigurationProperties("relay")
@ConsistentGoogleCredentialsReferences
public record RelayProperties(
        @NotNull Duration pollInterval,
        @Valid List<CalendarConfig> calendars,
        @NotNull @DefaultValue("P6M") Period recurringEventHorizon,
        @NotNull @Valid InitializationProperties initialization,

        // neu, ersetzt die bisherigen vier Felder auf CalendarConfig
        @NotBlank String organizerEmail,
        @NotBlank String attendeeEmail,
        @NotBlank String fromAddress,
        @NotBlank String replyToAddress,

        // neu
        @Valid List<GoogleCredentials> googleCredentials) {

    public RelayProperties {
        calendars = calendars == null ? List.of() : List.copyOf(calendars);
        initialization = initialization == null ? new InitializationProperties(5, Duration.ofHours(1)) : initialization;
        googleCredentials = googleCredentials == null ? List.of() : List.copyOf(googleCredentials);
    }

    public record GoogleCredentials(
            @NotBlank String id,
            @NotBlank String clientId,
            @NotBlank String clientSecret,
            @NotBlank String refreshToken) {
    }
}
```

Die vier neuen Felder folgen exakt demselben "global, geteilt über jeden
konfigurierten Kalender" Muster, das die bestehende Javadoc von
`recurringEventHorizon()` und `initialization()` bereits für genau diesen Fall
begründet — hier auf die iMIP-Identität angewendet, mit derselben Konsequenz:
kein Per-Kalender-Override, siehe "Nicht verhandelbare Anforderungen" oben.
Eine leere `googleCredentials`-Liste ist gültig (analog zu einer leeren
`calendars`-Liste) — ein Deployment ganz ohne `type: google`-Kalender
braucht keinen Eintrag.

### `CalendarConfig` — entfernte und neue Felder

```java
@ConsistentCalendarSourceFields
public record CalendarConfig(
        @NotBlank String id,
        @NotNull @DefaultValue("caldav") CalendarSourceType type,
        @Nullable String caldavUrl,
        @Nullable String caldavUsername,
        @Nullable String caldavPassword,
        @Nullable String googleCalendarId,

        // neu, ersetzt googleClientId/googleClientSecret/googleRefreshToken
        @Nullable String googleCredentialsId,

        // entfernt: organizerEmail, attendeeEmail, fromAddress, replyToAddress
        // (jetzt auf RelayProperties, siehe oben)

        @DefaultValue("true") boolean deltaSyncEnabled) {

    public enum CalendarSourceType {
        CALDAV, GOOGLE
    }
}
```

`googleCredentialsId` ist — wie `googleCalendarId` heute schon — nur bei
`type == GOOGLE` Pflicht, ausgedrückt über dieselbe
`@ConsistentCalendarSourceFields`-Constraint wie bisher, deren
`GOOGLE`-Zweig sich ändert:

```java
case GOOGLE -> isNotBlank(config.googleCalendarId())
        && isNotBlank(config.googleCredentialsId());
```

(statt bisher zusätzlich `googleClientId`/`googleClientSecret`/
`googleRefreshToken` zu prüfen — diese drei Felder existieren auf
`CalendarConfig` nach dieser Feature nicht mehr).

### Neue Validierung: `googleCredentialsId` muss auflösbar sein

**Design-Entscheidung.** Ob eine gesetzte `googleCredentialsId` tatsächlich
einen konfigurierten `relay.google-credentials[].id`-Eintrag referenziert,
kann `@ConsistentCalendarSourceFields` nicht prüfen — dessen Validator sieht
nur eine einzelne `CalendarConfig`-Instanz, nicht die Geschwisterliste
`relay.google-credentials[]`, die nur auf `RelayProperties`-Ebene sichtbar
ist. Diese Spec folgt deshalb demselben, im Projekt bereits etablierten Muster
(ein klassen-level Bean-Validation-Constraint mit dediziertem
`ConstraintValidator`, siehe `ConsistentCalendarSourceFields`s eigene
Begründung: ein im kompakten Konstruktor geworfener Fehler bricht bei der
ersten Verletzung ab, statt mit jeder anderen Verletzung zusammen in einem
einzigen `ConstraintViolationException`-Bericht zu erscheinen) — nur diesmal
auf `RelayProperties` selbst platziert, weil dort beide Seiten der Referenz
(die Liste der Kalender und die Liste der Credential-Sets) gleichzeitig
sichtbar sind:

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ConsistentGoogleCredentialsReferencesValidator.class)
public @interface ConsistentGoogleCredentialsReferences {
    String message() default "google credentials reference inconsistent";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

```java
public class ConsistentGoogleCredentialsReferencesValidator
        implements ConstraintValidator<ConsistentGoogleCredentialsReferences, RelayProperties> {

    @Override
    public boolean isValid(@Nullable RelayProperties properties, ConstraintValidatorContext context) {
        if (properties == null) {
            return true;
        }
        var credentialIds = properties.googleCredentials().stream()
                .map(RelayProperties.GoogleCredentials::id)
                .toList();
        var duplicateIds = findDuplicates(credentialIds);
        var unresolvedCalendars = properties.calendars().stream()
                .filter(calendar -> calendar.type() == CalendarSourceType.GOOGLE)
                .filter(calendar -> calendar.googleCredentialsId() != null)
                .filter(calendar -> !credentialIds.contains(calendar.googleCredentialsId()))
                .toList();

        if (duplicateIds.isEmpty() && unresolvedCalendars.isEmpty()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        duplicateIds.forEach(duplicateId -> context.buildConstraintViolationWithTemplate(
                        "relay.google-credentials contains duplicate id '" + duplicateId + "'")
                .addConstraintViolation());
        unresolvedCalendars.forEach(calendar -> context.buildConstraintViolationWithTemplate(
                        "relay.calendars[" + calendar.id() + "].google-credentials-id '"
                                + calendar.googleCredentialsId() + "' matches no configured "
                                + "relay.google-credentials[].id")
                .addConstraintViolation());
        return false;
    }
}
```

Kernpunkte dieser Design-Entscheidung:

- **Fail-fast am Anwendungsstart, im selben Bericht wie jede andere
  Konfigurationsverletzung** — Spring bindet `RelayProperties` beim Start,
  `@Validated` löst die volle Bean-Validation-Baumprüfung aus (alle
  `@NotBlank`/`@NotNull`-Felder, `@ConsistentCalendarSourceFields` pro
  Kalendereintrag, und jetzt zusätzlich diese neue klassen-level Constraint
  auf `RelayProperties` selbst), gesammelt in einer einzigen
  `ConstraintViolationException`. Kein separater Startup-Check, kein
  `@PostConstruct`, keine Prüfung erst beim ersten tatsächlichen
  OAuth-Token-Austausch — konsistent mit der bereits etablierten
  Fail-Fast-Philosophie dieses Projekts für Konfigurationsfehler.
- **Zwei unterschiedliche Verletzungen in einem Constraint zusammengefasst**
  (unauflösbare Referenz und doppelte `id`), weil beide dieselbe fachliche
  Voraussetzung prüfen — "die `relay.google-credentials[]`-Liste bildet einen
  eindeutigen, vollständig referenzierbaren Namensraum" — und beide dieselbe
  Datengrundlage (`properties.calendars()` und `properties.googleCredentials()`
  gemeinsam) brauchen. Eine Aufspaltung in zwei separate Constraints wäre
  vertretbar, hätte aber keinen zusätzlichen Nutzen, da ohnehin dieselbe
  Klasse (`RelayProperties`) und derselbe Validierungsdurchlauf betroffen
  sind.
- **Die Fehlermeldung nennt sowohl den betroffenen Kalendereintrag (`id`) als
  auch den unauflösbaren Wert** — wichtig, weil ein einzelner
  Konfigurationsfehler bei mehreren `type: google`-Einträgen sonst schwer
  einem konkreten Eintrag zuzuordnen wäre.

## Beispielkonfiguration

### `config/relay-calendars.yml`

```yaml
relay:
  poll-interval: 5m
  recurring-event-horizon: P6M

  organizer-email: ${RELAY_ORGANIZER_EMAIL}
  attendee-email: ${RELAY_ATTENDEE_EMAIL}
  from-address: ${RELAY_FROM_ADDRESS}
  reply-to-address: ${RELAY_REPLY_TO_ADDRESS}

  google-credentials:
    - id: personal-google-account
      client-id: ${GOOGLE_PERSONAL_CLIENT_ID}
      client-secret: ${GOOGLE_PERSONAL_CLIENT_SECRET}
      refresh-token: ${GOOGLE_PERSONAL_REFRESH_TOKEN}

  calendars:
    - id: personal-nextcloud
      caldav-url: https://cloud.example.com/remote.php/dav/calendars/user/personal/
      caldav-username: ${CALDAV_PERSONAL_USERNAME}
      caldav-password: ${CALDAV_PERSONAL_PASSWORD}

    - id: work-nextcloud
      caldav-url: https://cloud.example.com/remote.php/dav/calendars/user/work/
      caldav-username: ${CALDAV_WORK_USERNAME}
      caldav-password: ${CALDAV_WORK_PASSWORD}

    - id: personal-google-primary
      type: google
      google-calendar-id: ${GOOGLE_PERSONAL_CALENDAR_ID}
      google-credentials-id: personal-google-account

    - id: personal-google-secondary
      type: google
      google-calendar-id: ${GOOGLE_SECONDARY_CALENDAR_ID}
      google-credentials-id: personal-google-account
```

Beide `type: google`-Einträge (`personal-google-primary`,
`personal-google-secondary`) referenzieren dasselbe
`personal-google-account`-Credential-Set — genau der Fall, den diese Feature
adressiert: ein Google-Konto, ein OAuth-Client, zwei Kalender desselben
Kontos (der primäre und ein sekundärer `@group.calendar.google.com`-Kalender).
Kein CalDAV-Eintrag und kein Google-Eintrag trägt mehr eigene
`organizer-email`/`attendee-email`/`from-address`/`reply-to-address` — alle
vier gelten global.

### `.env`

```dotenv
# Einheitliche iMIP-Identität -- gilt jetzt für jeden konfigurierten Kalender,
# unabhängig von dessen Typ (vorher: ein Satz pro Kalendereintrag).
RELAY_ORGANIZER_EMAIL=organizer@example.com
RELAY_ATTENDEE_EMAIL=business@example.com
RELAY_FROM_ADDRESS=relay@example.com
RELAY_REPLY_TO_ADDRESS=organizer@example.com

# Zwei CalDAV-Kalender, unverändert gegenüber heute -- weiterhin ein
# Credential-Paar pro Kalendereintrag, da CalDAV-Credentials von dieser
# Feature nicht konsolidiert werden (siehe Out of scope).
CALDAV_PERSONAL_USERNAME=caldav-user
CALDAV_PERSONAL_PASSWORD=changeme-use-a-strong-password
CALDAV_WORK_USERNAME=caldav-work-user
CALDAV_WORK_PASSWORD=changeme-use-a-strong-password

# Ein Google-OAuth-Credential-Set, geteilt von beiden Google-Kalendereinträgen
# unten -- vorher: ein Satz pro Kalendereintrag, jetzt: ein Satz pro
# relay.google-credentials[]-Eintrag.
GOOGLE_PERSONAL_CLIENT_ID=changeme-oauth-client-id
GOOGLE_PERSONAL_CLIENT_SECRET=changeme-oauth-client-secret
GOOGLE_PERSONAL_REFRESH_TOKEN=changeme-refresh-token

# Zwei Kalender-IDs desselben Google-Kontos -- bleibt pro Kalendereintrag
# individuell, unverändert durch diese Feature.
GOOGLE_PERSONAL_CALENDAR_ID=you@gmail.com
GOOGLE_SECONDARY_CALENDAR_ID=team-events@group.calendar.google.com
```

## Migrationshinweis

Diese Feature ist ein bewusster Breaking Change für die eine, bereits
produktive Konfiguration dieses Deployments (siehe "Nicht verhandelbare
Anforderungen" oben). Der Deployer muss beim Upgrade von Hand:

1. **iMIP-Identität konsolidieren.** Aus den bisherigen Per-Kalender-Werten
   (`RELAY_PERSONAL_ORGANIZER_EMAIL`, `RELAY_GOOGLE_ORGANIZER_EMAIL` usw.)
   einen einzigen Wertesatz auswählen — im heutigen produktiven Deployment
   waren diese Werte je nach `.env.example`-Konvention ohnehin meist schon
   identisch über alle Kalender hinweg, sodass hier in der Regel keine
   inhaltliche Entscheidung, nur eine Umbenennung nötig ist — und als
   `RELAY_ORGANIZER_EMAIL`/`RELAY_ATTENDEE_EMAIL`/`RELAY_FROM_ADDRESS`/
   `RELAY_REPLY_TO_ADDRESS` neu in `.env` eintragen.
2. **Die vier Felder aus jedem `relay.calendars[]`-Eintrag in
   `relay-calendars.yml` entfernen** und stattdessen einmal auf
   `relay`-Ebene eintragen (siehe Beispiel oben).
3. **Für jedes bestehende Google-Konto einen `relay.google-credentials[]`-
   Eintrag anlegen**, mit den bisherigen `google-client-id`/
   `google-client-secret`/`google-refresh-token`-Werten dieses Kontos und
   einer frei gewählten, stabilen `id`.
4. **Jeden `type: google`-Kalendereintrag auf `google-credentials-id`
   umstellen**: die drei Credential-Felder aus dem Kalendereintrag entfernen,
   stattdessen `google-credentials-id: <die-oben-vergebene-id>` eintragen.
   `google-calendar-id` und `id` des Kalendereintrags bleiben unverändert.
5. **Keine Datenbankänderung nötig.** `RelayState`, `CalendarReplicaStore`
   und `GoogleCalendarReplicaStore` bleiben ausschließlich über
   `CalendarConfig.id()` (den Persistenz-Schlüssel, siehe README.md) gescopt
   — dieser Schlüssel ändert sich durch diese Feature nicht, also bleibt der
   gesamte bisherige Relay-Zustand nach dem Upgrade gültig und wird beim
   nächsten Poll-Zyklus normal fortgeschrieben.
6. Anwendung neu starten. Jeder Bindungs-/Validierungsfehler aus den
   Schritten 1–4 wird beim Start als vollständiger
   `ConstraintViolationException`-Bericht sichtbar, bevor ein Poll-Zyklus
   läuft (siehe Fehlerfälle unten) — kein stiller Teilausfall.

Eine automatische Migration (z. B. ein Startskript, das eine alte
`relay-calendars.yml` erkennt und in-place umschreibt) ist nicht Teil dieser
Feature — bei genau einem betroffenen Deployment steht der Aufwand einer
automatisierten Migration in keinem Verhältnis zu einer einmaligen manuellen
Anpassung.

## Fehlerfälle

- **Eines der vier globalen `relay.organizer-email`/`attendee-email`/
  `from-address`/`reply-to-address`-Felder fehlt oder ist leer.** Bestehendes
  `@NotBlank`-Verhalten, jetzt auf `RelayProperties`-Ebene statt
  `CalendarConfig`-Ebene — Anwendungsstart bricht mit
  `ConstraintViolationException` ab.
- **Ein `type: google`-Kalendereintrag hat keine oder eine leere
  `google-credentials-id`.** Wie heute schon für die alten drei
  Google-Felder: `@ConsistentCalendarSourceFields` schlägt fehl,
  Anwendungsstart bricht ab.
- **Eine gesetzte `google-credentials-id` referenziert keinen konfigurierten
  `relay.google-credentials[].id`-Eintrag.** Neue
  `@ConsistentGoogleCredentialsReferences`-Verletzung (siehe
  Konfigurationsschema oben), Fehlermeldung nennt sowohl die betroffene
  Kalender-`id` als auch den unauflösbaren Referenzwert. Anwendungsstart
  bricht ab — kein Kalender wird gepollt, auch nicht die übrigen, korrekt
  konfigurierten Einträge (konsistent mit dem bestehenden
  Alles-oder-nichts-Verhalten von `@Validated` bei jedem heutigen
  Konfigurationsfehler).
- **Zwei Einträge unter `relay.google-credentials[]` tragen dieselbe `id`.**
  Dieselbe neue Constraint schlägt ebenfalls fehl, mit einer eigenen, auf die
  doppelte `id` bezogenen Meldung.
- **Ein `type: caldav`-Kalendereintrag trägt versehentlich eine
  `google-credentials-id`.** Wird nicht gesondert validiert — das Feld wird
  für `type: caldav`-Einträge schlicht nie gelesen, exakt wie
  `googleCalendarId` heute schon bei `type: caldav`-Einträgen ignoriert wird.
  Kein neuer Fehlerfall gegenüber dem bestehenden Verhalten.

## Weitere Entscheidungen — eigene Einschätzung

- **`relay.google-credentials[]` ist absichtlich nicht auf mindestens einen
  Eintrag beschränkt.** Eine leere Liste ist gültig — ein Deployment ganz
  ohne `type: google`-Kalender braucht sie nicht, exakt wie eine leere
  `relay.calendars[]`-Liste heute schon für CI/lokale Läufe gültig ist.
- **Ein definierter, aber von keinem Kalendereintrag referenzierter
  `relay.google-credentials[]`-Eintrag ist kein Fehler.** Nur die
  umgekehrte Richtung (eine Referenz auf ein nicht existierendes
  Credential-Set) wird geprüft — ein vorab angelegtes, noch ungenutztes
  Credential-Set ist ein legitimer Zwischenzustand beim schrittweisen
  Konfigurieren mehrerer Kalender.
- **CalDAV-Credentials werden von dieser Feature nicht konsolidiert.** Der
  Auftrag bezog sich ausdrücklich auf Google-OAuth-Credentials; ein
  analoger `relay.caldav-credentials[]`-Mechanismus für mehrere Kalender
  desselben CalDAV-Servers wäre eine eigenständige, hier nicht beauftragte
  Erweiterung (siehe Out of scope).
- **Kein `docs/adr/`-Eintrag in dieser Spec.** Konsistent mit dem bereits
  etablierten Muster dieses Projekts (siehe `delta-sync.md`,
  `google-calendar-integration.md`): eine ADR wird erst nach der
  tatsächlichen Implementierung geschrieben, nicht vorab als Teil des
  Designs.

## Out of scope

- **Per-Kalender-Override der iMIP-Identität.** Explizit nicht gewollt, siehe
  "Nicht verhandelbare Anforderungen" oben.
- **Rückwärtskompatibles duales Binding** (altes Schema mit
  Per-Kalender-Feldern weiterhin zusätzlich zum neuen globalen Schema
  akzeptieren). Bewusster Breaking Change für dieses einzelne Deployment,
  siehe "Nicht verhandelbare Anforderungen" oben.
- **Automatisierte Migration** einer bestehenden `relay-calendars.yml`/`.env`
  vom alten ins neue Schema. Der Deployer migriert von Hand, siehe
  Migrationshinweis oben.
- **Konsolidierung von CalDAV-Credentials** über mehrere Kalender desselben
  CalDAV-Servers hinweg. Nur Google-OAuth-Credentials werden von dieser
  Feature konsolidiert.
- **Validierung der Google-OAuth-Credentials gegen die tatsächliche Google
  API** (z. B. ob `client-id`/`client-secret`/`refresh-token` tatsächlich
  gültig sind). Die neue Validierung prüft ausschließlich die strukturelle
  Auflösbarkeit der Referenz (`google-credentials-id` → bekannte
  `relay.google-credentials[].id`), nicht die Gültigkeit der referenzierten
  Werte selbst — ein ungültiger, aber auflösbarer Refresh-Token scheitert
  weiterhin erst beim ersten tatsächlichen Token-Austausch, exakt wie heute
  schon in `docs/features/google-calendar-integration.md`s Fehlerfällen
  beschrieben.
- **Änderungen an `core/domain`, `core/app`, `CalendarSource`,
  `GoogleCalendarReplicaStore`, `CalendarReplicaStore`, `BlockerSink`,
  `StateStore` oder jedem Adapter außer der Konstruktion in
  `RelayWiringConfiguration`.** Alle bleiben unangetastet — siehe "Domain
  model additions"/"Port additions" oben.

## Open questions

- **Eindeutigkeitsprüfung für `relay.google-credentials[].id` — Verhalten bei
  Implementierung noch nicht gegen einen bestehenden Präzedenzfall
  abgesichert.** Diese Spec entscheidet sich dafür, eine doppelte `id` als
  Validierungsfehler zu behandeln (siehe Konfigurationsschema), aber dieses
  Projekt hat bislang keine vergleichbare
  Eindeutigkeits-innerhalb-einer-Liste-Prüfung (auch `relay.calendars[].id`
  wird heute nirgends auf Eindeutigkeit geprüft) — zu bestätigen, ob dasselbe
  Prinzip perspektivisch auch rückwirkend auf `relay.calendars[].id`
  angewendet werden soll, oder ob diese Feature bewusst nur für den neuen
  `google-credentials`-Namensraum gilt.
- **Granularität der `.env`-Variablen für die globale iMIP-Identität.** Diese
  Spec nimmt vier separate Variablen an
  (`RELAY_ORGANIZER_EMAIL`/`RELAY_ATTENDEE_EMAIL`/`RELAY_FROM_ADDRESS`/
  `RELAY_REPLY_TO_ADDRESS`), konsistent mit der bestehenden
  Ein-Variable-pro-Feld-Konvention (`RELAY_POLL_INTERVAL` usw.) — nicht
  explizit vom Auftraggeber bestätigt, aber die naheliegende Fortsetzung des
  etablierten Musters.
- **Ob `docs/technical/google-calendar-setup.md` im Rahmen derselben
  Implementierungs-PR oder eines separaten `tech-documenter`-Laufs
  aktualisiert wird**, um den jetzt möglichen "ein Setup-Durchlauf pro
  Google-Konto, nicht pro Kalender"-Ablauf widerzuspiegeln — mirrort dieselbe,
  bereits in `docs/features/google-calendar-integration.md` offen gelassene
  Frage.
