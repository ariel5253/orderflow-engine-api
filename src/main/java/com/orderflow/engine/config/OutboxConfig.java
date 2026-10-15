package com.orderflow.engine.config;

import com.orderflow.engine.outbox.BackoffPolicy;
import com.orderflow.engine.outbox.ErpPublisherPort;
import com.orderflow.engine.outbox.OutboxRelay;
import com.orderflow.engine.outbox.OutboxRepository;
import com.orderflow.engine.outbox.PushNotificationPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.Random;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;

/**
 * Wires the outbox relay to its gateways and runs it in the background. Scheduling is enabled here, not on the
 * application class, so it belongs to the outbox module, whose jobs are the only ones that need it.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

    @Bean
    BackoffPolicy outboxBackoff(OutboxProperties properties) {
        return new BackoffPolicy(properties.backoff().base(), properties.backoff().max(), new Random());
    }

    @Bean
    OutboxRelay outboxRelay(OutboxRepository repository, PushNotificationPort push, ErpPublisherPort erp,
            BackoffPolicy outboxBackoff, OutboxProperties properties, Clock clock) {
        return new OutboxRelay(repository, push, erp, outboxBackoff, properties.lease(), properties.maxAttempts(),
                clock);
    }

    /**
     * The background poll. A fixed delay means a slow poll never overlaps the next one. The first poll also waits one
     * interval, so the relay starts only once the service is up.
     */
    @Bean
    FixedDelayTask outboxRelayPoll(OutboxRelay relay, OutboxProperties properties) {
        return new FixedDelayTask(relay::relayDue, properties.pollInterval(), properties.pollInterval());
    }

    @Bean
    SchedulingConfigurer outboxRelaySchedule(FixedDelayTask outboxRelayPoll) {
        return registrar -> registrar.addFixedDelayTask(outboxRelayPoll);
    }

    /**
     * {@code outbox_pending} is the backlog: it grows while push or the ERP is down. {@code outbox_dead} counts the
     * events that need an operator. Both are read from the table, so every relay instance reports the same values.
     */
    @Bean
    MeterBinder outboxGauges(OutboxRepository repository) {
        return registry -> {
            Gauge.builder("outbox_pending", repository, OutboxRepository::countPending)
                    .description("Outbox events not yet delivered to push or the ERP")
                    .register(registry);
            Gauge.builder("outbox_dead", repository, OutboxRepository::countDead)
                    .description("Outbox events that are no longer retried and wait for an operator")
                    .register(registry);
        };
    }
}
