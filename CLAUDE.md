# Working on IntegrationHub

- The plan is [docs/architecture.md](docs/architecture.md). Work one phase at a time, in order. Before starting,
  read the phase table; when a phase is done, mark it **Done** there and tick it in the README. Say plainly when a
  promised piece is not built.
- The other applications are .NET and live in other repositories, usually not available in this session.
  [docs/contracts.md](docs/contracts.md) is the reference for what they send. Never change a contract on the hub
  side: relay paths, bodies, status codes and tokens unchanged. When a phase needs a change in another repository,
  write it down under that phase ("Other repositories") instead of guessing at their code.
- Health information: never log bodies, names, health card numbers, birth dates, notes or result values. Test data
  is synthetic; never add real patient data.
- Every change comes with tests (JUnit 5, WireMock for upstreams, Testcontainers for PostgreSQL/RabbitMQ).
  `mvn -B verify` must pass before committing.
- Ports: API 5700, admin web 5701, PostgreSQL 5433, MLLP 2575. Docs are in English, in the plain style of the
  ClinicHub docs.
