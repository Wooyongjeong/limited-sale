CREATE TABLE mock_scenario_settings (
    pg_key BINARY(16) NOT NULL PRIMARY KEY,
    scenario VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    delay_seconds INT,
    CONSTRAINT ck_mock_settings_scenario CHECK (scenario IN ('SUCCESS', 'DECLINE', 'DELAY', 'RESPONSE_LOSS')),
    CONSTRAINT ck_mock_settings_delay CHECK (
        (scenario IN ('SUCCESS', 'DECLINE') AND delay_seconds IS NULL)
        OR (scenario IN ('DELAY', 'RESPONSE_LOSS') AND delay_seconds IS NOT NULL AND delay_seconds BETWEEN 1 AND 600)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO mock_scenario_settings (pg_key, scenario, delay_seconds)
SELECT pg_key, scenario,
    CASE scenario WHEN 'DELAY' THEN 10 WHEN 'RESPONSE_LOSS' THEN 5 ELSE NULL END
FROM mock_payments;
