package com.wooyongjeong.limitedsale.order.application;

import java.time.Duration;

/** The wait after a failed step: it doubles up to a ceiling and starts over after a success. */
class OutboxBackoff {
    private final Duration first;
    private final Duration longest;
    private Duration next;

    OutboxBackoff(Duration first, Duration longest) {
        this.first = first;
        this.longest = longest;
        this.next = first;
    }

    /** The time to wait for this failure. The following failure waits twice as long, up to the ceiling. */
    Duration failure() {
        Duration current = next;
        Duration doubled = next.multipliedBy(2);
        next = doubled.compareTo(longest) > 0 ? longest : doubled;
        return current;
    }

    void success() {
        next = first;
    }
}
