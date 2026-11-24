CREATE TABLE order_acceptances
(
    id               BIGINT                              NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id        BINARY(16)                          NOT NULL,
    member_id        BIGINT                              NOT NULL,
    request_key      BINARY(16)                          NOT NULL,
    sale_id          BIGINT                              NOT NULL,
    quantity         INT                                 NOT NULL,
    status           VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    rejection_reason VARCHAR(32) COLLATE utf8mb4_0900_bin,
    failure_reason   VARCHAR(64) COLLATE utf8mb4_0900_bin,
    order_id         BIGINT,
    accepted_at      DATETIME(6)                         NOT NULL,
    finished_at      DATETIME(6),
    CONSTRAINT uk_order_acceptances_public UNIQUE (public_id),
    CONSTRAINT uk_order_acceptances_member_key UNIQUE (member_id, request_key),
    INDEX            ix_order_acceptances_order (order_id),
    CONSTRAINT fk_order_acceptances_member FOREIGN KEY (member_id) REFERENCES members (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_order_acceptances_order FOREIGN KEY (order_id) REFERENCES purchase_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_order_acceptances_quantity CHECK (quantity BETWEEN 1 AND 1000000),
    CONSTRAINT ck_order_acceptances_state CHECK (
        (BINARY status = 'ACCEPTED' AND rejection_reason IS NULL AND failure_reason IS NULL AND
         order_id IS NULL AND finished_at IS NULL) OR
        (BINARY status = 'ORDER_CREATED' AND rejection_reason IS NULL AND failure_reason IS NULL AND
         order_id IS NOT NULL AND finished_at IS NOT NULL) OR
        (BINARY status = 'REJECTED' AND rejection_reason IS NOT NULL AND
         BINARY rejection_reason IN ('SALE_NOT_FOUND', 'SALE_OUTSIDE_PERIOD', 'SALE_STOPPED',
                                     'PURCHASE_LIMIT_EXCEEDED', 'SALE_SOLD_OUT') AND
         failure_reason IS NULL AND order_id IS NULL AND finished_at IS NOT NULL) OR
        (BINARY status = 'PROCESSING_FAILED' AND rejection_reason IS NULL AND failure_reason IS NOT NULL AND
         order_id IS NULL AND finished_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The auto-increment id is the publish order. A row is only ever inserted and later marked published, so the
-- acceptance row that a consumer locks is never touched by the publisher.
CREATE TABLE order_outbox
(
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    topic        VARCHAR(255)  NOT NULL,
    message_key  VARCHAR(64)   NOT NULL,
    payload      VARCHAR(2000) NOT NULL,
    created_at   DATETIME(6)   NOT NULL,
    published_at DATETIME(6),
    attempts     INT           NOT NULL DEFAULT 0,
    INDEX        ix_order_outbox_unpublished (published_at, id),
    CONSTRAINT ck_order_outbox_text CHECK (CHAR_LENGTH(topic) >= 1 AND CHAR_LENGTH(message_key) >= 1 AND
                                           JSON_VALID(payload)),
    CONSTRAINT ck_order_outbox_attempts CHECK (attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
