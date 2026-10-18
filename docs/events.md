# Event feed

IntegrationHub publishes **de-identified** events about what it relays, for
[Analytics](https://github.com/ArashKeshtgar/Analytics) (its phase 4) and anything else that wants them. This file is the
contract; the producer is `ca.arashkeshtgar.integrationhub.events`.

## Where

| | |
|---|---|
| Broker | RabbitMQ (development: `localhost:5672`, management UI `:15672`, `guest`/`guest`; `compose.yml`) |
| Exchange | `clinical.events`, type **topic**, durable |
| Routing key | the event `type`, e.g. `result.final` (bind `#` for all, `result.*` for results) |
| Queue | the hub declares the durable queue `analytics.clinical-events` bound with `#`, so events published before the consumer first starts wait there. A consumer declares the **same queue** (durable, no arguments) or its own. |
| Message | JSON, `content-type: application/json`, persistent, `message-id` = `eventId`, `type` = event type, header `schemaVersion` |
| Switch | `HUB_EVENTS_ENABLED=true` and `HUB_EVENTS_PSEUDONYM_SECRET` (32+ characters). Off by default. |

Delivery is **at least once**: an event can arrive twice (a publisher confirm was lost, a sender resent a call), so
a consumer ignores an `eventId` it has seen. Order across events is not guaranteed; use `occurredAt`.

## Schema, version 1

```json
{
  "schemaVersion": 1,
  "eventId": "3f2c0a0e-5d0b-3c8e-9a58-0e2e9d7b1a11",
  "type": "result.final",
  "occurredAt": "2026-10-18T14:03:22.418Z",
  "clinicSlug": "harbourview",
  "application": "labflow",
  "orderId": "service-request|101",
  "testCodeSystem": "http://loinc.org",
  "testCode": "718-7",
  "priority": "routine",
  "status": "final",
  "patientPseudonym": "9b0c…64 hex characters…",
  "finance": null
}
```

| Field | Meaning |
|---|---|
| `schemaVersion` | `1`. A change that removes or renames a field, or changes its meaning, is a new version; adding a field is not. |
| `eventId` | A UUID made from the content (type, clinic, order, body hash), so the same call sent twice is the same event. |
| `type` | See below. |
| `occurredAt` | When the hub received the call (UTC). |
| `clinicSlug` | ClinicHub's clinic slug, from the order identifier `urn:clinichub:{clinic}:service-request`. `null` for `finance.document`. |
| `application` | The application the order went to (`labflow`, `clinicreport`), for results and cancels remembered from the order; for `finance.document` the sender's client id. |
| `orderId` | `service-request|{id}`, the order's id in ClinicHub; the clinic is in `clinicSlug`. `null` for finance. |
| `testCodeSystem`, `testCode` | The first coding of the ServiceRequest or DiagnosticReport (LOINC, or ClinicReport's template key). |
| `priority` | `routine`, `urgent`, `asap`, `stat` from the order; `null` where the order is not known to the hub. |
| `status` | The order's status (`active`, `revoked`), or the report's (`final`, `amended`). |
| `patientPseudonym` | `hex(HMAC-SHA256(secret, clinic + "\|" + MRN))`: the same patient has the same value in every event of that clinic. `null` when the order was not seen by the hub or carried no MRN. |
| `finance` | Only `finance.document`: `{ "sourceKey", "externalId", "kind" }`. Never an amount, party or reference. |

**Never in an event:** names, health card numbers, birth dates, notes, result values, amounts, free text. A
pseudonym cannot be reversed without the secret; the secret stays with the hub. Do not join pseudonyms across
clinics (the clinic is part of the input, so they differ).

## Types

| `type` | When | Per |
|---|---|---|
| `order.placed` | An order Bundle was relayed and the application **answered** (any status). | ServiceRequest |
| `order.accepted` | The same, answered `2xx`. | ServiceRequest |
| `order.cancelled` | A cancel (`PUT …/ServiceRequest?identifier=…`) answered `2xx`. | cancel |
| `order.cancel-refused` | A cancel answered `409` (results entered, report started). | cancel |
| `result.final` | A DiagnosticReport with status `final` relayed to ClinicHub, answered `2xx`. | DiagnosticReport |
| `result.amended` | The same with status `amended`. Other statuses (`cancelled`, `preliminary`, …) make no event. | DiagnosticReport |
| `finance.document` | A financial document relayed to FinancePowerApp, answered `2xx`. | document |

A call the application did **not answer** (unreachable, timeout) makes no event: the sender retries, and the retry
makes it once. Calls refused by the hub (no token, wrong scope) make none. A resent identical call is the same
`eventId`, so it is one event after de-duplication.

`order.placed` and `order.accepted` differ only when the application refuses an order (`4xx`, `5xx`): then there is
a `placed` and no `accepted` (a `5xx` is retried by the sender and may become `accepted` later).

**Not in the feed (phase 5):** prescriptions (a drug says too much about a patient, even without a name) and patient
merges. A merge answered `2xx` does change what the hub remembers: the pseudonym of the merged patient's orders becomes
the survivor's, so **results that arrive after the merge** carry the survivor's pseudonym. Events already published keep
the pseudonym they had, and nothing in the feed says that two pseudonyms are one person: a consumer that counts
patients will count a merged patient twice for what happened before the merge.

## How the pseudonym reaches results

A result or a cancel does not carry the patient. When an order is relayed the hub remembers, per
`(clinic, order id)`, the pseudonym, application, test and priority (table `order_link`: no MRN, no name), and uses
them for the events of later results and cancels of that order. A result for an order the hub never saw has a `null`
pseudonym and priority. The MRN is read from the Patient identifier whose system is `hub.events.mrn-system`
(default `urn:clinichub:{clinic}:mrn`); the health card number is never read.

## Reliability

Events are written to the table `event_outbox` in the same database transaction as the call's log row, then sent by a
publisher with publisher confirms, oldest first, retrying with a growing pause (1 s … 5 min) while the broker is
down. Published rows are deleted after `hub.events.retention-days` (7); remembered orders (`order_link`) after
`hub.events.order-link-retention-days` (365), so a result later than that has no pseudonym. Metrics: `hub.events.outbox.pending`,
`hub.events.outbox.oldest.age.seconds`, `hub.events.published{result=ok|failed}` at `/actuator/prometheus`.
