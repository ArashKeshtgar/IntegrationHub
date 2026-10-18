-- The message log: metadata only. Bodies, names, health card numbers and result values are never stored here.
CREATE TABLE message (
    id               uuid         PRIMARY KEY,
    received_at      timestamptz  NOT NULL DEFAULT now(),
    route            varchar(60)  NOT NULL,
    direction        varchar(10)  NOT NULL CHECK (direction IN ('inbound', 'outbound')),
    sender_client_id varchar(100),
    clinic_slug      varchar(60),
    method           varchar(10)  NOT NULL,
    path             text         NOT NULL,
    content_type     varchar(200),
    request_size     bigint,
    request_sha256   char(64),
    bundle_id        varchar(100),
    resource_summary jsonb,
    upstream_status  integer,
    latency_ms       integer,
    error            text
);

CREATE INDEX ix_message_received_at ON message (received_at DESC);
CREATE INDEX ix_message_route_status ON message (route, upstream_status);
CREATE INDEX ix_message_clinic ON message (clinic_slug, received_at DESC);
CREATE INDEX ix_message_bundle ON message (bundle_id);

-- Identifiers found in a message (ServiceRequest, externalId), for searching.
CREATE TABLE message_identifier (
    message_id uuid         NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    system     varchar(200) NOT NULL,
    value      varchar(200) NOT NULL,
    PRIMARY KEY (message_id, system, value)
);

CREATE INDEX ix_message_identifier_lookup ON message_identifier (system, value);
