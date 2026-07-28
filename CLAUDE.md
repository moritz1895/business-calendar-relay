# CLAUDE.md — business-calendar-relay

Project-specific guidance. Extends `~/CLAUDE.md` (architecture rules, Java/Spring
standards, agent workflow, git workflow) — that file is authoritative and is not
repeated here.

## Goal

Watch one or more private CalDAV calendars and mirror their events as titleless
blocker appointments into a business Outlook calendar. The transport is iMIP:
this service sends `METHOD:REQUEST`/`METHOD:CANCEL` iCal mail to the business
address; Outlook renders the invitation natively. No Outlook-side API, no Graph
integration — plain SMTP with a `text/calendar` MIME part.

## Domain vocabulary

- **Source event** — a VEVENT read from a private CalDAV calendar.
- **Blocker** — the mirrored appointment as it exists in the business calendar.
  Titleless by design; only busy/free time matters, not the reason.
- **Relay** — the mapping from one source event's `UID` to the iMIP `UID` used
  for its blocker, kept stable across create/update/cancel so Outlook treats
  them as the same appointment.

## Ports (hexagonal)

- `CalendarSource` (outbound) — reads events from a CalDAV calendar. One
  configured instance per source calendar.
- `BlockerSink` (outbound) — sends iMIP mail (SMTP) representing a blocker
  create/update/cancel.
- `StateStore` (outbound) — persists the relay mapping (source UID → blocker
  UID/SEQUENCE) and, later, CalDAV sync tokens for `sync-collection` deltas.

Application layer orchestrates: poll `CalendarSource` → diff against
`StateStore` → emit the right iMIP method via `BlockerSink` → update
`StateStore`.

## Reference findings (docs/reference/*.eml)

Three Nextcloud-generated iMIP mails were captured and their target Outlook
behavior manually verified — this is the acceptance baseline, not just sample
data:

- **Initial invite** lands as an unconfirmed blocker immediately; no Outlook
  reply/accept is needed to occupy the slot.
- **Update** moves the same blocker; no duplicate is created.
- **Cancel** marks the appointment as cancelled in Outlook; actual deletion
  stays a manual step. This is accepted, intentional behavior — do not build
  automatic deletion or any workaround for it.

Structural findings driving the generator:

- MIME shape: `multipart/mixed` > (`multipart/alternative` [text/plain +
  text/html] , `text/calendar`). The calendar part is a sibling of the
  human-readable alternative, not nested inside it.
- The `text/calendar` part: `Content-Type: text/calendar; method=REQUEST|CANCEL;
  charset="utf-8"; name=event.ics`, `Content-Transfer-Encoding: base64`,
  `Content-Disposition: inline; name=event.ics; filename=event.ics`. This exact
  disposition (inline + filename set) is what makes Outlook render an
  invitation card instead of a file attachment.
- iMIP identity: same `UID` across invite/update/cancel for one source event;
  `SEQUENCE` strictly increases on every REQUEST/CANCEL resend (never reuse or
  decrement).
- Minimum VEVENT properties Outlook needs to auto-block without a reply:
  `STATUS:CONFIRMED`, `ATTENDEE;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE`,
  `ORGANIZER`, `DTSTAMP`, `DTSTART`/`DTEND` with `TZID`.
- Cancel keeps `STATUS:CONFIRMED` on the VEVENT — the cancel semantics live
  entirely in `METHOD:CANCEL` at VCALENDAR level, not in a VEVENT status flag.
  The ATTENDEE line on cancel drops `PARTSTAT`/`ROLE`/`RSVP`, keeping only
  `CN` + `mailto`.
- A full `VTIMEZONE` block for `Europe/Berlin` is present in every message;
  reproduce it (or an equivalent) rather than relying on `Z`/UTC-only times,
  since the reference mails never do.
- `From`/envelope-from match exactly, and `Reply-To` is set to the organizer's
  human address — replicate this pairing so SPF stays green and Outlook shows
  a sensible reply target.

None of the reference mails contain `VALARM` or `RECURRENCE-ID` — the captured
scenarios are single-occurrence events. `VALARM` remains out of scope.
Recurring source events were revisited and are now handled (Issue #3, see
`docs/features/event-filtering.md`): `RRULE` is expanded, `EXDATE` and
`RECURRENCE-ID` overrides are resolved at the `CalDavCalendarSourceAdapter`
boundary, and each occurrence gets a composite `sourceUid`
(`<seriesUid>#<original occurrence instant>`) so a moved occurrence tracks as
an update rather than a duplicate.

## Module descriptor

`module-info.java` is present per the global null-safety rule (`@NullMarked`
at module level) and compiles as a real, fully resolved module — every
`requires` must stay accurate as dependencies are added. It is declared
**open** so reflection-heavy Spring machinery (proxies, `@ConfigurationProperties`
binding, etc.) isn't blocked by strong encapsulation. It has no practical
effect at runtime: the Spring Boot repackaged jar is launched via
`java -jar`, which runs `JarLauncher` on the plain classpath and never
invokes `--module-path`, so JPMS isolation never actually engages. Treat the
descriptor as compiled documentation of the module's dependencies and
nullability intent — real module boundaries matter for libraries (like
`hexagonal-arch` itself), not for this application.

## Configuration & credentials

- Any number of source calendars, declared in one config file (CalDAV URL,
  calendar path, principal) — not one-config-per-calendar.
- Credentials never in code or committed config: environment variables for
  now, `.env` (git-ignored) later. No secret ever lands in `docs/reference/`
  or test fixtures either — the captured .eml files are already sanitized of
  real credentials and must stay that way.

## Deliberately deferred

- **Delta detection**: full poll-and-diff first; CalDAV `sync-collection`
  (RFC 6578) sync-token support comes later once the basic relay works
  end-to-end. Still deferred — current next feature on the roadmap.
- ~~**Filtering logic**~~ Done (Issue #3, `docs/features/event-filtering.md`):
  a creation-eligibility gate (past-cutoff, all-day exclusion, transparent/
  free exclusion, `STATUS:CANCELLED` exclusion, a configurable forward
  sliding window for recurring events via `relay.recurring-event-horizon`)
  decides which source events become blockers, applied only to first-time
  creation — it never retroactively cancels an already-active blocker.
  Title/content scrubbing remains out of scope: `SourceEvent` still carries
  no `SUMMARY`/`DESCRIPTION`, by design (see `docs/domain.md`).

## Test fixtures

`docs/reference/*.eml` are the golden reference captures (Nextcloud-generated,
Outlook-verified behavior). Structural tests for the generated iMIP output
should assert against the ICS properties extracted from these files, not
against the surrounding Nextcloud HTML/text template — that template is
Nextcloud's, not ours, and this service's output does not need to match it.
