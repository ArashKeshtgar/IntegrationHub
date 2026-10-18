-- The status the hub answered the sender with. upstream_status stays empty when nothing was forwarded
-- (refused token, unknown route, upstream unreachable).
ALTER TABLE message ADD COLUMN response_status integer;
CREATE INDEX ix_message_response_status ON message (route, response_status);
