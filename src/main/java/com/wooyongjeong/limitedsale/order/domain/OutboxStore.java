package com.wooyongjeong.limitedsale.order.domain;

import java.util.Collection;
import java.util.List;

public interface OutboxStore {
    /** Up to {@code limit} unpublished rows in id order, read without locking. */
    List<OutboxMessage> unpublished(int limit);

    /** Marks the rows published at the database's current UTC time. */
    void markPublished(Collection<Long> ids);

    /** Adds one to the attempt count of each row whose send failed. */
    void recordFailures(Collection<Long> ids);
}
