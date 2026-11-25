package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.OutboxHistory;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** One statement per call, committing on its own; only the outbox publisher's thread calls it. */
@Repository
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.outbox-publisher.enabled"},
    havingValue = "true")
class JdbcOutboxHistory implements OutboxHistory {
    private final JdbcTemplate jdbc;

    JdbcOutboxHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deletePublishedOlderThan(Duration age, int limit) {
        // ix_order_outbox_unpublished (published_at, id) bounds the scan to the old published rows.
        return jdbc.update("DELETE FROM order_outbox WHERE published_at < UTC_TIMESTAMP(6) - INTERVAL ? MICROSECOND "
            + "AND NOT EXISTS (SELECT 1 FROM order_acceptances a WHERE a.id = order_outbox.acceptance_id "
            + "AND a.status = 'ACCEPTED') ORDER BY id LIMIT ?", age.toNanos() / 1000, limit);
    }
}
