# IntegrationHub

The interface engine of the [ClinicHub](https://github.com/ArashKeshtgar/ClinicHub) platform: orders, cancels, results
and financial documents between ClinicHub, [LabFlow](https://github.com/ArashKeshtgar/LabFlow),
[ClinicReport](https://github.com/ArashKeshtgar/ClinicReport), FinancePowerApp and
[PharmaFlow](https://github.com/ArashKeshtgar/Drugstore) pass through it, so there is one log of what was sent and
what came back, and one place that speaks HL7 v2 to the outside world.

Java 21 · Spring Boot 3 · HAPI FHIR R4 / HL7 v2 · PostgreSQL · RabbitMQ · Testcontainers.

- Plan and phases: [docs/architecture.md](docs/architecture.md)
- What the applications send each other today: [docs/contracts.md](docs/contracts.md)

## Status

- [ ] Phase 0 — skeleton, CI, Docker
- [ ] Phase 1 — results relay (LabFlow, ClinicReport → ClinicHub)
- [ ] Phase 2 — orders, cancels, financial documents
- [ ] Phase 3 — de-identified event feed to [Analytics](https://github.com/ArashKeshtgar/Analytics), metrics, search
- [ ] Phase 4 — HL7 v2 in (external lab ORU) and out (OLIS simulation)
- [ ] Phase 5 — e-prescriptions to PharmaFlow, patient merges, consent flags
- [ ] Phase 6 — admin web with ClinicHub sign-in, replay, alerts

All data in tests and samples is synthetic.
