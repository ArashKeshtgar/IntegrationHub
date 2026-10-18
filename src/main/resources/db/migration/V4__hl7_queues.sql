-- HL7 v2 store-and-forward (docs/architecture.md, phase 4).
--
-- A result that arrives over MLLP is mapped to FHIR and kept here until ClinicHub has taken it, so a ClinicHub
-- that is down for an hour does not lose it. The payload is health information: it is encrypted (AES-256-GCM,
-- key from HUB_HL7_QUEUE_KEY) and the column is set to NULL as soon as the row is delivered or given up.
-- Nothing in the other columns identifies a patient.
CREATE TABLE hl7_inbound (
    id              uuid         PRIMARY KEY,
    received_at     timestamptz  NOT NULL DEFAULT now(),
    sender          varchar(100) NOT NULL,      -- MSH-3.MSH-4, e.g. EXTLAB.LAB1
    control_id      varchar(100) NOT NULL,      -- MSH-10
    status          varchar(12)  NOT NULL,      -- PENDING, DELIVERED, REJECTED (ClinicHub said no), FAILED (gave up)
    report_count    integer      NOT NULL,
    attempts        integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    payload         bytea,
    delivered_at    timestamptz,
    last_status     integer,                    -- ClinicHub's last HTTP status
    last_error      varchar(200),               -- a fixed reason, never content
    CONSTRAINT uq_hl7_inbound UNIQUE (sender, control_id)
);

CREATE INDEX ix_hl7_inbound_due ON hl7_inbound (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_hl7_inbound_received ON hl7_inbound (received_at);

-- ORU^R01 copies on their way to an OLIS-style receiver (the simulator in tools/olis-sim). The message is
-- encrypted the same way and cleared once sent. The patient in it is the pseudonym, never an MRN or a name.
CREATE TABLE hl7_outbound (
    id              uuid         PRIMARY KEY,
    message_id      uuid,                       -- the relayed call this came from
    created_at      timestamptz  NOT NULL DEFAULT now(),
    status          varchar(12)  NOT NULL,      -- PENDING, SENT, FAILED
    attempts        integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    payload         bytea,
    sent_at         timestamptz,
    last_error      varchar(200)
);

CREATE INDEX ix_hl7_outbound_due ON hl7_outbound (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_hl7_outbound_created ON hl7_outbound (created_at);
