package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.StallStore;
import com.wooyongjeong.limitedsale.order.domain.StalledCandidate;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain statements, each committing on its own; only the outbox publisher's thread calls them. */
@Repository
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.outbox-publisher.enabled"},
    havingValue = "true")
class JdbcStallStore implements StallStore {
    private final JdbcTemplate jdbc;

    JdbcStallStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StalledCandidate> publishedAndUnfinished(Duration publishedAgo, int limit) {
        return jdbc.query("""
            SELECT a.id, a.public_id, o.topic, o.message_key, o.payload, o.partition_no, o.record_offset,
                   TIMESTAMPDIFF(MICROSECOND, o.published_at, UTC_TIMESTAMP(6)) AS age_us,
                   (SELECT COUNT(*) FROM order_outbox s WHERE s.acceptance_id = a.id) AS sends
            FROM order_acceptances a
            JOIN order_outbox o ON o.id = (SELECT MAX(l.id) FROM order_outbox l WHERE l.acceptance_id = a.id)
            WHERE a.status = 'ACCEPTED' AND o.published_at IS NOT NULL
              AND o.published_at <= UTC_TIMESTAMP(6) - INTERVAL ? MICROSECOND
            ORDER BY a.id
            LIMIT ?
            """, (rows, number) -> {
            ByteBuffer id = ByteBuffer.wrap(rows.getBytes("public_id"));
            Integer partition = (Integer) rows.getObject("partition_no", Integer.class);
            Long offset = (Long) rows.getObject("record_offset", Long.class);
            return new StalledCandidate(rows.getLong("id"), new UUID(id.getLong(), id.getLong()),
                rows.getString("topic"), rows.getString("message_key"), rows.getString("payload"), partition, offset,
                Duration.ofNanos(rows.getLong("age_us") * 1000), rows.getInt("sends"));
        }, publishedAgo.toNanos() / 1000, limit);
    }

    @Override
    public void republish(StalledCandidate candidate) {
        jdbc.update("INSERT INTO order_outbox(topic, message_key, payload, created_at, attempts, acceptance_id) "
            + "VALUES (?, ?, ?, UTC_TIMESTAMP(6), 0, ?)", candidate.topic(), candidate.key(), candidate.payload(),
            candidate.acceptanceRowId());
    }
}
