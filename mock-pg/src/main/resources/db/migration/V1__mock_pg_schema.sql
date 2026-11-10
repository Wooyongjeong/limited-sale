CREATE TABLE mock_payments (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, pg_key BINARY(16) NOT NULL, amount BIGINT NOT NULL,
 status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL, scenario VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
 completion_due_at DATETIME(6), response_loss_consumed BOOLEAN NOT NULL DEFAULT FALSE, finalized_at DATETIME(6),
 version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_mock_payments_pg_key UNIQUE (pg_key),
 CONSTRAINT ck_mock_amount CHECK (amount BETWEEN 1 AND 1000000000000000),
 CONSTRAINT ck_mock_status CHECK (status IN ('PROCESSING','SUCCEEDED','FAILED','CLOSED')),
 CONSTRAINT ck_mock_scenario CHECK (scenario IN ('SUCCESS','DECLINE','DELAY','RESPONSE_LOSS')),
 CONSTRAINT ck_mock_response_loss CHECK (response_loss_consumed IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
