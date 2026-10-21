-- Phase 6: replay of queued HL7 messages, and alerts.
--
-- A row that was given up (FAILED) or refused by ClinicHub (REJECTED) now keeps its encrypted payload for
-- hub.hl7.replay-window-days from failed_at, so an operator can replay it once the cause is fixed. After that
-- the payload is set to NULL as before (the row itself stays until hub.hl7.retention-days). The columns below
-- say who replayed a row and when; the person is the sign-in subject (ClinicHub account id), never a name.
ALTER TABLE hl7_inbound
    ADD COLUMN failed_at         timestamptz,
    ADD COLUMN replay_count      integer NOT NULL DEFAULT 0,
    ADD COLUMN last_replayed_at  timestamptz,
    ADD COLUMN last_replayed_by  varchar(100);

ALTER TABLE hl7_outbound
    ADD COLUMN failed_at         timestamptz,
    ADD COLUMN replay_count      integer NOT NULL DEFAULT 0,
    ADD COLUMN last_replayed_at  timestamptz,
    ADD COLUMN last_replayed_by  varchar(100);

-- Alerts: one open row per (kind, subject); resolved_at is set when the condition clears. The detail is a fixed
-- phrase with counts, never content. notified_at / resolved_notified_at: the webhook has been told.
CREATE TABLE alert (
    id                   uuid         PRIMARY KEY,
    kind                 varchar(30)  NOT NULL,   -- route-down, hl7-dead-letters
    subject              varchar(100) NOT NULL,   -- a route name, or inbound / outbound
    opened_at            timestamptz  NOT NULL DEFAULT now(),
    resolved_at          timestamptz,
    detail               varchar(300) NOT NULL,
    notified_at          timestamptz,
    resolved_notified_at timestamptz
);

CREATE UNIQUE INDEX uq_alert_open ON alert (kind, subject) WHERE resolved_at IS NULL;
CREATE INDEX ix_alert_opened ON alert (opened_at DESC);
