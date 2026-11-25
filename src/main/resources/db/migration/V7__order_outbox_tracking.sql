-- 3-6: the outbox row names its acceptance (so a stalled acceptance can be republished as a new row and its sends
-- counted) and records where the broker put it (so the stall check can see whether the consumer group has passed it).
ALTER TABLE order_outbox
    ADD COLUMN acceptance_id BIGINT NULL,
    ADD COLUMN partition_no  INT    NULL,
    ADD COLUMN record_offset BIGINT NULL;

-- Rows written before this version carry the acceptance only in their payload.
UPDATE order_outbox o
    JOIN order_acceptances a
    ON a.public_id = UNHEX(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.acceptanceId')), '-', ''))
SET o.acceptance_id = a.id;

ALTER TABLE order_outbox
    MODIFY acceptance_id BIGINT NOT NULL,
    ADD INDEX ix_order_outbox_acceptance (acceptance_id, id),
    ADD CONSTRAINT fk_order_outbox_acceptance FOREIGN KEY (acceptance_id) REFERENCES order_acceptances (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT ck_order_outbox_position CHECK (
        (partition_no IS NULL AND record_offset IS NULL) OR
        (partition_no >= 0 AND record_offset >= 0 AND published_at IS NOT NULL));

-- The stall check reads the unfinished acceptances; without this it would scan every acceptance ever stored.
ALTER TABLE order_acceptances
    ADD INDEX ix_order_acceptances_status (status, id);
