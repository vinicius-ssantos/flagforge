package io.github.viniciusssantos.flagforge.distribution.internal;

import java.time.Clock;

import io.github.viniciusssantos.flagforge.distribution.OutboxRelayProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxRelayProperties.class)
class DistributionConfiguration {

    /**
     * The application clock.
     *
     * <p>The relay schedules retries into the future, so its tests need to move time rather than
     * wait for it. {@code ConditionalOnMissingBean} lets a test supply a controllable clock without
     * any production seam.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
