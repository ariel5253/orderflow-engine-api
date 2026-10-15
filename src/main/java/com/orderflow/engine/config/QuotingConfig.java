package com.orderflow.engine.config;

import com.orderflow.engine.pricing.domain.PricingEngine;
import com.orderflow.engine.quoting.CreateQuoteUseCase;
import com.orderflow.engine.quoting.CreditAdvisor;
import com.orderflow.engine.quoting.GetQuoteUseCase;
import com.orderflow.engine.quoting.QuoteRepository;
import com.orderflow.engine.quoting.SnapshotProvider;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the quote use cases to their ports. The pricing engine is pure and knows nothing of Spring, so it is created
 * here rather than scanned.
 */
@Configuration(proxyBeanMethods = false)
public class QuotingConfig {

    @Bean
    PricingEngine pricingEngine() {
        return new PricingEngine();
    }

    @Bean
    CreateQuoteUseCase createQuoteUseCase(SnapshotProvider snapshots, CreditAdvisor creditAdvisor,
            QuoteRepository quotes, PricingEngine pricingEngine, Clock clock) {
        return new CreateQuoteUseCase(snapshots, creditAdvisor, quotes, pricingEngine, clock);
    }

    @Bean
    GetQuoteUseCase getQuoteUseCase(SnapshotProvider snapshots, QuoteRepository quotes, CreditAdvisor creditAdvisor,
            Clock clock) {
        return new GetQuoteUseCase(snapshots, quotes, creditAdvisor, clock);
    }
}
