package com.orderflow.engine.config;

import com.orderflow.engine.outbox.HousekeepingJob;
import com.orderflow.engine.outbox.HousekeepingStore;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the housekeeping job to its store and its retentions, and runs it in the background. Scheduling is enabled
 * here, next to the job that needs it, so the application class stays free of wiring.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(HousekeepingProperties.class)
public class HousekeepingConfig {

    @Bean
    HousekeepingJob housekeepingJob(HousekeepingStore store, Clock clock, HousekeepingProperties properties) {
        return new HousekeepingJob(store, clock, properties.idempotency().retention(),
                properties.housekeeping().retention());
    }
}
