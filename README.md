# IntegrationHub

The interface engine of the [ClinicHub](https://github.com/ArashKeshtgar/ClinicHub) platform: orders, cancels, results
and financial documents between ClinicHub, [LabFlow](https://github.com/ArashKeshtgar/LabFlow),
[ClinicReport](https://github.com/ArashKeshtgar/ClinicReport), FinancePowerApp and
[PharmaFlow](https://github.com/ArashKeshtgar/Drugstore) pass through it, so there is one log of what was sent and
what came back, and one place that speaks HL7 v2 to the outside world.

Java 21 · Spring Boot 3 · HAPI FHIR R4 · HL7 v2 over MLLP · PostgreSQL · RabbitMQ · Testcontainers.

- Plan and phases: [docs/architecture.md](docs/architecture.md)
- What the applications send each other today: [docs/contracts.md](docs/contracts.md)

## Run it

Needs Java 21, Maven and Docker.

```
docker compose up -d --wait      # PostgreSQL 16 on :5433, RabbitMQ on :5672 (UI :15672)
mvn spring-boot:run              # API on :5700
curl localhost:5700/actuator/health
```

`mvn -B verify` runs the tests (Testcontainers starts its own PostgreSQL, so Docker must be running) and the
Spotless format check; `mvn spotless:apply` fixes formatting. Settings: `HUB_DB_URL`, `HUB_DB_USER`, `HUB_DB_PASSWORD`.
Image: `docker build -t integrationhub .`

## Using the relay

A sender posts to `http://localhost:5700/route/{route}/fhir` instead of the application directly, with the token it
already has. Routes are in `hub.routes` (`application.yml`): `clinichub` (results), `labflow` and `clinicreport` (orders, cancels), `finance` (financial documents), `pharmaflow` (e-prescriptions and their cancel; patient merges go through the app routes); each lists the paths and methods it relays. To undo, point the sender back
at the application. Every call, including refusals, is a row in `message` (metadata only, never the body).

## Events, metrics, search

- De-identified events to RabbitMQ for Analytics: [docs/events.md](docs/events.md). Off until
  `HUB_EVENTS_ENABLED=true` and `HUB_EVENTS_PSEUDONYM_SECRET` (32+ characters) are set.
- `GET /actuator/prometheus` (metrics) and `GET /api/messages` (search of the log) need a ClinicHub token with
  `integrationhub:messages.view`; with `--spring.profiles.active=dev` a call from this machine needs none.

## HL7 v2

Results from an external lab arrive as ORU^R01 over MLLP on `:2575`, become a FHIR Bundle and go to ClinicHub; if
ClinicHub is down they wait (encrypted) and are retried. Results the hub relays are also sent as ORU^R01 to an OLIS-style
receiver. Off until `HUB_HL7_ENABLED=true`, `HUB_HL7_QUEUE_KEY` (`openssl rand -base64 32`) and
`HUB_CLINICHUB_CLIENT_SECRET` are set; the export also needs `HUB_HL7_OUTBOUND_ENABLED=true` and
`HUB_HL7_OUTBOUND_HOST`. Try it without a lab:

```
java tools/olis-sim/OlisSim.java --port 2576 --out /tmp/olis      # the receiver
# hub: HUB_HL7_ENABLED=true HUB_HL7_OUTBOUND_ENABLED=true HUB_HL7_OUTBOUND_HOST=localhost ...
```

The listener has no TLS: keep it on a private network. Details and what is not built: docs/architecture.md, phase 4.

## Admin web and operations

```
cd web && npm install && npm run dev      # http://localhost:5701, signs in through ClinicHub
```

Needs ClinicHub running with the clients `integrationhub-admin` (and `integrationhub`) from its `appsettings.Development.json`,
and the hub started with `--spring.profiles.active=dev` (CORS for :5701) or `HUB_ADMIN_CORS_ORIGINS=http://localhost:5701`.
A person needs the ClinicHub permission *See the message log* (Administrator has it); *Replay an HL7 message* is separate.

- **Overview:** each route's health from the message log (a call failed when the hub answered 5xx), the HL7 queues, open alerts.
- **Messages:** search, then one call's facts, its timeline and the other calls with the same identifier. No bodies: the hub keeps none.
- **Dead letters:** HL7 messages given up on or refused by ClinicHub. They keep their encrypted payload for
  `HUB_HL7_REPLAY_WINDOW_DAYS` (default 7), so *Replay* puts one back in its queue; `0` drops the payload at once (no replay).
- **Alerts:** `route-down` (`HUB_ALERTS_ROUTE_DOWN_MIN_FAILURES` failed calls and no answer from the upstream in
  `HUB_ALERTS_ROUTE_DOWN_AFTER`) and `hl7-dead-letters`. Each goes to the log and, if `HUB_ALERTS_WEBHOOK_URL` is set, to that
  URL as JSON when it opens and when it clears. No e-mail: let the webhook send it.

`cd web && npm test` runs the web tests (Node 22). The hub, its database, RabbitMQ and the web run together in ControlPanel's
`compose.apps.yml`. The API is in docs/architecture.md, phase 6.

## Status

- [x] Phase 0 — skeleton, CI, Docker
- [x] Phase 1 — results relay (LabFlow, ClinicReport → ClinicHub) — hub done and seen with LabFlow (`ClinicHub:ResultsUrl`); ClinicReport's setting is still to be changed, see the phase in docs/architecture.md
- [x] Phase 2 — orders, cancels, financial documents — hub done and seen with ClinicHub and LabFlow (`Integrations:LabFlow:FhirBaseUrl`); the other senders' settings are still to be changed, see the phase in docs/architecture.md
- [x] Phase 3 — de-identified event feed to [Analytics](https://github.com/ArashKeshtgar/Analytics), metrics, search
- [x] Phase 4 — HL7 v2 in (external lab ORU over MLLP) and out (OLIS simulation) — hub done; no TLS on the listener, not tried with a real lab or OLIS, ClinicHub needs the `integrationhub` client, see the phase in docs/architecture.md
- [x] Phase 5 — e-prescriptions to PharmaFlow, patient merges, consent flags — hub done (contracts, route `pharmaflow`, log, pseudonym on merges); e-prescriptions seen end to end with ClinicHub and PharmaFlow; no application sends or reads merges or labels yet, see the phase in docs/architecture.md
- [x] Phase 6 — admin web with ClinicHub sign-in (`web/`, :5701), route health, message timeline, replay of HL7 dead letters, alerts to the log and a webhook, platform compose — no payload viewer, no e-mail alerts, replay only for HL7; see the phase in docs/architecture.md

All data in tests and samples is synthetic.
