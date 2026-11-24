package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.OutboxMessage;
import com.wooyongjeong.limitedsale.order.domain.OutboxStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and updates {@code order_outbox} with plain statements, each committing on its own. Nothing here locks: only
 * the instance that holds the publisher lock calls it, and the rows it changes are not touched by any other writer.
 */
@Repository
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.outbox-publisher.enabled"},
    havingValue = "true")
class JdbcOutboxStore implements OutboxStore {
    private final JdbcTemplate jdbc;

    JdbcOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OutboxMessage> unpublished(int limit) {
        return jdbc.query("SELECT id, topic, message_key, payload FROM order_outbox WHERE published_at IS NULL "
            + "ORDER BY id LIMIT ?", (rows, number) -> new OutboxMessage(rows.getLong("id"), rows.getString("topic"),
            rows.getString("message_key"), rows.getString("payload")), limit);
    }

    @Override
    public void markPublished(Collection<Long> ids) {
        update("UPDATE order_outbox SET published_at = UTC_TIMESTAMP(6) WHERE published_at IS NULL AND id IN ", ids);
    }

    @Override
    public void recordFailures(Collection<Long> ids) {
        update("UPDATE order_outbox SET attempts = attempts + 1 WHERE id IN ", ids);
    }

    private void update(String statement, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update(statement + "(" + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
            new ArrayList<>(ids).toArray());
    }
}
