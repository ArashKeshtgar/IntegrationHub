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
**As ClinicHub sends it** (`FhirOrders.Cancel`, seen in a real run): `POST {app}/fhir`, a transaction Bundle whose one
entry is the ServiceRequest again with `status: revoked`, the reason in a note, and `request` = `PUT
ServiceRequest?identifier=urn:clinichub:{clinic}:service-request|{id}`. A direct
`PUT {app}/fhir/ServiceRequest?identifier=…` (what this file first assumed) is relayed and recognised too. `200` = cancelled. **`409` + OperationOutcome = refused** (results entered, report started):
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

### 5. Electronic prescriptions — ClinicHub → PharmaFlow (phase 5; ClinicHub sends it since `701de46`, PharmaFlow does not receive it yet)
`POST {pharmaflow}/fhir`, transaction Bundle, through the hub route `pharmaflow` (`/route/pharmaflow/fhir`). ClinicHub
signs the token itself: `aud` `pharmaflow`, scope `prescriptions`, `clinic_slug`. The Bundle is built like an order's
(flow 1): a `Patient` (MRN `urn:clinichub:{clinic}:mrn`, health card, name, birth date, as in an order), a
`Practitioner` (CPSO), and one `MedicationRequest` per drug:

| Field | Content |
|---|---|
| `identifier` | `urn:clinichub:{clinic}:prescription` \| `{id}`. A Bundle sent again is the same prescription: PharmaFlow must not enter it twice |
| `groupIdentifier` | `urn:clinichub:{clinic}:prescription-group` \| `G{n}`: the drugs written together; also the Bundle's `identifier` |
| `status`, `intent` | `active`, `order` |
| `medicationCodeableConcept.coding` | the drug as a DIN: system `http://hl7.org/fhir/NamingSystem/ca-hc-din` (see open question 21) |
| `subject`, `requester` | the Patient and Practitioner of the Bundle |
| `authoredOn` | the date written |
| `dosageInstruction[0].text` | the directions (sig) |
| `dispenseRequest` | `quantity` (`value`, `unit`: capsule, tablet, inhaler, pen, gram), `numberOfRepeatsAllowed` (0 to 11), `expectedSupplyDuration` in days when given |
| `substitution.allowedBoolean` | `false` when the prescriber wrote *no substitution* |
| `note` | free text for the pharmacist, if any |

`200` with a `transaction-response` (the ids PharmaFlow made, as for an order) = received into reception as an
electronic prescription. **Cancelling** is the same Bundle again with `status: cancelled`, a note with the reason, and
`request` = `PUT MedicationRequest?identifier=urn:clinichub:{clinic}:prescription|{id}` (the way an order's cancel
is sent, flow 2); `200` = cancelled, **`409` + OperationOutcome = refused** (already dispensed or being filled): an
answer, not a failure, passed through and not retried.

The hub relays it unchanged and logs only the resource types, the prescription's id and its status. **Never** the drug,
the directions, the quantity, the doctor or the patient. A prescription makes **no event** for Analytics: what a patient
is prescribed says too much about them even without a name.

### 6. Patient merges — ClinicHub → every app that received an order or prescription for the merged record
When ClinicHub merges two patient records it sends, to each app that had something for the merged record (it knows:
the `TargetSystem` of the merged record's orders), through that app's route (`labflow`, `clinicreport`, `pharmaflow`),
a transaction Bundle with one entry:

```json
{ "resource": { "resourceType": "Patient", "active": false,
    "identifier": [{ "system": "urn:clinichub:harbourview:mrn", "value": "{merged MRN}" }],
    "link": [{ "type": "replaced-by",
               "other": { "identifier": { "system": "urn:clinichub:harbourview:mrn", "value": "{survivor MRN}" } } }] },
  "request": { "method": "PUT", "url": "Patient?identifier=urn:clinichub:harbourview:mrn|{merged MRN}" } }
```

Nothing else about the patient is sent (no name, health card or birth date). The receiving app repoints what it holds
for the merged MRN to the survivor's; `200` = done, and a patient it never had is also `200` (nothing to do). The hub
relays it unchanged. When the app answers 2xx the hub also moves the pseudonym it remembers for the merged patient's
orders to the survivor's, so results that come later carry the survivor's pseudonym. A merge makes no event, and events
already published keep the pseudonym they had (see [events.md](events.md)).

**Built in the applications (21 October 2026).** ClinicHub sends the merge to each application that had a *delivered*
order for the merged record (not the external lab: it has no merge message) and to PharmaFlow when a delivered
prescription was; it also moves the prescriptions to the surviving record, which it did not before. ClinicReport keys
its patients by the clinic's MRN: with both records known the merged one's reports and orders move and it is removed,
with only the merged one known it takes the survivor's MRN. LabFlow and PharmaFlow keep their own patients (health card,
or name and birth date) and now keep the clinic's MRN on each electronic order or prescription; a merge moves that MRN
and, when two of their patients stood behind the two MRNs, writes "possible duplicate" in the access log and does not
merge them on the clinic's word. All answer 200 for a record they never had, and 400 for a merge into itself.

### 7. Confidentiality labels — on orders, prescriptions and results (phase 5)
A patient's consent directive (ClinicHub's lockbox) that covers an order or prescription is sent as a label on the
resource: `meta.security` with a coding of the HL7 v3 Confidentiality system
`http://terminology.hl7.org/CodeSystem/v3-Confidentiality`: `R` (restricted) or `V` (very restricted). The receiving
app limits who may see the order and its result to people allowed by that label, and puts the **same label on the
DiagnosticReport** it sends back. An app that does not know a code treats it as `R`. Which directive becomes which
code is ClinicHub's decision (open question 23).

The hub relays labels unchanged. It logs the codes (a short code only: anything else is logged as `other`) in the
message's `securityLabels` and counts them: `hub.consent.labelled{route,code}`, so a privacy officer can see that
restricted items pass through. It does **not** check that a restricted order's result comes back labelled.

**Built (21 October 2026).** ClinicHub: the whole record withheld (lockbox on the entire record) is `V`; one visit
withheld, or the record withheld from a named provider, is `R` (question 23); no active directive, no label; the label
is taken when the order or prescription is written and again when an undelivered Bundle is rebuilt. ClinicReport, LabFlow
and PharmaFlow keep the label with the order, show it (worklist badge, reception badge, prescription detail) and put the
same `meta.security` on the DiagnosticReport they send back (PharmaFlow sends no result). A code that is not `R` or `V` is
read as `R`; `U`, `L`, `M`, `N` as no label.

**Enforced (21 October 2026): "break the glass".** A labelled item is closed to everyone, `R` and `V` alike, until a user
opens it for themselves with a reason: `403 {code: "restricted", label}` until `POST …/override {reason}` (at least 10
characters) is answered 200; the override is for that user and that item, lasts one hour, is kept in memory (a restart
closes it), and is written to the application's own audit log with who, what and why. Whoever turns an order into a
requisition or starts the report carries on with it. ClinicReport gates `POST /api/orders/{id}/start` and every
`/api/reports/{id}/…` route; LabFlow `POST /api/reception/electronic-orders/{id}/requisition` and every
`/api/lab/requisitions/{id}/…` route, and its worklist lists a closed requisition with the patient shown as "Restricted"
and no values; PharmaFlow `GET /api/prescriptions/{id}`, every action on it or its drugs and the dispensing of a drug,
and its queue lists a closed one with the patient hidden. The web screens ask why and retry. **What it does not cover:** the
lists in ClinicReport (worklist, report list) and LabFlow's reception list still show who and what was ordered (names
are what staff need to find the item), and the hub, ClinicHub and Analytics see only what they always saw.

### 8. Orders to an external lab — ClinicHub → hub → lab (phase 7)
`POST|PUT {hub}/route/externallab/fhir`, the same order Bundle as flow 1 (and the same cancel Bundle as flow 2), with a
token `aud` `externallab`, scope `orders`, `clinic_slug`. ClinicHub sends it when `Integrations:ExternalLab:FhirBaseUrl`
is set to this route; unset, the order is only recorded. The hub answers for the lab, after an ORM^O01 over MLLP and the
lab's ACK: `200` (AA/CA), `422` order refused (AE/AR), `409` cancellation refused, `502` the lab did not answer, `503` no
lab configured, `400` not an order. A `200` carries a transaction-response with one entry per Patient, Practitioner and
ServiceRequest, **no `location`** (the lab's ACK has no order id), so ClinicHub keeps no external id. The lab's result
comes back as flow in phase 4 (ORU^R01, flow 3's Bundle).

## Retry behaviour already in the senders

Every sender has an outbox: 10 s, 20 s, 40 s… up to 30 min, *Not delivered* after 12 tries, manual *Try again now*.
So for flows 1–4 the hub **does not add its own retry**: it answers with the upstream's status (or `502`/`504`
when the upstream is unreachable), and the sender's outbox retries.

## Known gaps in the platform (candidates for phase 5)

- Electronic prescriptions ClinicHub → PharmaFlow: contract and route in the hub (flow 5); ClinicHub has no
  prescribing yet and PharmaFlow no intake or sign-in through ClinicHub.
- ~~Patient merges are not sent~~ and ~~consent directives are not sent with orders~~: both built in the applications on
  21 October 2026 (flows 6 and 7); the applications show the label but do not yet enforce it.
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
9. **Answered (FinancePowerApp `AuthSetup.DocumentSourceAsync`, 2026-10-21):** `X-Finance-Key` is accepted **instead
   of** a token, in Development only, and only when the call has no `Bearer` header; a call with a token is judged by
   the token alone. ClinicHub, LabFlow and ClinicReport send a token; PharmaFlow sends only the key
   (`Finance:DevKey`). The `dev` profile therefore sets `hub.routes.finance.api-key-header: X-Finance-Key`.
10. Finance tokens from the other applications (client credentials): `aud` = `finance`, scope `finance`, as written
    above; the finance route requires both.
11. **Answered:** yes. ClinicHub's `Integrations:*:FhirBaseUrl` hold the full base ending in `/fhir` and are posted to
    as they are (cancels too: a Bundle POSTed to the same address); LabFlow's and ClinicReport's `ResultsUrl` likewise.
    The finance settings hold the base without a path, and the senders append `/api/integration/documents`.
12. How does the .NET client write the cancel's query (`|` raw or `%7C`; `:` raw or `%3A`)? Both are relayed; the
    log understands both.
13. Order Bundle (phase 3): the Patient's MRN is an identifier with system `urn:clinichub:{clinic}:mrn`. If it is
    another system, set `hub.events.mrn-system`; without it events have no pseudonym.
14. Order Bundle: each ServiceRequest has `identifier` = `urn:clinichub:{clinic}:service-request|{id}` (the same one a
    cancel names in its query and a result names in `basedOn`). Events and the order memory depend on it.
15. The first `coding` of a ServiceRequest / DiagnosticReport `code` is the test (LOINC for LabFlow, the template key for
    ClinicReport); `priority` is the FHIR value (`routine`, `urgent`, `asap`, `stat`).
16. HL7 v2 (phase 4): the placer order number in ORC-2/OBR-2 of a result from an external lab is
    `{order id}^{clinic slug}` (`101^harbourview`) or the whole identifier
    `urn:clinichub:harbourview:service-request|101` (written with `\F\` for the bar). ClinicHub numbers an external-lab
    order by its own order id, so these match it. Nothing sends the number to the lab yet: ClinicHub does not send
    external-lab orders and the hub has no ORM^O01.
17. HL7 v2: ClinicHub accepts a results Bundle from the client `integrationhub` (client credentials, scope `results`)
    at `POST {clinichub}/fhir`, for orders placed to its `ExternalLab` target (and only those, at the clinic named in
    `basedOn`), and its token endpoint is `{issuer}connect/token`. Built in ClinicHub and seen with the hub on 19 October 2026.
18. HL7 v2: the same Bundle sent twice (the hub's retry after a lost answer) is answered 2xx or ignored by ClinicHub,
    not 4xx. (Read in ClinicHub's code, not run: the result is found by order and report identifier and replaced, answer 201. A resend with the same control id never reaches ClinicHub, the hub answers it itself.) If it answers 4xx the hub treats the result as refused (`REJECTED`) and tells the lab AR.
19. OLIS: the real message profile is not known to the hub (required fields, identifiers). The export is for
    `tools/olis-sim` only.
20. Prescriptions (phase 5): ClinicHub needs a token for `aud` `pharmaflow`, scope `prescriptions` (like the one for
    LabFlow's orders), and PharmaFlow has to accept it: today it has no sign-in through ClinicHub.
21. The DIN system name: `http://hl7.org/fhir/NamingSystem/ca-hc-din` is the one HL7 registers for Health Canada's
    Drug Identification Number; PharmaFlow's drug catalog may key drugs differently (see its mapping study).
22. **Answered (21 Oct 2026):** ClinicReport keys its patients by MRN. LabFlow and PharmaFlow did not keep it; they
    do now (`SourceMrn` on the electronic order / prescription, scripts 055 and 051), so a merge has something to find.
23. **Answered:** entire record `V`, one visit `R`, one provider `R`; ClinicReport and LabFlow return the label on their
    results (see flow 7).
24. The hub's pseudonym move on a merge needs ClinicHub's MRN system to be `hub.events.mrn-system` (flow 6 uses
    `urn:clinichub:{clinic}:mrn`, as an order's Patient does).
25. Orders to an external lab (phase 7): ClinicHub's placer number is `{order id}^{clinic slug}` in ORC-2/OBR-2 and the
    lab has to quote it back in its result (question 16). The hub sends the patient's name, birth date, sex, MRN and
    health card number and the orderer's CPSO number to the lab in the ORM^O01; that is the purpose of the message, and
    the hub neither stores nor logs it. Whether a given lab accepts that profile is not known.
