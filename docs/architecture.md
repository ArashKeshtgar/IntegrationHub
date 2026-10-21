# Architecture and phases

IntegrationHub is the interface engine of the ClinicHub platform: every message between the applications passes
through it, so there is one place to see what was sent, to whom, and what came back, and one place to translate
to the formats the outside world uses (HL7 v2). It is written in **Java 21 / Spring Boot**, because the reference
FHIR and HL7 v2 libraries (HAPI) and most hospital interface engines (Mirth, Rhapsody's tooling) live in Java.

Today the applications call each other directly ([contracts.md](contracts.md)). The hub is put in between **by
configuration only**: each sender's base URL is pointed at a hub route. Pointing it back undoes the change. No
application is rewritten.

```mermaid
flowchart LR
    CH[ClinicHub] -->|orders, cancels| HUB
    LF[LabFlow] -->|results| HUB
    CR[ClinicReport] -->|results| HUB
    CH & LF & CR & PF[PharmaFlow] -->|financial documents| HUB
    HUB[IntegrationHub] --> CH & LF & CR & FIN[FinancePowerApp]
    HUB -->|de-identified events| MQ[(RabbitMQ)] --> AN[Analytics]
    EXT[External lab / OLIS sim] <-->|HL7 v2 over MLLP| HUB
    HUB --- DB[(PostgreSQL: message log)]
```

## Decisions

1. **A relay first, not a new owner.** ClinicHub owns patients and orders; LabFlow and ClinicReport own results;
   FinancePowerApp owns the books. The hub owns only its message log and its routes.
2. **Synchronous routes stay synchronous.** ClinicHub stores the ids from the transaction-response, and a `409` on a
   cancel is an answer. The hub returns the upstream's status and body unchanged, `502` if the upstream refused the
   connection, `504` on timeout. It does not retry: the senders' outboxes already do.
3. **The sender's token goes through.** The hub validates it (ClinicHub's JWKS, issuer, expiry, an audience and scope
   allowed on that route) and forwards the same `Authorization` header, so each receiver's own checks — "a result only
   from the app the order went to" — keep working. The hub never signs on behalf of another application.
4. **No health information in the log by default.** The log records metadata: route, sender (client id), clinic,
   resource types and counts, Bundle id, identifiers of ServiceRequests/DiagnosticReports, status, latency, size, a
   SHA-256 of the body. Storing bodies is a setting (`hub.payloads.store`), off by default; when on, bodies are
   encrypted (AES-GCM, key from configuration), kept for `hub.payloads.retentionDays` (default 30), and every view of
   a body is written to an access log (PHIPA).
5. **Asynchronous only where the outside world is asynchronous:** HL7 v2 (MLLP, ACKs) and the event feed to Analytics.

## Stack

Java 21, Spring Boot 3, Maven, HAPI FHIR structures R4 (parsing, validation), an own small HL7 v2 parser (phase 4, see its decisions), Spring
Security OAuth2 resource server (JWT), Spring Data JDBC + Flyway, PostgreSQL, RabbitMQ (phase 3), Micrometer +
Prometheus, JUnit 5 + Testcontainers + WireMock, GitHub Actions. API on `:5700`.

## Phases

| Phase | What | Status |
|---|---|---|
| 0 | Skeleton: build, health, CI, Docker, local compose | **Done** |
| 1 | Results relay (LabFlow, ClinicReport → ClinicHub) with token check and message log | **Done**; seen with the real LabFlow, ClinicReport and ClinicHub (`via-hub` profiles, 21 Oct 2026) |
| 2 | Orders, cancels and financial documents through the hub | **Done**; seen with all five applications (21 Oct 2026), ClinicReport's finance documents excepted (none was owed) |
| 3 | Event feed to Analytics (de-identified), metrics, message search API | **Done**; seen from the real applications into Analytics' warehouse (21 Oct 2026) |
| 4 | HL7 v2: external lab results in (ORU^R01 → FHIR), OLIS-style export out | **Done** in the hub; not tried with a real lab or OLIS; TLS and mutual TLS since phase 8 |
| 5 | New flows: e-prescriptions to PharmaFlow, patient merges, consent flags | **Done** in the hub (contracts, route, log, pseudonym on merges); e-prescriptions seen end to end (ClinicHub, hub, PharmaFlow); merges and labels sent by ClinicHub and handled (labels enforced) by ClinicReport, LabFlow and PharmaFlow since phase 8 |
| 6 | Admin web with ClinicHub sign-in, replay, alerts; platform compose | **Done** (web, replay of HL7 dead letters, alerts to the log and a webhook, route health, message timeline, platform compose); e-mail alerts and the lab codes page since phase 8; no payload viewer and replay only for HL7, by design (phase 8) |
| 7 | Orders to an external lab: ClinicHub's order Bundle becomes an HL7 ORM^O01 over MLLP | **Done** in the hub and ClinicHub; seen with `tools/olis-sim` as the lab, not with a real lab |
| 8 | Closing the gaps: TLS on MLLP, the lab's test codes, the lab's order number, merges and labels in the applications, e-mail alerts | **Done**; see below for what is still not |

Each phase ends with its tests green in CI, this table updated, and the README's status list updated. Changes needed
in other repositories are listed per phase; they are made there, in their own commits, and noted here.

### Phase 0 — Skeleton

- Maven project `ca.arashkeshtgar.integrationhub`, Java 21, Spring Boot 3 (web, actuator, security, validation,
  data-jdbc, flyway), PostgreSQL driver.
- `GET /actuator/health`, `GET /actuator/info` (version, git commit).
- `compose.yml`: PostgreSQL 16 (port 5433 to avoid clashes), later RabbitMQ.
- Flyway `V1__message_log.sql`: `message` (id uuid, received_at, route, direction, sender_client_id, clinic_slug,
  method, path, content_type, request_size, request_sha256, bundle_id, resource_summary jsonb, upstream_status,
  latency_ms, error), `message_identifier` (message_id, system, value) for searching by ServiceRequest/externalId.
- `Dockerfile` (multi-stage, distroless or temurin-jre), GitHub Actions: `mvn -B verify` with Testcontainers.
- Code style: Spotless (google-java-format). README: how to run.

**Done when:** `mvn verify` passes in CI; `docker compose up` + `mvn spring-boot:run` answers on `:5700/actuator/health`.

**Built:** all of the above. Spring Boot 3.5, Spotless runs in `verify`. Only `/actuator/health` and `/actuator/info`
are open; every other path answers `401` until phase 1 adds the token check. Not built: RabbitMQ in the compose file
(phase 3). Not yet seen running: the first CI run on GitHub, and `docker build` of the full image (see README).

### Phase 1 — Results relay

- Route table in configuration (`hub.routes`): name, upstream base URL, allowed audiences/scopes, timeout. First
  route: `clinichub` → `http://localhost:5300`, accepting the results tokens (scope `results`).
- `POST /route/{route}/fhir` and `/route/{route}/fhir/**`: validate the token (issuer `http://localhost:5301/`, JWKS,
  expiry, scope), parse just enough of the Bundle with HAPI to log its summary (types, `basedOn`, status), forward
  body and headers (`Authorization`, `Content-Type`, `Accept`, `X-Request-Id`; add `X-Forwarded-*`), stream the answer
  back unchanged.
- Invalid token → `401` without forwarding. Unknown route → `404`. Malformed JSON is **forwarded anyway** and logged
  as unparsed: the receiver decides, the hub must not become a stricter validator than the receiver.
- Message log written for every call, including refusals.
- **Tests:** WireMock as ClinicHub; a test JWKS and signed tokens; cases: pass-through of 200/400/409/500 bodies,
  502 on connection refused, 504 on timeout, 401 on bad issuer/expired/wrong scope, log rows written, no body stored.
- **Other repositories:** register client `integrationhub` in ClinicHub `Sso:Clients` (first needed in phase 4; the
  relay does not need its own token). Point the results base URL of LabFlow and ClinicReport (the setting that holds
  ClinicHub's FHIR address in each) at `http://localhost:5700/route/clinichub`. Write in each README how to switch
  back.

**Done when:** a result released in LabFlow and a report signed in ClinicReport reach ClinicHub's results inbox
through the hub, and the log shows both.

**Built (this repository):**

- `hub.routes.*` (`HubProperties`): upstream URL, timeout, connect timeout, allowed audiences, required scopes. Route
  `clinichub` → `http://localhost:5300`, scope `results`. `hub.auth.issuer` / `hub.auth.jwks-uri`.
- `POST /route/{route}/fhir[/**]` (`RelayController`): token check, then body, `Authorization`, `Content-Type`,
  `Accept` and `X-Request-Id` forwarded (an id is added when the sender sent none), `X-Forwarded-For/Proto/Host`
  added. Status, body and headers of the answer come back unchanged, except hop-by-hop headers, and Tomcat writes
  `Content-Type: type;charset=x` without the space. No redirects followed, no retry.
- Refusals are FHIR `OperationOutcome`: `401` (missing or invalid token: signature, issuer, expiry, scope, audience; with
  `WWW-Authenticate: Bearer`), `404` unknown route or `.`/`..` in the path, `413` body over `hub.max-body-bytes`
  (32 MB), `502` upstream unreachable, `504` upstream timeout, `503` when ClinicHub's keys cannot be fetched.
- `BundleSummary` (HAPI R4, parser warnings switched off because they can quote values): resource types and counts,
  Bundle id and type, DiagnosticReport statuses, and the order identifiers from `basedOn` (as `identifier` or as
  `…|id` / `ServiceRequest?identifier=…|id` references) with the clinic slug taken from
  `urn:clinichub:{clinic}:service-request`. A body that does not parse is forwarded and logged `parsed: false`.
- `MessageLog`: one row per call, refusals included, metadata only (no body column). Migration `V2` adds
  `response_status` (what the sender was told); `upstream_status` stays empty when nothing was forwarded. A failing
  log write is reported and does not stop the relay.
- Tests (42 in all): WireMock as ClinicHub and as its JWKS; tokens signed RS256 with `typ: at+jwt`; pass-through of
  200/400/409/422/500/503 and a byte-exact body with non-ASCII text; 502, 504; 401 for no token, garbage, wrong issuer,
  expired, wrong key, wrong or no scope, wrong audience (nothing forwarded each time); log rows and identifiers;
  a check that no marker from the body reaches the database or the application log.
- Found by those tests: ClinicHub's tokens are marked `typ: at+jwt`, which Spring's decoder refuses by default.
  `TokenValidator` accepts `JWT`, `at+jwt` and no type.

**Not built:**

- `hub.payloads.store` (encrypted bodies, access log): the log has no body column at all for now.
- A `503` test for unreachable JWKS (the code path exists).
- Anything other than `POST` and `/fhir`: phase 2 (done).
- Seen with the real LabFlow and ClinicHub (2026-10-18, a developer machine): two released LabFlow results went
  through the `clinichub` route with LabFlow's own token, ClinicHub answered 200 and completed the orders, and the hub
  made their `result.final` events.
- **Seen with ClinicReport too (21 Oct 2026):** an ECG ordered in ClinicHub, reported and signed in ClinicReport, came
  back through the `clinichub` route (200) and showed as a final result on the visit; a LabFlow CBC and creatinine the
  same way. ClinicHub, LabFlow and ClinicReport each ran their `via-hub` profile, the hub ran in ControlPanel's compose.

**Other repositories — done (21 Oct 2026):**

1. **LabFlow** got `ClinicHub:ResultsUrl` (empty: ClinicHub's own `/fhir`), **ClinicReport** the same setting and
   `ClinicHubOptions.ResultsEndpoint()` with its tests. The token request still goes straight to ClinicHub in both.
   Each has a `via-hub` launch profile and says in its README how to switch back (`connected`).
2. The open questions in contracts.md: 9 and 11 answered; the rest stand as written there.
3. ClinicHub: the `integrationhub` client exists (phase 4).


### Phase 2 — Orders, cancels, finance

- Routes `labflow` → `:5180`, `clinicreport` → `:5290` (audience = the app, scope `orders`), `finance` → `:5400`
  (audience `finance`, scope `finance`; in development also pass `X-Finance-Key` through).
- Cancels: `PUT /route/{app}/fhir/ServiceRequest?identifier=…` — query string preserved exactly; `409` passed through.
- Finance documents: `POST /route/finance/api/integration/documents`; log each document's `sourceKey`, `externalId`,
  `kind` (no amounts needed in the log).
- **Tests:** contract tests replaying recorded examples of each flow (add sample bodies under
  `src/test/resources/contracts/`, synthetic data only).
- **Other repositories:** ClinicHub `Integrations:LabFlow:FhirBaseUrl`, `Integrations:ClinicReport:FhirBaseUrl`,
  `Integrations:Finance:BaseUrl`; LabFlow/ClinicReport/PharmaFlow finance base URLs → hub routes. Development
  profile `via-hub` in each, so the direct setup stays the default.

**Done when:** an order placed in ClinicHub reaches LabFlow and ClinicReport, a cancel refused with `409` shows the
lab's message in ClinicHub, and a claim reaches FinancePowerApp — all through the hub.

**Built (this repository):**

- Routes `labflow` → `:5180` and `clinicreport` → `:5290` (audience = the app, scope `orders`, `POST` and `PUT` on
  `/fhir`), `finance` → `:5400` (audience and scope `finance`, `POST` on `/api/integration/documents`; `X-Finance-Key`
  is passed through). Each route now says which paths, methods and extra headers it relays and how its body is
  summarised (`allowed-paths`, `allowed-methods`, `forward-headers`, `body-format`). The order of checks is: route and
  path (`404`), method (`405` with `Allow`), token (`401`), then the body.
- Cancel: `PUT /route/{app}/fhir/ServiceRequest?identifier=…`. The query string goes to the app as sent. Tomcat is set
  to accept the characters it normally refuses (`server.tomcat.relaxed-query-chars`), and the relay percent-encodes
  only what a URI cannot carry (a raw `|` becomes `%7C`; `%XX` is never touched), because the JDK client cannot write
  those. The app decodes both to the same value. A `409` comes back with the app's own body and is asked for once.
- Log: an order Bundle's `ServiceRequest` identifiers and statuses (not the Patient or Practitioner: types and counts
  only); a cancel's identifier from the query; for finance each document's `sourceKey`, `externalId` and `kind`
  (also as identifiers `urn:integrationhub:finance:{sourceKey}` → `externalId`, so they can be searched). Amounts,
  parties, references, lines, names, health cards, notes: not read, not stored (tests check the database and the
  application log for markers planted in every sample).
- `X-Finance-Key`: `hub.routes.finance.api-key-header` (env `HUB_ROUTE_FINANCE_API_KEY_HEADER`), empty by default. When
  set, a call with that header and **no** `Authorization` is passed to FinancePowerApp unvalidated (it checks the key);
  a call that has a token is always validated. Meant for development only.
- Contract tests with synthetic samples in `src/test/resources/contracts/` (order Bundle and its transaction-response,
  a cancel and the lab's `409`, finance documents) — 77 tests in all.

**Not built / not known:**

- `X-Finance-Key` is accepted by FinancePowerApp **instead of** a token in Development (contracts.md, question 9), so
  the `dev` profile and ControlPanel's compose set `api-key-header: X-Finance-Key`; elsewhere it stays off.
- The order's transaction-response is not read, so the ids ClinicHub stores are not in the log.
- `Expect: 100-continue`, `HEAD`, `OPTIONS`: not relayed.
- **Seen with the real applications (21 Oct 2026, hub in ControlPanel's compose, the others on the host with their
  `via-hub` profiles):** ClinicHub's orders to LabFlow and ClinicReport (200 each); a cancel LabFlow accepted (200, the
  order shows *Cancelled* with the reason) and one it refused (409, ClinicHub shows "LabFlow did not cancel: Results
  have been entered…", the lab's own words); a prescription to PharmaFlow (200); finance documents from ClinicHub (a
  signed visit's claim, token) and LabFlow (token), and PharmaFlow's key-only path (an already-delivered document sent
  again with `X-Finance-Key`: 200, recognised as a duplicate, nothing new in the books). **Not seen:** a finance
  document from ClinicReport (the ECG template has no OHIP fees, so signing owed nothing), and PharmaFlow's own feed
  sending a new document through the hub (nothing new to send).
- Found on the way, in FinancePowerApp, not the hub: a body that is not JSON gets `500` from
  `/api/integration/documents`, where `400` was expected.

**Other repositories — done (21 Oct 2026):**

1. ClinicHub: a `via-hub` launch profile sets `Integrations:{LabFlow,ClinicReport,PharmaFlow,ExternalLab}:FhirBaseUrl`
   to `http://localhost:5700/route/{name}/fhir` (the settings hold the full base; cancels are POSTed to the same
   address) and `Integrations:Finance:BaseUrl` to `http://localhost:5700/route/finance`.
2. LabFlow, ClinicReport, PharmaFlow: `via-hub` profiles with their finance base URL on the hub's `finance` route
   (LabFlow and ClinicReport also their results).
3. Each README says how to switch back (the `http` / `connected` profile).
4. Questions 9 and 11 answered in contracts.md.

### Phase 3 — Event feed, metrics, search

- After a relayed call succeeds, publish a **de-identified** event to RabbitMQ exchange `clinical.events` (topic):
  `order.placed`, `order.accepted`, `order.cancelled`, `order.cancel-refused`, `result.final`, `result.amended`,
  `finance.document`. Fields: event id, time, clinic slug, application, test code (LOINC / template key), priority,
  status, ids of the order (`service-request|{id}`), **patient pseudonym** = HMAC-SHA256(secret, clinic + MRN) — never
  names, health cards, birth dates, notes or values. Analytics consumes this (its phase 4). Schema in
  `docs/events.md`, versioned (`schemaVersion`).
- Publishing uses a local outbox table in the same transaction as the log row, so an event is not lost when RabbitMQ
  is down.
- Metrics: messages per route/status, latency histogram, upstream errors. `GET /api/messages?route=&clinic=&status=&identifier=&from=&to=`
  (requires an admin token, phase 6 adds the UI; until then the dev profile allows it locally).

**Built (this repository):**

- **Events** ([events.md](events.md) is the contract, schema version 1): `order.placed`, `order.accepted`,
  `order.cancelled`, `order.cancel-refused`, `result.final`, `result.amended`, `finance.document` to the topic exchange
  `clinical.events` (the hub also declares the durable queue `analytics.clinical-events`, so nothing published before
  Analytics first starts is dropped). Off by default: `HUB_EVENTS_ENABLED=true` and a pseudonym secret of 32+
  characters, or the application does not start. `compose.yml` now has RabbitMQ.
- **Pseudonym** = hex(HMAC-SHA256(secret, `clinic|MRN`)), the MRN read from the Patient identifier with system
  `urn:clinichub:{clinic}:mrn` (setting `hub.events.mrn-system`); never the health card. Results and cancels do not
  carry the patient, so the hub remembers pseudonym, application, test and priority per order (`order_link`, 365 days)
  and uses them for the events of later results and cancels. A result for an unknown order has no pseudonym.
- **Outbox**: events are written to `event_outbox` in the same transaction as the call's log row and sent by a
  publisher with publisher confirms, retrying with a growing pause while RabbitMQ is down. Event ids come from the
  content, so a resent call is one event. Tested with a real broker, including a call relayed while the broker is
  down and published after it starts.
- **Metrics** at `/actuator/prometheus`: `hub.messages{route,status}`, `hub.message.latency{route}` (histogram),
  `hub.upstream.errors{route,kind=timeout|unreachable|http-5xx}`, `hub.events.outbox.pending`,
  `hub.events.outbox.oldest.age.seconds`, `hub.events.published{result}`. The route label is only ever a configured
  route (anything else is `unknown`).
- **Search** `GET /api/messages?route=&clinic=&status=&identifier=&from=&to=&limit=&offset=` (newest first, at most
  200 per page; `identifier` is a value or `system|value`; `status` is what the sender was told) and
  `GET /api/messages/{id}`. Metadata only: there is no body to return.
- **Who may read** the search and the metrics: a ClinicHub token with the permission `integrationhub:messages.view`
  (`hub.admin.permission`), checked like the relay's tokens, and the audience when `hub.admin.audience` is set. The dev
  profile (`--spring.profiles.active=dev`) also lets a call from this machine through without a token, but not one
  that carries `X-Forwarded-For`, `Forwarded` or `X-Real-IP`. Without either, `401`/`403`. Fails closed: the paths
  need an authority only that filter grants.
- **A fix to phase 1**: `MessageLog.write` called its `@Transactional` method on itself, so the transaction never
  applied and a log row and its identifiers were not written atomically. It uses a `TransactionTemplate` now, and a
  test shows that if an event cannot be stored, nothing of the call is.
- Tests: 105 in all (28 new in the events classes, the admin classes and the atomicity test), the events ones against
  a real RabbitMQ in Testcontainers.

**Decisions to check** (where the plan was not exact):

- `order.placed` is made when the application **answered**, not only on success, so that `order.accepted` means
  something different (a refused order is placed and not accepted). The plan says events follow a successful call;
  change `EventFactory.orders` if you want them to coincide.
- Results make events only for `final` and `amended` (the plan's list); `cancelled` and others make none.
- The RabbitMQ health indicator is off: the relay works with the broker down. The state of the feed is the
  `hub.events.outbox.*` metrics.

**Not built / not known:**

- No dashboards or alert rules for the metrics.
- No free-text search, no total count (phase 6 added the web page). `hub.payloads.store` still not built.
- **Seen with the real applications (21 Oct 2026):** the orders, results and cancel above made `order.placed`,
  `order.accepted`, `result.final` and `order.cancelled` events for LabFlow and ClinicReport; Analytics' consumer
  (ControlPanel, `--profile events`, its own RabbitMQ account) took them and its next run built them into
  `fact_order`. Every event of that day carries a pseudonym, so ClinicHub's MRN system is the one assumed
  (`urn:clinichub:{clinic}:mrn`, contracts.md question 13).

**Other repositories — done:**

1. ClinicHub: the MRN system is as assumed (above); the `integrationhub` client and its permissions exist (phase 4/6).
2. The pseudonym secret for ControlPanel is in its `.env` (`INTEGRATIONHUB_EVENTS_PSEUDONYM_SECRET`, made 21 Oct 2026).
   **Changing it changes every pseudonym**, so the same patient becomes a new one in Analytics.
3. RabbitMQ accounts `integrationhub` and `analytics` (ControlPanel `.env.example` has the commands; `guest` cannot
   be used from another container).
4. Analytics declares the queue as in events.md and ignores repeated `eventId`s (its phase 4).

### Phase 4 — HL7 v2

- **In:** MLLP listener (`:2575`) for ORU^R01 from an external lab: validate, map to a FHIR DiagnosticReport +
  Observations (`basedOn` from ORC-2/OBR-2 placer number = ClinicHub's ServiceRequest identifier), send to ClinicHub
  with the hub's own client-credentials token (client `integrationhub`, scope `results`; ClinicHub must accept results
  from this client for orders sent to an external lab — a change in ClinicHub). ACK `AA` when ClinicHub accepted,
  `AE`/`AR` otherwise. This route is asynchronous: store-and-forward with the same backoff as the senders.
- **Out:** LabFlow's open item "HL7 v2 ORU export (OLIS simulation)": copy final lab results relayed in phase 1 to an
  OLIS simulator as ORU^R01 (configurable target; a small simulator under `tools/olis-sim` that logs and ACKs).
- Mapping tables in code, with tests per segment; sample messages synthetic.

**Built (this repository):**

- **MLLP listener** on `:2575` (`hub.hl7.mllp.port`), one virtual thread per connection, many messages per connection,
  each answered before the next is read. Frames over `max-message-bytes` (1 MiB), a bad end of frame or an idle
  connection (`read-timeout`, 30 s) close the connection. Bytes between frames (a stray CR/LF) are skipped.
- **Parser and mapping**, in code and tested per segment (`hl7/`): `Hl7Message` (any HL7 v2.x text: CR, LF or CRLF,
  escapes `\F\ \S\ \T\ \R\ \E\ \Xhh\ \.br\`), `Hl7Tables` (all code tables in one file), `OruToFhir`
  (MSH, ORC/OBR, OBX, NTE; PID is never read), `FhirToOru` (the reverse, for the export), `Hl7Time` (keeps precision).

  | HL7 v2 | FHIR R4 |
  |---|---|
  | MSH-3.MSH-4, MSH-10 | sender id (`EXTLAB.LAB1`) and message id, used for de-duplication; Bundle id and resource ids are derived from them, so a resend gives the same ids |
  | ORC-2 / OBR-2 placer order number `101^harbourview` (or the whole `urn:clinichub:harbourview:service-request` as the first component) | `DiagnosticReport.basedOn.identifier` = `urn:clinichub:harbourview:service-request\|101` |
  | ORC-3 / OBR-3 filler order number | `DiagnosticReport.identifier` (`urn:integrationhub:hl7:{sender}:filler-order`) |
  | OBR-4 | `DiagnosticReport.code` (LN → LOINC, SCT → SNOMED, an unknown system name → `urn:integrationhub:hl7:{sender}:{name}`) |
  | OBR-7 / OBR-22 | `effectiveDateTime` / `issued` |
  | OBR-25 (table 0123) | `status`: F final, C amended, X cancelled; P, R, A, O, I, S, Y are accepted and **not forwarded** (ClinicHub takes final, amended, cancelled) |
  | OBX-2 NM / SN / ST, TX, FT / CE, CWE, CNE | `valueQuantity` (with `<`, `>`, `<=`, `>=` as comparator, unit from OBX-6 as UCUM) / `valueString` / `valueCodeableConcept` |
  | OBX-3 | `Observation.code`; the Observations are `DiagnosticReport.result` |
  | OBX-7 | `referenceRange` (text, and low/high when it is `a-b`, `<a` or `>a`) |
  | OBX-8 (table 0078, repeating) | `interpretation` (v3 ObservationInterpretation); a flag not in the table is kept as text |
  | OBX-11 (table 0085) | `Observation.status`; OBX-14, else OBR-7 → `effectiveDateTime` |
  | NTE after an OBX / after the OBR | `Observation.note` / `DiagnosticReport.conclusion` |
  | PID, PV1, NK1, … | **not read**. No patient is named in what goes to ClinicHub; it knows the patient from the order |

  A message the hub cannot map completely (unknown result status, an OBX value type other than the above, a numeric
  value that is not a number, no placer order number, an OBX before any OBR) is refused with AR; nothing is forwarded
  with a value missing.
- **Store-and-forward** (`hl7_inbound`, migration V4): the message is mapped, written, and handed to ClinicHub at once
  as the client `integrationhub` (client credentials, scope `results`, token cached until 30 s before it expires).
  If ClinicHub (or the network) does not take it (no answer, 408/425/429/5xx, 401) the row stays and the worker
  (`hub.hl7.retry`) tries again after 10 s, 20 s, 40 s … up to 30 min, 12 attempts, then the row is `FAILED`. Any other
  4xx from ClinicHub marks it `REJECTED` and is not retried.
- **ACK**: `AA` = ClinicHub has it, or the message holds nothing to forward (a preliminary result); `AE` = the hub has it
  and is retrying, do not resend; `AR` = refused for good. The ACK text is one of a few fixed phrases and never quotes
  the message. A message the lab sends again (same sender and MSH-10) is recognised: AA if delivered, AE if still
  waiting, and it is processed again only if the first attempt ended `FAILED` or `REJECTED`.
- **Health information at rest**: the queued FHIR Bundle and the queued outbound ORU are encrypted (AES-256-GCM, key
  `HUB_HL7_QUEUE_KEY`, bound to the row id) and the column is set to NULL when the row is delivered, rejected or
  given up. The other columns hold sender, message id, status, counts and a fixed error word. Rows older than
  `retention-days` (14) are purged. Nothing of a message is logged (only fixed reasons); each attempt is a row in
  `message` (route `hl7`, method `MLLP` or `RETRY`, metadata only) and a delivered result makes the same events as a
  relayed one (`result.final`, `result.amended`).
- **Export to OLIS** (`hl7_outbound`): a POST to the `clinichub` route that ClinicHub answered 2xx is also queued, in the
  transaction of its log row, as one ORU^R01 per final / amended / cancelled DiagnosticReport, and sent by a worker to
  `hub.hl7.outbound.host:port` over MLLP; AA marks it sent, anything else is retried like the inbound side, then
  `FAILED`. PID-3 holds only the patient **pseudonym** of the order (`UNKNOWN` when the hub does not know the order);
  no name, birth date or health card number. A report whose values the mapping cannot express (e.g. a boolean) is
  skipped as a whole and counted (`hub.hl7.outbound{result=skipped}`).
- **`tools/olis-sim/OlisSim.java`**: `java tools/olis-sim/OlisSim.java --port 2576 --out received/ --mode accept|error|reject|silent`
  prints one content-free line per message, ACKs, and optionally keeps each message as a file.
- **Settings** (all off by default): `HUB_HL7_ENABLED`, `HUB_HL7_QUEUE_KEY` (32 random bytes, base64), 
  `HUB_CLINICHUB_CLIENT_SECRET`, `HUB_CLINICHUB_TOKEN_URL` (default `{issuer}connect/token`), `HUB_CLINICHUB_FHIR_URL`
  (default `{clinichub route upstream}/fhir`), `HUB_HL7_ALLOWED_SENDERS` (MSH-3.MSH-4, empty = any),
  `HUB_HL7_ZONE` (for times written without a zone), `HUB_HL7_MLLP_PORT`/`BIND`, `HUB_HL7_OUTBOUND_ENABLED`/`HOST`/`PORT`.
  With `hub.hl7.enabled=true` the application does not start without a valid queue key and the ClinicHub settings.
- **Metrics**: `hub.hl7.inbound{result=delivered|retry|failed|rejected|duplicate|accepted-not-forwarded|…}`,
  `hub.hl7.inbound.pending`, `hub.hl7.inbound.failed`, `hub.hl7.outbound{result=queued|sent|retry|failed|skipped}`.
- Tests: 156 in all (51 new: parser and time, a test per mapping area against synthetic messages, framing, ACK,
  cipher, settings, the MLLP server against a real PostgreSQL with ClinicHub played by WireMock — delivery, retry,
  giving up, refusal, duplicates, no health information in logs or the log table — and the export against the real
  `OlisSim` started as a process).

**Decisions to check** (where the plan was not exact):

- **Own HL7 parser instead of HAPI HL7 v2.** The plan listed HAPI HL7 v2. Its parsers are version specific and need a
  structure definition per version (v2.3 ≠ v2.5.1) while labs mix versions; the hub only reads a handful of fields.
  `Hl7Message` is about 270 lines and takes any version. If you want HAPI for validation against a profile, it can be
  added next to it; nothing depends on the choice except the parser class.
- **ACK semantics with store-and-forward**: AA is sent only when ClinicHub has the result, not when the hub has stored
  it; AE (not AA) when it is stored and being retried. A lab that treats AE as "send again" is harmless (duplicates
  are recognised), a lab that treats AE as a failure would raise a false alarm. If the lab wants AA on receipt,
  change `Hl7Inbound.store` — it is one line, but then a result can be acknowledged and still end in `FAILED`.
- **Placer order number format** (`101^harbourview` or the full URN) is an assumption: how an external lab is told the
  order number is not defined anywhere yet (contracts.md, question 16).
- **Preliminary and other non-final statuses are accepted and dropped**, because ClinicHub's results contract is
  final / amended / cancelled (contracts.md flow 3).
- **Character set**: ISO-8859-1 unless MSH-18 says UTF-8.
- **Text values (ST, TX, FT) and NTE comments are read whole**: these types have no components, so a `&` or `^` the
  lab did not escape is kept as text. (Found after the phase: the first version cut the text at it, so
  "Hemolyzed & lipemic" reached ClinicHub as "Hemolyzed ".)
- The export copies results the hub **relayed**, which are LabFlow's and ClinicReport's. It does not export results that
  came in over HL7 (they would go back to where they came from).

**Not built / not known:**

- ~~**No TLS on the MLLP listener or the export.**~~ (Done in phase 8.) Run them on a private network or behind a TLS terminator (stunnel,
  the load balancer). Anyone who can reach port 2575 can submit a result for any order; `allowed-senders` only checks
  what the message says about itself. Mutual TLS or an allow-list by address is needed before a real lab connects.
- **Not tried with a real lab, a real OLIS or a real ClinicHub.** Everything was run against synthetic messages, a
  WireMock ClinicHub and `OlisSim`. The real OLIS message profile (required PID and ORC fields, health card number,
  provider and facility identifiers, its own coding of test and specimen) is **not** implemented; the export is an
  ORU^R01 in the shape of the hub's own results, for the simulator.
- Only ORU^R01. No ORM (orders to an external lab), no ADT, no query.
- OBX value types other than NM, SN (simple form), ST, TX, FT, CE, CWE, CNE are refused (no ED/RP attachments, no
  dates, no repeating values). Specimen (SPM) and the OBR parent/child links are ignored.
- A resend of a message whose first copy is still `PENDING` is not compared with it: the first content wins.
- **Seen end to end on 19 October 2026** (running hub, ClinicHub with its new `ExternalLab` target, a synthetic ORU^R01
  sent over MLLP by hand): an order placed to the external lab became *Completed* with its result in the physician's
  inbox, and the lab got `AA`. A message for an order that does not exist got `AR` (`REJECTED`, 403 from ClinicHub). A
  resent copy got `AA already delivered` from the hub's own duplicate check, so ClinicHub's handling of a second
  identical Bundle was **not** exercised. Before that change every inbound result ended `REJECTED`.
- GitHub Actions has not run on this phase (the tests were run in this session only).
- No admin screen for the queues (phase 6): look at `hl7_inbound` / `hl7_outbound` (no content in them) and the
  metrics; a `FAILED` row means the lab has to resend.

**Other repositories — to do locally, not done:**

1. ClinicHub — **done and seen with this hub (19 October 2026):** the client `integrationhub` (client credentials, scope
   `results`) is registered, and the results endpoint maps it to a new order target `ExternalLab`: a Bundle is accepted
   only for an order placed to that target at the clinic named in `basedOn` (403 otherwise). Set
   `HUB_CLINICHUB_CLIENT_SECRET` to the client's secret (`dev-only-integrationhub-secret` in ClinicHub's development
   settings). The token endpoint is `{issuer}connect/token`, or set `HUB_CLINICHUB_TOKEN_URL`.
2. ClinicHub — **partly done:** an external-lab order is numbered by its ClinicHub order id, so `101^harbourview` or
   the URN form in ORC-2/OBR-2 matches it. **Not built:** ClinicHub does not send the order to the lab and the hub has
   no ORM^O01, so how the lab learns the number is still outside both (the requisition travels some other way).
3. ClinicHub — **read in its code, not run:** a Bundle that arrives twice is stored once (the result is found by order
   and report identifier and replaced), and answered 201 both times, so a resend is not `REJECTED`. This holds only if
   the DiagnosticReport `identifier` is the same each time, which the hub guarantees.
4. The lab or its interface engine: point it at `hub:2575`, agree the sender id (MSH-3/MSH-4) and what it does with AE.
5. Choose and keep the queue key (`HUB_HL7_QUEUE_KEY`). Losing it makes queued rows unreadable (they are then given
   up after the retries); changing it with rows pending has the same effect.

### Phase 5 — Flows the platform lacks

Each needs work in the other repositories; the hub defines the contract (`docs/contracts.md`) and the route.

1. **E-prescriptions ClinicHub → PharmaFlow:** FHIR MedicationRequest Bundle (patient, prescriber with CPSO, drug as
   DIN, sig, quantity, repeats). PharmaFlow receives it into reception as an electronic prescription.
2. **Patient merges:** when ClinicHub merges two records, send `Patient` with `link.type = replaced-by` to the apps that
   received orders for the merged record.
3. **Consent directives:** mark orders of a patient with a directive (`meta.security` with a confidentiality code), so
   LabFlow and ClinicReport can restrict who sees the result.

**Built (the hub's part):**

- **Contracts** 5 to 7 in [contracts.md](contracts.md) (open questions 20 to 24): the e-prescription Bundle and its
  cancel, the patient-merge Bundle, the confidentiality label. They mirror what ClinicHub already sends for orders (a
  transaction Bundle POSTed to `/fhir`, a cancel as the resource again with a conditional PUT as its `request`).
- **Route `pharmaflow`** (`HUB_ROUTE_PHARMAFLOW_UPSTREAM`, default `http://localhost:5500`): POST only, `aud` `pharmaflow`,
  scope `prescriptions`, relayed unchanged with the sender's token, status and body passed back (a `409` on a cancel is an
  answer). The merge Bundle goes through the existing `labflow` and `clinicreport` routes and this one.
- **Log:** a prescription is its id (`urn:clinichub:{clinic}:prescription|{id}`, searchable and giving the clinic) and
  its status; never the drug, directions, quantity, doctor or patient (a test with markers over the log tables and the
  application log). Confidentiality codes are logged as short codes (anything else as `other`) and counted:
  `hub.consent.labelled{route,code}`.
- **Merges:** a Patient `replaced-by` another, answered `2xx` on a POST, moves the pseudonym the hub remembers with the
  merged patient's orders to the survivor's (`order_link`), so later results carry it. Nothing happens when the app did not
  answer or answered an error (the sender retries), for a merge of someone with no orders here, or for an MRN system the
  hub does not recognise.
- Tests: 12 new (relay unchanged, the refused cancel, the log holding no marker, wrong audience and scope, POST only,
  merges relayed and moving the pseudonym, not moving it when unanswered, labels counted and kept short); 170 in all.

**Not built, said plainly:**

- **Nothing sends or receives these flows yet.** ClinicHub has no prescribing (no drugs, no prescriptions) and sends no
  merge Bundle and no label; PharmaFlow has no electronic-prescription intake and no sign-in through ClinicHub; LabFlow
  and ClinicReport do not read a merge or a label. Tested with synthetic Bundles and WireMock only.
- No event for a prescription or a merge. Events published before a merge keep the old pseudonym; the feed does not say
  two pseudonyms are one person (events.md).
- The hub does not check that a result for a restricted order comes back labelled, and does not add, remove or change
  a label: relaying is unchanged.
- The pseudonym move needs the MRN in the merge Bundle to be in the system the hub reads (`hub.events.mrn-system`).

**Other repositories — to do locally, not done:**

1. **ClinicHub:** (a) a client `pharmaflow` for order-style service tokens: `aud` `pharmaflow`, scope `prescriptions`
   (where it signs the one for LabFlow); (b) send the merge Bundle (flow 6) to each app that received something for the
   merged record, in the transaction of the merge or from its outbox, through the hub route; (c) put `meta.security` on
   an order or prescription a consent directive covers, and decide which directive is which code (question 23);
   (d) **prescribing: done in ClinicHub `701de46`** (prescribing at a visit, the Bundle and cancel of flow 5, the
   token with `aud` `pharmaflow` and scope `prescriptions`, so (a) is done too). **Seen through the hub in a real run**
   against PharmaFlow's intake (a prescription sent and accepted, then cancelled before filling; the hub log held no
   drug, patient or health card number). The refusal after filling (`409`) was seen against a stand-in and is
   tested in PharmaFlow, not yet run live against PharmaFlow.
2. **PharmaFlow:** (a) the intake for the e-prescription Bundle into reception, idempotent on the prescription
   identifier, **done** (PharmaFlow `9d4de5c`); (b) sign-in through ClinicHub, **done** (PharmaFlow `82362e2`); (c) a
   cancel that answers `409` once dispensing has started, **done**; (d) read the merge Bundle: not done.
3. **LabFlow, ClinicReport:** read the merge Bundle (repoint what they hold for the merged MRN, question 22) and the
   label (limit who sees the order and its result, and put the label on the report they send back).
4. **Analytics:** nothing, unless it should unify a merged patient's two pseudonyms: that needs a `patient.merged` event
   the hub does not send.

### Phase 6 — Admin web and operations

**Built (this repository):**

- **Admin API** under `/api/admin` (plus `/api/messages` and `/actuator/prometheus` as before), metadata only:
  - `GET /api/admin/me`: who is calling, whether they may replay, whether HL7 is on.
  - `GET /api/admin/overview`: every route's health, the queue sizes, the number of open alerts.
  - `GET /api/admin/dead-letters`, `POST /api/admin/dead-letters/{inbound|outbound}/{id}/replay`.
  - `GET /api/admin/alerts?state=open|all`.
  - `GET /api/messages/{id}/timeline`: the call's answer, the events it caused, its HL7 queue rows, and the other calls
    carrying the same identifier (order, result, cancel).
- **Permissions:** `integrationhub:messages.view` for everything above; `integrationhub:messages.replay` for the POST.
  ClinicHub hands an application its permissions **without** the application's prefix (`messages.view`), so the hub
  accepts both spellings. (Until now it accepted only the prefixed one, which no ClinicHub token carries.) Refusals are
  `{"error": "..."}`: 401 no token, 403 no permission.
- **CORS:** only for `/api/**`, only for the pages in `hub.admin.cors-origins` (the admin web). The relay has none.
- **Route health** (`hub.routes` plus `hl7` when the listener is on), read from the message log: calls and failures in
  15 minutes and 24 hours, p95 latency, last answered, last failure. A call *failed* when the hub answered 5xx; it
  *succeeded* when the upstream answered below 500 (a `409` from the pharmacy is an answer). Status: `down` (see alerts),
  `degraded` (a failure in 15 minutes), `ok`, or `idle` (no calls: nothing probes the applications).
- **Replay.** A message the hub gave up on (`FAILED`) or ClinicHub refused (`REJECTED`), and an OLIS copy that was given
  up, now **keep their payload (still encrypted) for `hub.hl7.replay-window-days` (default 7) after `failed_at`**,
  then the payload is dropped as before (the row stays until `hub.hl7.retention-days`). `0` drops it at once, as
  before, and nothing can be replayed. Replay puts the row back to `PENDING` with no attempts; the retry worker takes it
  within its interval. The row records the replay count, time and the sign-in subject (an account id, not a name);
  the log line and the counter `hub.replays{queue}` do not name content. A message with no payload left, or not in a
  failed state, answers `409` with the reason. **This changes how long encrypted health information stays in the
  hub's database** (7 days longer for given-up messages); set the window to `0` to keep the old behaviour.
- **Alerts.** Two kinds, one open row per kind and subject in `alert`: `route-down` (at least
  `hub.alerts.route-down-min-failures`, default 3, calls failed and none was answered below 500 in
  `hub.alerts.route-down-after`, default 5 minutes) and `hl7-dead-letters` (inbound or outbound rows given up or refused
  and not replayed). Each is a log line (`ERROR`) and, with `hub.alerts.webhook-url`, a JSON POST when it opens and
  when it resolves (kind, subject, state, times, a fixed phrase with counts: never content); a webhook that is down is
  tried again every round. Gauge `hub.alerts.open`.
- **Admin web** (`web/`, React + Vite + TypeScript, `:5701`): signs in through ClinicHub (authorization code with
  PKCE, public client `integrationhub-admin`, token kept for the tab only), then Overview (routes, queues, alerts),
  Messages (search, a message's facts and timeline, related calls), Dead letters (replay behind a second click, only for
  those with the replay permission), Alerts. The nginx image proxies `/api` to the hub, so the page needs no CORS in the
  platform compose.
- **Platform compose:** the hub, its PostgreSQL and RabbitMQ and the web are in ControlPanel's `compose.apps.yml`
  (ports 5700 and 5701 on 127.0.0.1; RabbitMQ on 127.0.0.1:5672 where Analytics' consumer looks). HL7, the event feed
  and the webhook are off until their `.env` values are set.
- **CI** builds the web (typecheck, tests, build) and both Docker images. Tests: 14 new in the hub (permissions and
  CORS, route health, alerts and the webhook, dead letters and replay including the window, the timeline), 15 web
  tests in 4 files, 2 in ClinicHub; 184 in the hub in all.

**Not built, said plainly:**

- **No payload viewer and no `integrationhub:payloads.view`.** The hub still stores no message bodies
  (`hub.payloads.store` is not built), so there is nothing to view; the permission was dropped from the plan until a
  store exists.
- **Replay is for HL7 queue rows only.** A relayed call (results, orders, prescriptions) is synchronous and not stored:
  the sender's own outbox retries it, and the hub cannot resend what it did not keep.
- **No e-mail alerts.** Log and webhook only.
- **No active probe.** Route health comes from real traffic; a route nobody calls is `idle`, not proven healthy.
- Alerts have no acknowledge or snooze; the dead-letter alert clears when the rows are replayed or purged by the
  retention period, so a message that is given up and never replayed keeps its alert for `hub.hl7.retention-days`.
- No audit log of who *viewed* what in the admin web (the message log holds no content; a replay is recorded on its row).
- (The HL7 results path ended in ClinicHub refusing, 403, until ClinicHub got an external-lab order target; fixed, see phase 4 and phase 7.)
- The Docker images are built in CI, not pushed to a registry.
- No end-to-end browser test in CI; the web was run by hand against ClinicHub.

**Other repositories:**

1. **ClinicHub (done in this change, in ClinicHub's repository):** the clients `integrationhub-admin` (public, for the
   web, with `App: integrationhub` so its token carries the `integrationhub` permissions) and `integrationhub`
   (confidential, scope `results`, for the HL7 path), and the permissions `integrationhub:messages.view` and
   `integrationhub:messages.replay` (the Administrator role has them; no other role does). The external-lab order
   target that was missing here is built too (19 October 2026, after this phase): see phase 4 and phase 7.
2. **ControlPanel (done, in its repository):** the hub, its database, RabbitMQ and the web in `compose.apps.yml`, and the
   `INTEGRATIONHUB_*` settings in `.env.example`. Not started with `up` there.

### Phase 7 — Orders to an external lab (ORM^O01)

Closes the loop of phase 4: a result from an external lab (ORU^R01 in) answers an order that ClinicHub has recorded
(`ExternalLab` target); now ClinicHub can also **send** that order, through the hub, to the lab.

- **Route `externallab`** (`POST|PUT /route/externallab/fhir`, setting `hl7-order: true`). ClinicHub posts the same order
  Bundle it sends to LabFlow and signs the same kind of token (`aud` `externallab`, scope `orders`, `clinic_slug`). The
  hub checks the token as for any route, turns the Bundle into **one ORM^O01** (`hl7/FhirToOrm`) and sends it over MLLP
  to `hub.hl7.lab` (`HUB_HL7_LAB_HOST`, `HUB_HL7_LAB_PORT`). **The lab's ACK is the answer, in the same call:**
  `AA`/`CA` gives `200` with a transaction-response, `AE`/`AR` gives `422` for an order (ClinicHub retries) and `409`
  for a cancellation (ClinicHub treats it as the lab's answer, like LabFlow's), no ACK or no connection gives `502`, a
  Bundle that is not an order gives `400`, no lab configured gives `503`.
- **No queue and no retry in the hub:** ClinicHub's outbox retries (10 s up to 30 min, 12 times). Nothing is stored.
- **The message:** `MSH` (v2.5, `UNICODE UTF-8`), `PID` (MRN as `MR`, health card as `JHN`, name, birth date, sex),
  then per ServiceRequest `ORC` (`NW`, or `CA` for a cancellation; placer number `{order id}^{clinic slug}`, the
  requisition in ORC-4, the order time, the ordering provider's CPSO number in ORC-12) and `OBR` (same placer number,
  the test as `code^display^LN`, ordering provider, priority in OBR-27: `S` stat, `A` urgent/asap, `R` routine), and an
  `NTE` with the clinical note. The lab quotes the placer number back in ORC-2/OBR-2 of its result, which is what
  phase 4 reads (contracts.md, question 16).
- **Patient identity goes to the lab, because an order needs it.** The hub builds the message in memory, sends it and
  drops it. The message log holds metadata only, as for every route; the tests check that no name, number, note or
  birth date reaches the log or the application log.
- Metrics: `hub.hl7.orders{result=accepted|refused|unavailable|invalid|not_configured}`, and the usual per-route
  `hub.messages{route=externallab}`.
- **Seen end to end on 19 October 2026** with a second hub (new build, own database), ClinicHub and `tools/olis-sim` as
  the lab: a Stat creatinine order went out as ORM^O01 and ClinicHub marked it *Accepted*; a hand-sent ORU^R01 quoting
  `13^harbourview` made it *Completed*; cancelling another order sent `ORC|CA` and ended *Cancelled*. The simulator
  checks nothing about the message, so this shows the path works, not that a real lab accepts the profile.
- Tests (189 in the hub in all): `Hl7OrdersTest` (a socket plays the lab: exact segments and field positions, cancel as `CA`, `AR`/`AE`,
  silence and hang-up, wrong audience and scope, a Bundle that is not an order).

**Not built / not known:**

- **No TLS** on the MLLP connection to the lab (as in phase 4). One connection per message, no keep-alive.
- **Not tried with a real lab.** The segments follow HL7 v2.5 as commonly used for orders; a real lab will want its own
  profile (its test codes, specimen information in `SPM`/`OBR`, accession handling, required `PV1`). Test codes are sent
  as ClinicHub's LOINC; a lab that uses its own codes needs a mapping table, which does not exist.
- **The order's filler number is not kept.** The ACK of an ORM carries none, so ClinicHub's order has no external id.
- A refused order (`AR`) is retried by ClinicHub up to 12 times before it is marked *Not delivered*; the hub cannot tell
  ClinicHub that it is permanent without changing ClinicHub's retry rules.
- Only ORM^O01 (new and cancel). No order change, no order status query, no OML^O21.
- A cancellation after the lab has resulted is the lab's decision (it should answer `AR`/`AE`); the hub does not know.

**Other repositories:**

1. **ClinicHub (done):** `Integrations:ExternalLab:FhirBaseUrl` set to the hub route (`http://localhost:5700/route/externallab/fhir`)
   makes external-lab orders go through the outbox like LabFlow's; unset, they are only recorded (status
   `AwaitingResult`), as before.
2. The lab or its interface engine: listen on the port in `HUB_HL7_LAB_PORT`, accept ORM^O01 and answer with an ACK, and
   quote ORC-2/OBR-2 in the result it sends back.

### Phase 8 — Gaps closed (21 October 2026)

- **TLS on MLLP** (`MllpSockets`, `hub.hl7.mllp.tls`, `hub.hl7.lab.tls`, `hub.hl7.outbound.tls`, `hub.hl7.tls.*`): the
  listener speaks MLLP over TLS 1.2/1.3 with the key store's certificate and can ask for (`want`) or require (`need`)
  the lab's certificate, checked against the trust store; connections the hub makes to the lab and to the OLIS-style
  receiver check the other side's certificate and its host name against the trust store (empty: the JVM's roots) and
  present the hub's own when asked. PKCS12 stores; passwords from the environment. A failed handshake closes the
  connection and logs only the exception type. Tested with test-only self-signed stores in `src/test/resources/tls`:
  both ways over mutual TLS, an untrusted server refused, an untrusted client certificate refused, a plain client to a
  TLS listener answered with nothing, and the settings refused when inconsistent. **Not tried against a real lab's
  certificate chain or an HL7 engine's TLS profile.**
- **The lab's test codes** (`hl7_code_map`, V6, `LabCodes`, admin API `GET|PUT /api/admin/code-map`, the PUT needing the
  replay permission and replacing the whole map, a bad row named and nothing saved): an order goes out with the lab's
  code and name first and the LOINC as the alternate (`HGB^Haemoglobin^L^718-7^^LN`); a result coded only the lab's way
  comes back with the LOINC added. A test not in the map goes as before. The admin web has a "Lab codes" page for it (the
  replay permission to change it, read-only without); the hub's CORS now allows `PUT` for the admin origins.
- **The lab's order number is kept.** ClinicHub now stores the filler order number a result carries (the identifier the
  hub writes from ORC-3/OBR-3) as the order's external id the first time it sees one; the ACK of an ORM carries none.
- **Merges and confidentiality labels in the applications** (flows 6 and 7): ClinicHub sends them and labels orders and
  prescriptions; ClinicReport, LabFlow and PharmaFlow keep the label, show it and return it on their results, and
  follow a merge, and since the same day **enforce the label** with a logged "break the glass" override (details in
  contracts.md, flow 7). Details and what each does in [contracts.md](contracts.md#6-patient-merges--clinichub--every-app-that-received-an-order-or-prescription-for-the-merged-record).
  The hub needed no change: it relays both unchanged.
- **E-mail alerts** (`AlertMailer`, `hub.alerts.mail-to`, `mail-from`, Spring's `spring.mail.*`): the facts the webhook
  carries, never message content. A channel that fails is tried again with the next round, and so is the other, so a
  message may arrive twice rather than not at all. Tested with a mocked mail sender; **not sent through a real SMTP server.**

**Not done, and why:**

- **A payload viewer and replay of relayed (FHIR) messages are not built, on purpose.** The hub stores metadata only:
  that is its privacy design (the tests plant markers in every sample and look for them in the database and the logs).
  A viewer needs the bodies kept, which makes the hub a store of patient data with its own retention, access and breach
  duties; the senders' outboxes already retry and already hold what was sent. If it is wanted, it is a decision to take
  (kept encrypted like the HL7 queues, for how long, who may open it), not a feature to add quietly.
- Not tried with a real lab, OLIS, a real SMTP server or a real HL7 engine's TLS.

## Not in scope

- Rewriting any of the .NET applications, or moving their outboxes into the hub.
- A FHIR server (storage, search). The hub relays; it is not a clinical data repository.
- Real OLIS, real OHIP, real PharmaNet/ePrescribe connections: simulated only, synthetic data only.
