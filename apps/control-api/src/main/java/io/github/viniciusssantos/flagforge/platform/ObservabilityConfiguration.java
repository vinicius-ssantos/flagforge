package io.github.viniciusssantos.flagforge.platform;

import java.util.Set;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

    private static final Set<String> PROHIBITED_HIGH_CARDINALITY_TAG_KEYS = Set.of(
            "credential",
            "organization.id",
            "sdk.key",
            "subject.id",
            "targeting.key",
            "token",
            "user.id");

    @Bean
    MeterFilter rejectSensitiveOrHighCardinalityTags() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                boolean prohibitedTagPresent = id.getTags().stream()
                        .anyMatch(tag -> PROHIBITED_HIGH_CARDINALITY_TAG_KEYS.contains(tag.getKey()));
                return prohibitedTagPresent ? MeterFilterReply.DENY : MeterFilterReply.NEUTRAL;
            }
        };
    }
}
