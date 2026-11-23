package com.wooyongjeong.limitedsale.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Application clock for code that must not read the database time; tests replace this bean. */
@Configuration
public class ClockConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
