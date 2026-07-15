package io.github.viniciusssantos.flagforge;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

abstract class PostgreSqlIntegrationTestSupport {

    static final String POSTGRES_IMAGE = "postgres:17.10-alpine";

    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("flagforge")
            .withUsername("flagforge")
            .withPassword("flagforge-test-only");

    static {
        POSTGRESQL.start();
    }

    @DynamicPropertySource
    static void registerPostgreSqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }
}
