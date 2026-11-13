package com.wooyongjeong.mockpg.domain;

import java.util.UUID;

public interface PaymentStore {
    PaymentResult charge(UUID key, long amount);
    PaymentResult close(UUID key, long amount);
    PaymentResult query(UUID key);
    ScenarioConfiguration configure(UUID key, ScenarioConfiguration configuration);
}
