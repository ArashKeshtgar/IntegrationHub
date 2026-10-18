# Contracts the hub relays

What the platform's applications already send each other, as built on 2026-10-17. The hub must relay these
**unchanged**: same paths after the route prefix, same bodies, same tokens, same answers. Sources are the other
repositories' docs, chiefly ClinicHub's [docs/architecture.md](https://github.com/ArashKeshtgar/ClinicHub/blob/main/docs/architecture.md).
A Claude Code session on this repository usually cannot see those repositories, so this file is the reference;
update it when one of them changes a contract.

## Applications (development)

| Application | Repository | API | Web | Database |
|---|---|---|---|---|
| ClinicHub (sign-in, patients, orders, billing) | ArashKeshtgar/ClinicHub | http://localhost:5300 | http://localhost:5301 (Vite; also the OIDC issuer) | SQL Server: catalog + one database per clinic |
| ClinicReport (echo, ECG reports) | ArashKeshtgar/ClinicReport | http://localhost:5290 | http://localhost:5291 | SQLite, `clinicreport-{clinic}.db` in connected mode |
| LabFlow (lab) | ArashKeshtgar/LabFlow | http://localhost:5180 | http://localhost:5174 | SQL Server, one lab DB or one per clinic |
| FinancePowerApp (group accounting) | local only, no GitHub remote yet | http://localhost:5400 | http://localhost:5401 | SQL Server `FinancePowerApp` |
| PharmaFlow (pharmacy) | ArashKeshtgar/Drugstore | http://localhost:5500 | http://localhost:5501 | SQL Server |
| **IntegrationHub** (this) | ArashKeshtgar/IntegrationHub | http://localhost:5700 | http://localhost:5701 (phase 6) | PostgreSQL |
| Analytics | ArashKeshtgar/Analytics | http://localhost:5600 | http://localhost:5601 | DuckDB |

## Tokens

- Issuer: `http://localhost:5301/` (ClinicHub, OpenIddict). Keys: `{issuer}.well-known/jwks`. Discovery:
  `{issuer}.well-known/openid-configuration`. Access tokens are signed JWTs, not encrypted.
- **Orders** (ClinicHub → app): ClinicHub signs a 5-minute token itself; `aud` = the app's client id (`labflow`,
  `clinicreport`), scope `orders`, claim `clinic_slug`.
- **Results** (app → ClinicHub): the app gets a token by `POST /connect/token`, `grant_type=client_credentials`,
  its own client id and secret, `scope=results`.
- **Finance** (any app → FinancePowerApp): `aud` = `finance`, scope `finance`; ClinicHub signs its own, the others
  use client credentials. In development FinancePowerApp also accepts `X-Finance-Key`.
- Dev client secrets live in ClinicHub's `appsettings.Development.json` (`Sso:Clients`), e.g. `labflow`,
  `clinicreport`. New clients (this hub, Analytics) are registered there.

## Flows

### 1. Orders — ClinicHub → LabFlow / ClinicReport
`POST {app}/fhir` with a FHIR R4 **transaction Bundle**: Patient (clinic MRN, Ontario health card), Practitioner
(CPSO, OHIP billing number), one ServiceRequest per test (LOINC or LabFlow code; ClinicReport template key), note,
priority. The answer is a **transaction-response** whose `location`s ClinicHub stores as the app's ids — so this
call must stay **synchronous** through the hub. Receivers recognise a resent Bundle.
ClinicHub config: `Integrations:LabFlow:FhirBaseUrl`, `Integrations:ClinicReport:FhirBaseUrl`.

### 2. Cancelling — ClinicHub → LabFlow / ClinicReport
`PUT {app}/fhir/ServiceRequest?identifier=urn:clinichub:{clinic}:service-request|{id}` with `status: revoked` and
the reason in a note. `200` = cancelled. **`409` + OperationOutcome = refused** (results entered, report started):
an answer, not a failure — the hub must pass it through and must not retry it.

### 3. Results — LabFlow / ClinicReport → ClinicHub
`POST {clinichub}/fhir`, transaction Bundle, one DiagnosticReport per ordered test, `basedOn`
`urn:clinichub:{clinic}:service-request|{id}` (this names the clinic database). LabFlow adds Observations (LOINC,
value, unit, range, H/L). ClinicReport sends conclusion, addenda and `presentedForm` link. `final`, then `amended`;
`cancelled` cancels. ClinicHub accepts a result only from the app the order went to — it decides that from the
token's client, so the hub must forward the sender's own token.
Senders' outboxes: LabFlow `lab.OutboundMessage` (database/051), ClinicReport `ResultMessages`.

### 4. Financial documents — every app → FinancePowerApp
`POST {finance}/api/integration/documents`, JSON array (not FHIR):

```json
{ "sourceKey": "harbourview", "externalId": "claim:1234:ready", "kind": "Invoice", "date": "2026-10-05",
  "reference": "AB12-001234", "party": { "type": "OHIP", "code": "OHIP" },
  "lines": [ { "category": "OHIP.Professional", "amount": 37.95, "taxCode": "EXEMPT", "providerCode": "123456", "department": "Clinic" } ] }
```

Kinds: Invoice, Receipt, Adjustment, Bill, Payment, Usage, Reversal. `externalId` is idempotent per app. **No
health information** in these documents. ClinicHub config: `Integrations:Finance:BaseUrl`.

## Retry behaviour already in the senders

Every sender has an outbox: 10 s, 20 s, 40 s… up to 30 min, *Not delivered* after 12 tries, manual *Try again now*.
So for flows 1–4 the hub **does not add its own retry**: it answers with the upstream's status (or `502`/`504`
when the upstream is unreachable), and the sender's outbox retries.

## Known gaps in the platform (candidates for phase 5)

- Electronic prescriptions ClinicHub → PharmaFlow: not built (PharmaFlow README).
- Patient merges are not sent to LabFlow/ClinicReport (FHIR `Patient.link` not built).
- Consent directives are not sent with orders.
- LabFlow: "HL7 v2 ORU export (OLIS simulation)" is open.

## Identifier systems

Defined in ClinicHub `src/ClinicHub.Api/Integrations/OrderCatalog.cs` (`FhirSystems`). Service requests:
`urn:clinichub:{clinic}:service-request`. Ontario health card and CPSO use the Canadian baseline systems; OHIP billing
number and the health card version-code extension are ClinicHub URNs.

## Open questions for the hub

The relay (phase 1) is built on these assumptions about ClinicHub's tokens. Check them with a real results token
(its payload is JSON between the two dots, base64url) and correct this file.

1. Signing algorithm: `RS256`. If ClinicHub signs with another, `TokenValidator` must list it.
2. Header `typ`: `at+jwt` (OpenIddict). Accepted, as are `JWT` and none.
3. `scope`: a space-separated string (`"results"`); a JSON array is accepted too.
4. Client id claim: `client_id`, else `azp`, else `sub`. It is only used for the log.
5. `aud` of a results token (client credentials): unknown. Route `clinichub` therefore does **not** check the
   audience (`allowed-audiences` empty). Put it in the route once known.
6. Does a results token carry `clinic_slug`? Not needed: the log takes the clinic from the `basedOn` identifier.
7. `iss` is exactly `http://localhost:5301/` (trailing slash) and the keys are at `…/.well-known/jwks`.
8. Order tokens (phase 2): ClinicHub signs them itself with `aud` = `labflow` / `clinicreport` and scope `orders`
   (contracts above). If a ClinicHub-signed token has no `client_id` the log shows `azp` or `sub` as the sender.
9. `X-Finance-Key` in development: is it accepted **instead of** a token (then set
   `HUB_ROUTE_FINANCE_API_KEY_HEADER=X-Finance-Key` for development) or besides one? Which applications send which?
10. Finance tokens from the other applications (client credentials): `aud` = `finance`, scope `finance`, as written
    above; the finance route requires both.
11. Does the sender's FHIR base setting end in `/fhir`? The hub relays only `/route/{app}/fhir…`.
12. How does the .NET client write the cancel's query (`|` raw or `%7C`; `:` raw or `%3A`)? Both are relayed; the
    log understands both.

