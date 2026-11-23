package com.wooyongjeong.limitedsale.order.domain;

import java.time.Duration;

/**
 * Per-member request counter shared by every application instance. Each call counts as one request, whether or not
 * it is allowed, so a retry with the same request key also uses up the budget.
 */
public interface OrderRateLimit {
    Decision hit(long memberId, Operation operation);

    /** Each operation has its own counter, so a burst of order requests never blocks paying an existing order. */
    enum Operation {
        CREATE,
        PAYMENT
    }

    sealed interface Decision {
        record Allowed() implements Decision {
        }

        /** The member used up the window; {@code retryAfter} is the time left until the counter expires. */
        record Rejected(Duration retryAfter) implements Decision {
        }

        /** The counter could not be read or written. Callers decide whether to let the request through. */
        record Unavailable() implements Decision {
        }
    }
}
