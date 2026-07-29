package io.github.viniciusssantos.flagforge.evaluation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.SdkPrincipal;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.ValueType;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.FlagTarget;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingConfiguration;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DatabaseEvaluationSnapshotProvider
        implements EvaluationSnapshotProvider {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DatabaseEvaluationSnapshotProvider(
            NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EvaluationSnapshot> load(
            SdkPrincipal principal,
            String flagKey) {
        Optional<EnvironmentRow> environment = findEnvironment(
                principal.organizationId(),
                principal.environmentId());
        if (environment.isEmpty()) {
            return Optional.empty();
        }

        EnvironmentRow environmentRow = environment.get();
        Optional<FlagRow> flag = findFlag(
                principal.organizationId(),
                environmentRow.projectId(),
                flagKey);
        if (flag.isEmpty()) {
            return Optional.empty();
        }

        FlagRow flagRow = flag.get();
        Map<String, VariantValue> variants = loadVariants(
                principal.organizationId(),
                environmentRow.projectId(),
                flagRow.id());
        TargetingConfiguration targetingConfiguration =
                new TargetingConfiguration(
                        List.of(new FlagTarget(
                                flagKey,
                                variants.keySet(),
                                flagRow.defaultVariant(),
                                List.of(),
                                List.of())),
                        List.of());
        String configurationVersion = "db-live-env-"
                + environmentRow.version()
                + "-flag-"
                + flagRow.version();

        return Optional.of(new EvaluationSnapshot(
                principal.organizationId(),
                environmentRow.projectId(),
                principal.environmentId(),
                configurationVersion,
                flagRow.active(),
                false,
                flagRow.valueType(),
                flagRow.defaultVariant(),
                variants,
                targetingConfiguration,
                null));
    }

    private Optional<EnvironmentRow> findEnvironment(
            UUID organizationId,
            UUID environmentId) {
        String sql = """
                SELECT project_id, version
                FROM flagforge.environments
                WHERE organization_id = :organizationId
                  AND id = :environmentId
                """;
        return jdbcTemplate.query(
                        sql,
                        Map.of(
                                "organizationId", organizationId,
                                "environmentId", environmentId),
                        (resultSet, rowNumber) -> new EnvironmentRow(
                                resultSet.getObject("project_id", UUID.class),
                                resultSet.getLong("version")))
                .stream()
                .findFirst();
    }

    private Optional<FlagRow> findFlag(
            UUID organizationId,
            UUID projectId,
            String flagKey) {
        String sql = """
                SELECT id,
                       value_type,
                       default_variant_key,
                       state,
                       version
                FROM flagforge.feature_flags
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND flag_key = :flagKey
                """;
        return jdbcTemplate.query(
                        sql,
                        Map.of(
                                "organizationId", organizationId,
                                "projectId", projectId,
                                "flagKey", flagKey),
                        (resultSet, rowNumber) -> mapFlag(resultSet))
                .stream()
                .findFirst();
    }

    private Map<String, VariantValue> loadVariants(
            UUID organizationId,
            UUID projectId,
            UUID flagId) {
        String sql = """
                SELECT variant_key,
                       value_type,
                       boolean_value,
                       string_value
                FROM flagforge.feature_flag_variants
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND flag_id = :flagId
                ORDER BY variant_key
                """;
        Map<String, VariantValue> variants = new LinkedHashMap<>();
        jdbcTemplate.query(
                sql,
                Map.of(
                        "organizationId", organizationId,
                        "projectId", projectId,
                        "flagId", flagId),
                resultSet -> {
                    String variantKey = resultSet.getString("variant_key");
                    ValueType valueType = ValueType.valueOf(
                            resultSet.getString("value_type"));
                    Object value = switch (valueType) {
                        case BOOLEAN -> resultSet.getBoolean("boolean_value");
                        case STRING -> resultSet.getString("string_value");
                    };
                    variants.put(
                            variantKey,
                            new VariantValue(valueType, value));
                });
        return Map.copyOf(variants);
    }

    private static FlagRow mapFlag(ResultSet resultSet)
            throws SQLException {
        return new FlagRow(
                resultSet.getObject("id", UUID.class),
                ValueType.valueOf(resultSet.getString("value_type")),
                resultSet.getString("default_variant_key"),
                "ACTIVE".equals(resultSet.getString("state")),
                resultSet.getLong("version"));
    }

    private record EnvironmentRow(
            UUID projectId,
            long version) {
    }

    private record FlagRow(
            UUID id,
            ValueType valueType,
            String defaultVariant,
            boolean active,
            long version) {
    }
}
