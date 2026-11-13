package com.wooyongjeong.mockpg.domain;

import java.util.Objects;

public record ScenarioConfiguration(Scenario scenario, Integer delaySeconds) {
    public ScenarioConfiguration {
        Objects.requireNonNull(scenario);
        if (scenario == Scenario.SUCCESS || scenario == Scenario.DECLINE) {
            if (delaySeconds != null) {
                throw new IllegalArgumentException("Immediate scenarios do not accept a delay");
            }
        } else {
            if (delaySeconds == null) {
                delaySeconds = scenario == Scenario.DELAY ? 10 : 5;
            }
            if (delaySeconds < 1 || delaySeconds > 600) {
                throw new IllegalArgumentException("Delay must be between 1 and 600 seconds");
            }
        }
    }

    public static ScenarioConfiguration of(Scenario scenario, Integer delaySeconds) {
        return new ScenarioConfiguration(scenario, delaySeconds);
    }
}
