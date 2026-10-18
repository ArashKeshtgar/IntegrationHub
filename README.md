# IntegrationHub

The interface engine of the [ClinicHub](https://github.com/ArashKeshtgar/ClinicHub) platform: orders, cancels, results
and financial documents between ClinicHub, [LabFlow](https://github.com/ArashKeshtgar/LabFlow),
[ClinicReport](https://github.com/ArashKeshtgar/ClinicReport), FinancePowerApp and
[PharmaFlow](https://github.com/ArashKeshtgar/Drugstore) pass through it, so there is one log of what was sent and
what came back, and one place that speaks HL7 v2 to the outside world.

Java 21 · Spring Boot 3 · HAPI FHIR R4 / HL7 v2 · PostgreSQL · RabbitMQ · Testcontainers.

- Plan and phases: [docs/architecture.md](docs/architecture.md)
- What the applications send each other today: [docs/contracts.md](docs/contracts.md)

## Run it

Needs Java 21, Maven and Docker.

```
docker compose up -d --wait      # PostgreSQL 16 on :5433
mvn spring-boot:run              # API on :5700
curl localhost:5700/actuator/health
```

`mvn -B verify` runs the tests (Testcontainers starts its own PostgreSQL, so Docker must be running) and the
Spotless format check; `mvn spotless:apply` fixes formatting. Settings: `HUB_DB_URL`, `HUB_DB_USER`, `HUB_DB_PASSWORD`.
Image: `docker build -t integrationhub .`

## Status

- [x] Phase 0 — skeleton, CI, Docker
- [ ] Phase 1 — results relay (LabFlow, ClinicReport → ClinicHub)
- [ ] Phase 2 — orders, cancels, financial documents
- [ ] Phase 3 — de-identified event feed to [Analytics](https://github.com/ArashKeshtgar/Analytics), metrics, search
- [ ] Phase 4 — HL7 v2 in (external lab ORU) and out (OLIS simulation)
- [ ] Phase 5 — e-prescriptions to PharmaFlow, patient merges, consent flags
- [ ] Phase 6 — admin web with ClinicHub sign-in, replay, alerts

All data in tests and samples is synthetic.
