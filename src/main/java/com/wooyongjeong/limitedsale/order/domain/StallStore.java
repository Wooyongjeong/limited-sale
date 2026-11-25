package com.wooyongjeong.limitedsale.order.domain;

import java.time.Duration;
import java.util.List;

public interface StallStore {
    /** ACCEPTED acceptances whose latest outbox row was published at least {@code publishedAgo} ago, oldest first. */
    List<StalledCandidate> publishedAndUnfinished(Duration publishedAgo, int limit);

    /** Adds a new unpublished outbox row with the same topic, key and payload for the publisher to send. */
    void republish(StalledCandidate candidate);
}
