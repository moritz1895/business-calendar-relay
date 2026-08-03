# Google Calendar Setup (`GoogleCalendarSourceAdapter`)

Schritt-für-Schritt-Anleitung für den **Deployer**, um einen neuen
`type: google`-Eintrag in `relay.calendars` produktiv zu nehmen. Dieses
Dokument beschreibt ausschließlich das **Wie** — Design-Rationale (warum ein
eigener `GoogleCalendarReplicaStore`-Port, warum `singleEvents=true` statt
eigener RRULE-Expansion, warum kein automatischer Refresh-Token-Erneuerungs-
Mechanismus) steht in
[`docs/features/google-calendar-integration.md`](../features/google-calendar-integration.md)
und wird hier nicht wiederholt.

## Voraussetzungen

- Ein Google-Cloud-Projekt, das der Deployer selbst anlegt und besitzt (kein
  von diesem Projekt bereitgestelltes Projekt).
- Die **Google Calendar API** in diesem Projekt aktiviert.
- Ein selbst angelegter **OAuth-2.0-Client** in genau diesem Projekt.
- Ein persönliches (Nicht-Workspace-)Gmail-Konto als Quellkalender. Google-
  Workspace-Domain-Wide-Delegation/Service-Accounts sind nicht unterstützt
  (siehe Spec, „Weitere Entscheidungen“).

## Schritt-für-Schritt: OAuth-Consent-Flow

Dieser Ablauf wird **einmal pro Google-Quellkalender** außerhalb der
laufenden Anwendung durchgeführt und liefert am Ende genau einen Wert, der in
die Konfiguration übernommen wird: `google-refresh-token`.

### 1. Google Calendar API aktivieren

In der [Google Cloud Console](https://console.cloud.google.com/): Projekt
auswählen oder neu anlegen, dann **APIs & Services → Library** → nach
„Google Calendar API“ suchen → **Enable**.

### 2. OAuth-2.0-Client anlegen

**APIs & Services → Credentials → Create Credentials → OAuth client ID**.
Typ „Desktop app“ oder „Web application“ — bei „Web application“ muss unter
„Authorized redirect URIs“ genau
`https://developers.google.com/oauthplayground` eingetragen werden (der
Redirect-URI, den der OAuth Playground im nächsten Schritt verwendet).
Client-ID und Client-Secret aus der Bestätigungsseite notieren — das sind
später `google-client-id`/`google-client-secret`.

### 3. Publishing-Status auf „In production“ setzen

**APIs & Services → OAuth consent screen**. Solange der Publishing-Status
„Testing“ ist, läuft jeder ausgestellte Refresh-Token bereits nach **7
Tagen** ab (zusätzlich ein Limit von 100 Test-Nutzern) — für einen headless
Hintergrund-Poller ohne interaktiven Re-Consent-Mechanismus inakzeptabel.
Über den Button **„Publish App“** auf „In production“ umschalten: ein
einzelner, kostenloser Klick, **keine** formale Google-Verifizierungsprüfung
erforderlich für den hier verwendeten `calendar.readonly`-Scope in diesem
Setup. Danach lebt ein ausgestellter Refresh-Token unbegrenzt (bis Widerruf
oder ca. 6 Monate Inaktivität, siehe „Betrieb: Fehlerfälle“ unten).

### 4. OAuth Playground mit eigenen Credentials konfigurieren

Auf [developers.google.com/oauthplayground](https://developers.google.com/oauthplayground):
oben rechts auf das **Zahnrad-Icon** klicken → **„Use your own OAuth
credentials“** aktivieren → Client-ID und Client-Secret aus Schritt 2
eintragen.

### 5. Scope auswählen und autorisieren

Im linken Bereich („Step 1 — Select & authorize APIs“) manuell den Scope

```
https://www.googleapis.com/auth/calendar.readonly
```

eintragen (bewusst nur lesend, nicht `.../auth/calendar` — siehe Spec) und
**Authorize APIs** klicken. Beim folgenden Google-Login erscheint einmalig
der Warnbildschirm „Google hat diese App nicht verifiziert“ — über
**Advanced → „Go to [App-Name] (unsafe)“** bestätigen, dann den
angeforderten Lesezugriff gewähren.

### 6. Authorization Code gegen Tokens tauschen

Zurück im Playground, „Step 2 — Exchange authorization code for tokens“ →
**„Exchange authorization code for tokens“** klicken. Die Antwort enthält
`refresh_token`, `access_token`, `expires_in`, `scope`, `token_type` — nur
`refresh_token` wird gebraucht. Diesen Wert einmalig in die Konfiguration
als `GOOGLE_..._REFRESH_TOKEN` übernehmen (siehe „Konfigurationsreferenz“
unten). Der `access_token` aus dieser Antwort wird nirgends verwendet — die
laufende Anwendung tauscht selbst frische Access-Tokens gegen den
Refresh-Token, siehe „Laufzeitverhalten“.

### `google-calendar-id` ermitteln

Für den primären Kalender eines persönlichen Kontos ist das schlicht die
Gmail-Adresse selbst (`you@gmail.com`). Für einen sekundären Kalender:
Google Calendar → Zahnrad → **Einstellungen** → den betreffenden Kalender
auswählen → Abschnitt **„Kalender integrieren“** → dort steht die
`google-calendar-id` (endet meist auf `@group.calendar.google.com`).

## Ein OAuth-Setup-Durchlauf pro Google-Konto, nicht pro Kalender

**Seit `docs/features/relay-config-consolidation.md` gilt:** Die Schritte 1–6
oben (OAuth-Client anlegen, Publishing-Status umschalten, Playground-Consent,
`refresh_token` beschaffen) führt der Deployer weiterhin **einmal pro
Google-Konto** aus — das ändert sich nicht. Was sich ändert: das Ergebnis
dieses Durchlaufs (`client-id`/`client-secret`/`refresh-token`) wird nicht
mehr pro Kalendereintrag kopiert, sondern **einmal** unter
`relay.google-credentials[]` benannt (siehe „Konfigurationsreferenz“ unten).
Kommt ein zweiter Kalender **desselben** Google-Kontos hinzu (z. B. ein
sekundärer `@group.calendar.google.com`-Kalender), genügt ein weiterer
`relay.calendars[]`-Eintrag mit `type: google`, einer neuen
`google-calendar-id` und derselben, bereits vorhandenen
`google-credentials-id` — **kein** erneuter OAuth-Playground-Durchlauf nötig.
Ein neuer OAuth-Setup-Durchlauf ist nur für ein tatsächlich neues
Google-**Konto** (oder einen neuen OAuth-Client) erforderlich.

## Konfigurationsreferenz

`google-credentials-id`/`google-calendar-id`/`type` sind pro
`relay.calendars[]`-Eintrag mit `type: google` konfigurierbar;
`client-id`/`client-secret`/`refresh-token` sind pro
`relay.google-credentials[]`-Eintrag konfigurierbar, einmal pro
Google-Konto, geteilt von beliebig vielen `type: google`-Kalendereinträgen
— Namen exakt wie in `.env.example` und `config/relay-calendars.yml.example`
in diesem Repository:

| yml-Feld (`relay.calendars[]`) | Env-Var im Beispielblock | Bedeutung |
|---|---|---|
| `type` | – (Literal `google`) | Diskriminator; ohne dieses Feld bindet ein Eintrag auf `caldav` |
| `google-calendar-id` | `GOOGLE_PERSONAL_CALENDAR_ID` | siehe „`google-calendar-id` ermitteln“ oben |
| `google-credentials-id` | – (Literal, z. B. `personal-google-account`) | Referenziert einen `relay.google-credentials[].id`-Eintrag (siehe unten). Unauflösbare Referenzen brechen den Anwendungsstart ab. |
| `delta-sync-enabled` | – (optional, Default `true`) | manueller Notausschalter, fällt bei `false` auf einen stets vollständigen `events.list`-Request ohne `syncToken` zurück |

| yml-Feld (`relay.google-credentials[]`) | Env-Var im Beispielblock | Bedeutung |
|---|---|---|
| `id` | – (Literal, z. B. `personal-google-account`) | Frei wählbarer, stabiler Bezeichner, referenziert von `relay.calendars[].google-credentials-id`. Muss innerhalb der Liste eindeutig sein. |
| `client-id` | `GOOGLE_PERSONAL_CLIENT_ID` | aus Schritt 2 |
| `client-secret` | `GOOGLE_PERSONAL_CLIENT_SECRET` | aus Schritt 2 |
| `refresh-token` | `GOOGLE_PERSONAL_REFRESH_TOKEN` | aus Schritt 6 — wie ein CalDAV-Passwort, nie im Klartext einchecken |

Die globale iMIP-Identität (`organizer-email`/`attendee-email`/
`from-address`/`reply-to-address`) gilt seit `relay-config-consolidation.md`
einheitlich auf `relay`-Ebene für jeden konfigurierten Kalender, nicht mehr
pro `type: google`-Eintrag — siehe README.md „Konfiguration“.

Vollständiges Beispiel (`config/relay-calendars.yml`, Werte referenziert aus
`.env`) — zwei Kalender desselben Google-Kontos, ein geteiltes
Credential-Set:

```yaml
relay:
  google-credentials:
    - id: personal-google-account
      client-id: ${GOOGLE_PERSONAL_CLIENT_ID}
      client-secret: ${GOOGLE_PERSONAL_CLIENT_SECRET}
      refresh-token: ${GOOGLE_PERSONAL_REFRESH_TOKEN}

  calendars:
    - id: personal-google-primary
      type: google
      google-calendar-id: ${GOOGLE_PERSONAL_CALENDAR_ID}
      google-credentials-id: personal-google-account

    - id: personal-google-secondary
      type: google
      google-calendar-id: ${GOOGLE_SECONDARY_CALENDAR_ID}
      google-credentials-id: personal-google-account
```

Passender `.env`-Block (siehe `.env.example` für die vollständige Vorlage):

```
GOOGLE_PERSONAL_CLIENT_ID=changeme-oauth-client-id
GOOGLE_PERSONAL_CLIENT_SECRET=changeme-oauth-client-secret
GOOGLE_PERSONAL_REFRESH_TOKEN=changeme-refresh-token
GOOGLE_PERSONAL_CALENDAR_ID=you@gmail.com
GOOGLE_SECONDARY_CALENDAR_ID=team-events@group.calendar.google.com
```

`id` (hier `personal-google-primary`/`personal-google-secondary`) ist der
Persistenz-Schlüssel für den jeweiligen Kalender — wie bei jedem
CalDAV-Eintrag nach dem ersten Relay-Lauf niemals umbenennen (siehe
README.md).

Ein `type: google`-Eintrag koexistiert im selben `relay.calendars` mit
beliebig vielen `type: caldav`-Einträgen; alle werden vom selben
`PollAndRelaySchedulerAdapter` unabhängig im konfigurierten
`relay.poll-interval` gepollt.

## Laufzeitverhalten

Kurz zusammengefasst — volle Herleitung in der Spec (Design-Entscheidung 2–4):

- **Access-Token-Erneuerung, vollständig autonom.** Bei jedem `readEvents()`-
  Aufruf tauscht `GoogleCalendarSourceAdapter` den konfigurierten,
  statischen `google-refresh-token` gegen einen kurzlebigen `access_token`,
  sobald kein noch gültiger Token im Speicher gecacht ist
  (`POST https://oauth2.googleapis.com/token`,
  `grant_type=refresh_token`) — keine Nutzerinteraktion, kein neuer
  `refresh_token` in der Antwort.
- **Delta-Sync plus horizon-begrenzter Ergänzungs-Fetch.** Ist
  `delta-sync-enabled` (Default `true`), pflegt der Adapter eine lokale
  Replik über `GoogleCalendarReplicaStore` anhand des `syncToken`-Deltas von
  `events.list` und führt zusätzlich bei jedem Zyklus einen zweiten, eng auf
  `[now, now + recurring-event-horizon]` begrenzten Request aus, damit eine
  unveränderte, aber unbegrenzt wiederkehrende Serie trotzdem neue Vorkommen
  offenbart, sobald das Horizont-Fenster fortschreitet.

## Betrieb: Fehlerfälle

| Fehler | Bedeutung | Aktion |
|---|---|---|
| Token-Exchange antwortet `400 invalid_grant` | Der Refresh-Token wurde extern widerrufen, ist nach ca. 6 Monaten Inaktivität abgelaufen, oder wurde durch einen neueren Consent-Grant für dieselbe Client-ID+Scope-Kombination still invalidiert. | **Manuell**: den OAuth-Consent-Flow (Schritte 4–6 oben) erneut durchlaufen, den neuen `google-refresh-token`-Wert in die Konfiguration einspielen und den Dienst neu starten — analog zu einem abgelaufenen CalDAV-Passwort. Kein automatischer Wiederherstellungsmechanismus. |
| `events.list` antwortet `410 Gone` | Googles Signal für einen ungültig gewordenen `syncToken`. | **Keine Aktion nötig.** Der Adapter löst automatisch einen vollständigen Resync aus (frischer `syncToken` ohne Delta-Parameter, Replik wird per `resetTo(...)` ersetzt) — kein Datenverlust. |
| `events.list`/Token-Exchange antwortet `403`/`429` | Transiente Fehler, typischerweise Quota-Überschreitung. | **Keine Aktion nötig.** Nur der aktuelle Poll-Zyklus scheitert; der nächste Zyklus versucht es mit demselben, weiterhin gültigen `syncToken`/Refresh-Token erneut. Kein automatischer Backoff über `relay.poll-interval` hinaus. Bei anhaltenden `429` lohnt ein Blick in **APIs & Services → Quotas** im Google-Cloud-Projekt. |

Alle drei Fälle resultieren im Log als `GoogleCalendarSourceException`
(strukturell parallel zu `CalDavCalendarSourceException`); siehe
[`docs/technical/scheduling.md`](scheduling.md) für die Behandlung eines
fehlgeschlagenen Poll-Zyklus.

## Siehe auch

- [`docs/features/google-calendar-integration.md`](../features/google-calendar-integration.md)
  — vollständige Design-Rationale (Port-Entscheidung, Konfigurationsschema,
  `sourceUid`-Mapping, Open Questions). Ihr Konfigurationsschema (ein
  vollständiges Credential-Tripel pro Kalendereintrag) ist durch
  `relay-config-consolidation.md` (siehe unten) inzwischen überholt.
- [`docs/features/relay-config-consolidation.md`](../features/relay-config-consolidation.md)
  — die aktuelle Design-Rationale für `relay.google-credentials[]` und die
  globale iMIP-Identität, auf der die Konfigurationsreferenz oben beruht.
- [`docs/technical/caldav.md`](caldav.md), [`docs/technical/smtp.md`](smtp.md)
  — die beiden anderen protokollspezifischen technischen Dokumente dieses
  Projekts.
