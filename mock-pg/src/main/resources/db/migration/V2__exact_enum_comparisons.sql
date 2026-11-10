ALTER TABLE mock_payments
    DROP CHECK ck_mock_status,
    DROP CHECK ck_mock_scenario,
    MODIFY status VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY scenario VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    ADD CONSTRAINT ck_mock_status CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'CLOSED')),
    ADD CONSTRAINT ck_mock_scenario CHECK (scenario IN ('SUCCESS', 'DECLINE', 'DELAY', 'RESPONSE_LOSS'));
