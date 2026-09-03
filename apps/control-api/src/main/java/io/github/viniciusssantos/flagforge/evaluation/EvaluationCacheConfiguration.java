package io.github.viniciusssantos.flagforge.evaluation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvaluationCacheProperties.class)
class EvaluationCacheConfiguration {
}
