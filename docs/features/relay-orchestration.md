# Feature: Relay Orchestration

## Feature summary

The relay orchestration is the application-layer heartbeat of this service: on
each invocation, for one configured private source calendar, it reads the
calendar's current events, compares them against what was mirrored last time,
and sends the minimum set of iMIP messages needed to bring the business
calendar's blockers back in sync — creating new blockers, moving changed
ones, and cancelling ones whose source event disappeared. It owns the
create/update/cancel decision and the `SEQUENCE` bookkeeping that makes
Outlook treat a source event's blocker as one continuous appointment across
its lifetime, rather than a new invitation every time. It does not talk to
CalDAV, SMTP, or a database directly — it drives three outbound ports
(`CalendarSource`, `BlockerSink`, `StateStore`) and the two domain building
blocks that already exist (`BlockerEvent`, `ImipCalendarRenderer`).

## Actors

- **Scheduler** — the only actor. Some future adapter (a Spring
  `@Scheduled` job, a manual trigger endpoint, whatever is chosen later)
  invokes the use case on a timer, once per configured source calendar. There
  is no interactive human actor in this flow; the human effect of this
  feature is indirect, visible only as blockers appearing/moving/cancelling
  in the business Outlook calendar.

## Domain model additions

These are small, dependency-free records read/written across the new ports.
They live in `core/domain/` alongside `BlockerEvent` and
`ImipCalendarRenderer`, since the ports and application service both need a
shared vocabulary for them.

### `SourceEvent`

A CalDAV VEVENT as read from the private source calendar, before it has any
relay identity.

- `sourceUid` — the CalDAV `UID` of the source VEVENT. Scoped to the source
  calendar; distinct namespace from the blocker's own `UID`.
- `start`, `end` — the occurrence's time window, same shape as
  `BlockerEvent.start`/`end` (zoned, same invariants: `end` after `start`,
  same zone on both).

`SourceEvent` deliberately carries nothing else — no summary, no description,
no organizer/attendee. Blockers are titleless by design (see
`ImipCalendarRenderer`'s fixed `SUMMARY:Privater Blocker`), and filtering
logic is explicitly deferred, so the only source-event facts this feature
ever needs are identity and time window. This keeps `CalendarSource` from
ever having to expose calendar content it will never use — a privacy
property that falls out of the design rather than needing a scrubbing step.

### `RelayState`

One source event's last-known relay state, as persisted by `StateStore`.

- `sourceUid` — the source event this state is for (key).
- `blockerUid` — the stable `UID` used for this source event's blocker
  across its whole lifetime (create → updates → cancel). Distinct from
  `sourceUid`; see the "relay UID generation" decision below.
- `sequence` — the last `SEQUENCE` value sent for `blockerUid`, i.e. the
  value already rendered into the most recently sent iMIP message.
- `lastKnownStart`, `lastKnownEnd` — the source event's time window as of
  the last successful send, used as the change-detection baseline on the
  next poll.
- `active` — `true` while the source event is still present and un-cancelled;
  `false` once a `CANCEL` has been sent for it. See "what happens to a
  cancelled entry" below for why this is a flag and not a deletion.

## Port additions

All three are outbound ports (`ports/outbound/`). Signatures below are
conceptual — exact Java shape (interface vs. functional method, exception
types, etc.) is the coder agent's call.

### `CalendarSource`

One configured instance per source calendar (matches the existing
`CLAUDE.md` description). Read-only.

- `readEvents()` → the full current set of `SourceEvent`s in the configured
  calendar, as of now. Always a full read, never a delta — `sync-collection`
  token-based delta sync is explicitly deferred, so this feature's diff
  logic must work correctly against a full snapshot every time.

That is the entire contract for this feature. Nothing else is needed from
CalDAV at the orchestration level.

### `BlockerSink`

One configured instance, shared across all source calendars (it always sends
to the same business mailbox), though nothing in this feature prevents
per-calendar configuration later.

- `send(BlockerMail)` — sends one rendered iMIP message. `BlockerMail` bundles
  everything the adapter needs to build the MIME message described in
  `CLAUDE.md`'s reference findings, without the orchestration layer knowing
  MIME structure:
  - `icsText` — the rendered `BEGIN:VCALENDAR...END:VCALENDAR` string from
    `ImipCalendarRenderer`.
  - `method` — `REQUEST` or `CANCEL`. Needed separately from `icsText`
    because the adapter must set it both in the ICS body (already present,
    via `METHOD:` in the rendered text) and in the MIME
    `Content-Type: text/calendar; method=...` parameter — the reference
    findings show both must agree.
  - `fromAddress`, `replyToAddress` — the sending identity. Per the reference
    findings, `From`/envelope-from must match exactly and `Reply-To` is the
    organizer's human address, so the adapter needs both explicitly rather
    than deriving one from the other.
  - `toAddress` — the business Outlook mailbox the blocker mail goes to.
- Failure is signalled by throwing (an unchecked exception type owned by
  this port); there is no return value to inspect for partial success. A
  single `send` call either fully succeeds or the orchestration treats it as
  failed — see the partial-failure decision below for how one failed send
  is isolated from the rest of a poll cycle.

`BlockerSink` does not see `BlockerEvent` or any domain event type — it only
sees rendered text plus mail metadata. This keeps it a pure "send this mail"
port; the decision of what event that mail represents was already made
upstream.

### `StateStore`

One configured instance per source calendar, mirroring `CalendarSource`'s
one-instance-per-calendar shape. This keeps every call site free of an
explicit calendar-id parameter and matches how the application service
itself is scoped (see below).

- `loadAll()` → every `RelayState` known for this source calendar, active and
  cancelled alike. Read once at the start of each poll cycle and used as the
  diff baseline; the orchestration never queries per-UID mid-cycle.
- `save(RelayState)` — upserts one relay state, keyed by `sourceUid`. Used
  after a successful create or update send, with `active = true`.
- `markCancelled(sourceUid, sequence)` — records that a `CANCEL` was sent at
  the given `sequence` for this `sourceUid`; the stored `blockerUid` is
  unchanged, `active` becomes `false`. Deliberately not a delete — see below.

**Why `save`/`markCancelled` are split instead of one generic upsert:** the
create/update path always carries a fresh time window (`lastKnownStart`/`End`)
alongside the state change, while the cancel path never does — there is no
new time window to record once a source event is gone. Two narrower methods
keep each call site from having to pass irrelevant or duplicated data.

**Enforcing the `SEQUENCE` invariant through this contract:** `CLAUDE.md`'s
reference findings require `SEQUENCE` to strictly increase and never reset
for a given blocker `UID`. This feature makes `StateStore` the single source
of truth for "the last `SEQUENCE` sent" — the orchestration never computes a
next sequence number from anywhere but the value it just read via `loadAll`,
plus one. As long as `loadAll` is read once at the top of a cycle and every
subsequent `save`/`markCancelled` in that same cycle is derived from that
snapshot (never from a hardcoded or re-guessed value), the invariant holds
by construction, independent of whatever storage mechanism the adapter ends
up using.

## Use case: Poll and Relay Source Calendar

### Actor

Scheduler (see above).

### Goal

Bring the business calendar's blockers for one source calendar back in sync
with that source calendar's current events, sending only the iMIP messages
needed to reflect what changed since the last poll.

### Pre-conditions

- Exactly one source calendar is configured for this use case instance
  (multi-calendar wiring — running one instance of this use case per
  configured calendar — is an application-configuration concern outside this
  spec).
- The use case instance has been configured with: the organizer email
  address and business attendee (mailbox) address to put on every
  `BlockerEvent` it builds, and the `fromAddress`/`replyToAddress` to put on
  every `BlockerMail` it sends. `CLAUDE.md`'s existing configuration table
  only documents SMTP transport settings, not these identity values — this
  spec treats them as additional inputs to this use case's configuration,
  their exact config-file shape being the same deferred concern as
  multi-calendar wiring.
- A source for "now" (e.g. a clock) is available to the use case, since
  `ImipCalendarRenderer.renderRequest`/`renderCancel` both require a
  `generatedAt` timestamp for `DTSTAMP` that this layer, not the domain
  service, is responsible for supplying.

### Command

One poll cycle takes no event-specific input — it is a trigger, not a
request about a particular event:

- *(no fields — invocation itself is the command; the source calendar,
  identity addresses, and clock are use-case configuration, not per-call
  parameters)*

### Main flow

1. Read `currentEvents` — the full current set of `SourceEvent`s — via
   `CalendarSource.readEvents()`.
2. Read `priorStates` — every known `RelayState` for this source calendar —
   via `StateStore.loadAll()`, indexed by `sourceUid`.
3. For each `sourceUid` present in `currentEvents`:
   1. **Not in `priorStates` → create.** Generate a new `blockerUid`
      (randomly, independent of `sourceUid` — see decision below), set
      `sequence = 0`, build a `BlockerEvent` from the current event's
      window plus the configured organizer/attendee addresses, render it
      with `ImipCalendarRenderer.renderRequest`, send it via
      `BlockerSink.send` with `method = REQUEST`. On success, `save` a new
      `RelayState { sourceUid, blockerUid, sequence: 0, lastKnownStart,
      lastKnownEnd, active: true }`.
   2. **In `priorStates`, `active`, window changed → update.** "Changed"
      means `start` or `end` differs from `lastKnownStart`/`lastKnownEnd`;
      no other field is ever compared, since `SourceEvent` carries nothing
      else. Reuse the stored `blockerUid`, set `sequence = prior.sequence +
      1`, build and render a `REQUEST` the same way as create, send it. On
      success, `save` the updated `RelayState` with the new sequence and
      window.
   3. **In `priorStates`, `active`, window unchanged → no-op.** Nothing is
      rendered or sent. Resending an identical `REQUEST` would not change
      Outlook's state and would only add mail traffic and needless
      `SEQUENCE` churn, so this case is intentionally a skip, not a resend.
   4. **In `priorStates`, not `active` (previously cancelled), event present
      again → treated as update.** This is not special-cased logic; it falls
      out of keeping cancelled `RelayState` entries around (see below) and
      comparing against `lastKnownStart`/`End` regardless of the `active`
      flag. The next `REQUEST` reuses the same `blockerUid` and continues
      the `sequence` count rather than starting a fresh blocker, which is
      the only choice consistent with "one blocker `UID` per source event
      for its whole lifetime."
4. For each `sourceUid` present in `priorStates` as `active` but absent from
   `currentEvents` → **cancel.** Reuse the stored `blockerUid`, set
   `sequence = prior.sequence + 1`, build the corresponding `BlockerEvent`,
   render it with `ImipCalendarRenderer.renderCancel`, send it via
   `BlockerSink.send` with `method = CANCEL`. On success, `markCancelled`
   with the new sequence.
5. Return a result summarizing the cycle (see Result below).

### What happens to a cancelled entry's `StateStore` record

It is kept, marked `active = false`, not deleted. Two reasons, both tied
to invariants this project already committed to:

- **`SEQUENCE` correctness.** If the entry were deleted and the same
  `sourceUid` reappeared later, the only correct options would be to either
  remember the last `sequence` somewhere else (duplicating the state this
  record already holds) or restart at `0` under a new `blockerUid` — but
  restarting means a second, independent invitation lands in Outlook next to
  the cancelled one instead of reviving it, which is a duplicate blocker,
  something `CLAUDE.md`'s reference findings explicitly say must not happen
  for updates and has no reason to be acceptable for a resurrection either.
  Keeping the record is the only way to guarantee `SEQUENCE` never resets.
- **Matches the project's own cancel semantics.** `CLAUDE.md` already
  decided that a cancelled blocker's *removal from Outlook* stays a manual,
  intentional step rather than something this service automates. Deleting
  the `StateStore` record the moment a cancel is sent would be inconsistent
  with that stance — the relay's bookkeeping would forget the appointment
  before its Outlook-side trace is actually gone.

### Error flows

- **`CalendarSource.readEvents()` fails.** Nothing has been sent or stored
  yet, so the whole cycle aborts and the failure propagates to the caller
  unchanged. There is no partial state to reconcile in this case.
- **`StateStore.loadAll()` fails.** Same as above — abort, nothing sent yet.
- **`BlockerSink.send(...)` fails for one event's create/update/cancel.**
  See "Partial-failure handling" below — this is the one failure mode this
  feature handles as continue-and-report rather than abort.
- **`StateStore.save`/`markCancelled` fails after a successful send.** Out
  of scope for this feature to handle specially: `StateStore`'s persistence
  reliability is explicitly deferred (see Out of scope). The consequence —
  next poll re-derives a stale baseline and may resend a `REQUEST` that
  Outlook already effectively has — is a known, accepted gap the future
  `StateStore` adapter's reliability determines the likelihood of, not
  something this orchestration logic compensates for.

### Result

A summary of what the cycle did, since the caller (scheduler adapter, or a
human triggering it manually) has no other way to observe outcomes:

- `created`, `updated`, `cancelled` — counts or lists of `sourceUid`s
  successfully processed in each category.
- `failed` — list of `(sourceUid, cause)` pairs for events whose send
  failed (see below); everything in this list was left untouched in
  `StateStore` and will be retried as the same create/update/cancel
  decision on the next poll.

## Partial-failure handling

**Decision: a single `BlockerSink.send` failure is isolated to that one
source event; the rest of the poll cycle continues.** The cycle does not
abort on the first failure, and it does not retry within the cycle either —
a failed event is simply left out of that cycle's successes and reported in
`failed`.

**Justification, against `CLAUDE.md`'s "full poll-and-diff first, keep it
simple" stance:**

- **Continuing is not extra complexity — it's less.** Aborting the whole
  cycle on one failure would still require deciding what to do with the
  sends that already succeeded before the failure (they cannot be
  un-sent), so "abort" doesn't actually avoid partial state — it just adds
  a decision about how far to unwind for no benefit. Continuing and letting
  each event's outcome stand on its own is the simpler model, not the more
  elaborate one.
- **No retry/backoff machinery is added.** A failed event is not retried
  within the cycle. Because `StateStore` is only updated on success, the
  next scheduled poll naturally re-derives the exact same create/update/
  cancel decision for that event and tries again — the existing full
  poll-and-diff design already is the retry mechanism, so nothing new needs
  to be built for it.
- **One bad event must not block unrelated ones.** With five events polled
  and one failing every cycle (e.g. a persistently malformed source event),
  an abort-on-first-failure design would mean that one event permanently
  starves updates and cancels for the other four for as long as it keeps
  failing. Continue-and-report has no such head-of-line blocking.

This is deliberately the smallest correct behavior: no retry counters, no
backoff, no dead-letter handling for a chronically failing event. If a
`sourceUid` fails every cycle indefinitely, that is visible in `failed` on
every result and is an operational concern for whoever consumes the result,
not something this feature needs to solve.

## Other judgment calls made in this spec

- **Relay UID generation.** A new `blockerUid` is generated randomly
  (e.g. a fresh random UUID) at create time, independent of `sourceUid` in
  both value and format. Deriving it deterministically from `sourceUid`
  (e.g. hashing it) was considered but rejected: it would leak the source
  calendar's `UID` namespace/format into the business calendar's blocker
  identities for no benefit, since the whole point of `RelayState` is to
  already carry that mapping explicitly and durably. Once generated, a
  `blockerUid` is never regenerated for the lifetime of its `RelayState`
  entry, cancelled or not.
- **Change detection is `start`/`end` only.** Since `SourceEvent` carries no
  other field and blockers are titleless, there is nothing else a change
  could mean for this feature. This will need revisiting if filtering logic
  (deferred) later gives `SourceEvent` more fields worth comparing.
- **No dedicated "resurrection" case.** Section 3.4 above is explicitly not
  new logic — it is what the update rule already does once cancelled
  entries are kept instead of deleted. Calling it out separately here is
  documentation, not an extra code path.

## Out of scope

Per `CLAUDE.md`'s "Deliberately deferred" section and this feature's own
brief:

- **Real CalDAV protocol implementation.** `CalendarSource.readEvents()` is
  specified as a full read with no delta semantics; `sync-collection`
  (RFC 6578) sync-token support is deferred until the basic full poll-and-diff
  relay works end-to-end, per `CLAUDE.md`.
- **Real SMTP sending / MIME construction.** `BlockerSink` is specified only
  as an interface contract (`BlockerMail` fields); assembling the
  `multipart/mixed` structure, base64 encoding, and exact headers described
  in `CLAUDE.md`'s reference findings is a future adapter's job.
- **Real `StateStore` persistence mechanics.** Whether entries live in a
  file, a database, or elsewhere is not decided here — only that `RelayState`
  must survive a restart (the diff on the next poll after a restart must see
  the same baseline as before it), and that the read/write operations above
  are sufficient for one full poll-and-diff cycle.
- **Filtering logic.** Every `SourceEvent` returned by `CalendarSource`
  becomes a blocker; no rule decides which source events are mirrored. Per
  `CLAUDE.md`, this is built last, after the structural iMIP generation is
  proven — introducing it now would mean building config surface and hooks
  before that phase starts, which `CLAUDE.md` explicitly says not to do.
- **Multi-calendar configuration file format.** This use case is specified
  as parameterized per single configured source calendar; running one
  instance per calendar declared in a future config file is an
  application-configuration concern, not part of this orchestration logic.
- **Recurring source events / `VALARM` / `RECURRENCE-ID`.** `CLAUDE.md`
  notes the captured reference scenarios are all single-occurrence events
  and recurrence is out of scope until explicitly revisited; `SourceEvent`
  and this diff algorithm assume one row per occurrence, not per recurring
  series.

## Open questions

- **Are organizer/attendee/`From`/`Reply-To` addresses per-source-calendar
  or global?** This spec assumes they are inputs to each use-case instance
  (so potentially per-calendar), but nothing in `CLAUDE.md` states whether a
  future multi-calendar setup would ever want different identities per
  calendar, or whether one identity always fits all configured calendars.
  Needs resolving before the multi-calendar config file format is designed.
- **Is a `failed` event retried on every subsequent poll indefinitely, or
  does something eventually need to suppress repeat notifications about it?**
  This spec deliberately leaves indefinite per-cycle retry as the only
  behavior (see Partial-failure handling), but if poll frequency is high and
  a `BlockerSink` outage is prolonged, whether the `failed` list needs
  deduplication or alerting is not addressed here.
- **What should trigger a poll, and at what interval?** Out of scope for
  this spec by design (it specifies the use case, not its caller), but the
  scheduling adapter that will invoke this use case is not yet designed
  anywhere in the project.
