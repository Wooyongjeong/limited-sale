package com.wooyongjeong.limitedsale.order.domain;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * An ACCEPTED acceptance whose latest outbox row was published at least the stall time ago. {@code partition} and
 * {@code offset} say where that row landed (null for rows published before they were recorded); {@code sends} counts
 * its outbox rows, so {@code sends - 1} is how often it was republished.
 */
public record StalledCandidate(long acceptanceRowId, UUID acceptanceId, String topic, String key, String payload,
        Integer partition, Long offset, Duration sincePublished, int sends, String requestId) {
    /** Whether the consumer group's committed offset for the partition (the next one it reads) is past this row. */
    public boolean passedBy(Map<Integer, Long> committedOffsets) {
        if (partition == null || offset == null) {
            return false;
        }
        Long committed = committedOffsets.get(partition);
        return committed != null && committed > offset;
    }
}
