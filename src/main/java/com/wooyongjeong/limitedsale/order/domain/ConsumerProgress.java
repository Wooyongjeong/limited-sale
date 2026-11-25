package com.wooyongjeong.limitedsale.order.domain;

import java.util.Map;
import java.util.Optional;

/** Where the order consumer group stands on the acceptance topic. */
public interface ConsumerProgress {
    /** Committed offset (the next one to read) per partition, or empty when the broker cannot tell right now. */
    Optional<Map<Integer, Long>> committedOffsets();
}
