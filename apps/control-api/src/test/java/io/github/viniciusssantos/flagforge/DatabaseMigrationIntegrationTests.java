package io.github.viniciusssantos.flagforge;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DatabaseMigrationIntegrationTests extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationsApplyFromAnEmptyPostgreSqlDatabaseAndValidate() {
        flyway.validate();

        Boolean schemaExists = jdbcTemplate.queryForObject(
                "select exists (select 1 from information_schema.schemata where schema_name = 'flagforge')",
                Boolean.class);
        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "select count(*) from flagforge.flyway_schema_history where success and version = '1'",
                Integer.class);

        assertThat(schemaExists).isTrue();
        assertThat(successfulMigrations).isEqualTo(1);
    }

    @Test
    void applicationStartupFailsWhenMigrationHistoryDoesNotMatch() {
        Integer originalChecksum = jdbcTemplate.queryForObject(
                "select checksum from flagforge.flyway_schema_history where version = '1'",
                Integer.class);

        assertThat(originalChecksum).isNotNull();

        try {
            int changedRows = jdbcTemplate.update(
                    "update flagforge.flyway_schema_history set checksum = ? where version = '1'",
                    originalChecksum + 1);
            assertThat(changedRows).isOne();

            assertThatThrownBy(() -> {
                try (var ignored = new SpringApplicationBuilder(FlagForgeApplication.class)
                        .web(WebApplicationType.NONE)
                        .run(
                                "--spring.datasource.url=" + POSTGRESQL.getJdbcUrl(),
                                "--spring.datasource.username=" + POSTGRESQL.getUsername(),
                                "--spring.datasource.password=" + POSTGRESQL.getPassword(),
                                "--spring.main.banner-mode=off")) {
                    throw new AssertionError("Application unexpectedly started with invalid migration history");
                }
            })
                    .hasStackTraceContaining("Validate failed")
                    .hasStackTraceContaining("checksum");
        } finally {
            jdbcTemplate.update(
                    "update flagforge.flyway_schema_history set checksum = ? where version = '1'",
                    originalChecksum);
        }
    }
}
