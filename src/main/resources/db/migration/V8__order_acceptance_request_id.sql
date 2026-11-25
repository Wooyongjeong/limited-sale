-- 3-7: the HTTP request id of the request that created the acceptance, so the consumer's logs carry the same id.
-- Acceptances stored before this version have none.
ALTER TABLE order_acceptances
    ADD COLUMN request_id VARCHAR(64) COLLATE utf8mb4_0900_bin NULL,
    ADD CONSTRAINT ck_order_acceptances_request_id CHECK (request_id IS NULL OR CHAR_LENGTH(request_id) BETWEEN 1 AND 64);
