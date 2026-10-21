-- De-identified events waiting for RabbitMQ. A row is written in the same transaction as the log row
-- of the call that caused it, so an event is not lost when the broker is down.
-- The id is derived from the event's content: a resent call does not make a second event.
CREATE TABLE event_outbox (
    id              uuid         PRIMARY KEY,
    message_id      uuid,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    event_type      varchar(60)  NOT NULL,
    payload         jsonb        NOT NULL,
    attempts        integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    published_at    timestamptz,
    last_error      varchar(200)
);

CREATE INDEX ix_event_outbox_due ON event_outbox (next_attempt_at) WHERE published_at IS NULL;
CREATE INDEX ix_event_outbox_published ON event_outbox (published_at) WHERE published_at IS NOT NULL;

-- What a later call needs to know about an order: results and cancels do not carry the patient, so the
-- pseudonym, test and priority are remembered from the order. De-identified: no MRN, no name.
CREATE TABLE order_link (
    clinic_slug       varchar(60)  NOT NULL,
    order_id          varchar(200) NOT NULL,
    patient_pseudonym char(64),
    application       varchar(60),
    test_code_system  varchar(200),
    test_code         varchar(100),
    priority          varchar(20),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (clinic_slug, order_id)
);
