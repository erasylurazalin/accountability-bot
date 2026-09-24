package dev.era.accountability.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The scheduler reads time from this bean rather than from Instant.now(), so a
 * test can replace it and run days of ticks in seconds.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
