package com.orderflow.engine.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time for the whole service. Beans read the time from this one injected clock, never from the system directly, so
 * a test can fix it or move it, and every lease and retry time becomes deterministic.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
