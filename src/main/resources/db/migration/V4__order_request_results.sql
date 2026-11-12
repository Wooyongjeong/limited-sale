CREATE TABLE order_request_results
(
    id               BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id        BIGINT NOT NULL,
    request_key      BINARY(16) NOT NULL,
    sale_id          BIGINT NOT NULL,
    quantity         INT NOT NULL,
    outcome          VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    order_id         BIGINT,
    rejection_reason VARCHAR(32) COLLATE utf8mb4_bin,
    created_at       DATETIME(6) NOT NULL,
    CONSTRAINT uk_order_request_results_member_key UNIQUE (member_id, request_key),
    CONSTRAINT fk_order_request_results_member FOREIGN KEY (member_id) REFERENCES members (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_order_request_results_order FOREIGN KEY (order_id) REFERENCES purchase_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_order_request_results_quantity CHECK (quantity BETWEEN 1 AND 1000000),
    CONSTRAINT ck_order_request_results_outcome CHECK (
        (BINARY outcome = 'CREATED' AND order_id IS NOT NULL AND rejection_reason IS NULL) OR
        (BINARY outcome = 'REJECTED' AND order_id IS NULL AND rejection_reason IS NOT NULL AND
         BINARY rejection_reason IN ('SALE_NOT_FOUND', 'SALE_OUTSIDE_PERIOD', 'SALE_STOPPED',
                                     'PURCHASE_LIMIT_EXCEEDED', 'SALE_SOLD_OUT'))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
