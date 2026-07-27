package io.github.viniciusssantos.flagforge;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.CredentialScope;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.SdkPrincipal;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationSnapshotProvider;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationSnapshotProvider.EvaluationSnapshot;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.BooleanVariant;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.CreateFlagCommand;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.FeatureFlag;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.LifecycleType;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.ValueType;
import io.github.viniciusssantos.flagforge.publishing.PublicationService;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublicationError;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublicationException;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublishedRevision;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EqualityCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EvaluationContext;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EvaluationReason;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.FlagTarget;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.Prerequisite;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.StringValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingConfiguration;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingRule;
import io.github.viniciusssantos.flagforge.tenancy.Environment;
import io.github.viniciusssantos.flagforge.tenancy.MembershipRole;
import io.github.viniciusssantos.flagforge.tenancy.Organization;
import io.github.viniciusssantos.flagforge.tenancy.Project;
import io.github.viniciusssantos.flagforge.tenancy.TenantAccessException;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class ImmutableConfigurationPublicationIntegrationTests
        extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private TenantHierarchyService tenantHierarchyService;

    @Autowired
    private FeatureFlagService featureFlagService;

    @Autowired
    private PublicationService publicationService;

    @Autowired
    private EvaluationSnapshotProvider snapshotProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void persistsRevisionSnapshotPointerAuditAndOutboxInOnePublication() {
        TenantFixture fixture = createFixture("atomic-publication", "actor-owner");
        MDC.put("correlationId", "publication-correlation-1");

        PublishedRevision revision = publicationService.publish(
                fixture.environment().id(),
                0);

        assertThat(revision.revisionNumber()).isOne();
        assertThat(revision.publicationVersion()).isOne();
        assertThat(revision.organizationId()).isEqualTo(fixture.organization().id());
        assertThat(revision.projectId()).isEqualTo(fixture.project().id());
        assertThat(revision.environmentId()).isEqualTo(fixture.environment().id());
        assertThat(revision.checksum()).matches("[0-9a-f]{64}");
        assertThat(revision.payloadBytes()).isPositive();
        assertThat(revision.correlationId()).isEqualTo("publication-correlation-1");

        assertThat(count("configuration_revisions", fixture.environment().id())).isOne();
        assertThat(count("configuration_snapshots", fixture.environment().id())).isOne();
        assertThat(count("publication_audit_events", fixture.environment().id())).isOne();
        assertThat(count("configuration_outbox", fixture.environment().id())).isOne();

        Map<String, Object> pointer = jdbcTemplate.queryForMap(
                "select current_revision_id, current_revision_number, pointer_version "
                        + "from flagforge.environment_publication_state "
                        + "where environment_id = ?",
                fixture.environment().id());
        assertThat(pointer.get("current_revision_id")).isEqualTo(revision.revisionId());
        assertThat(pointer.get("current_revision_number")).isEqualTo(1L);
        assertThat(pointer.get("pointer_version")).isEqualTo(1L);

        PublishedRevision current = publicationService.current(
                        fixture.environment().id())
                .orElseThrow();
        assertThat(current.publicationVersion()).isOne();
        assertThat(current.revisionId()).isEqualTo(revision.revisionId());

        String outboxStatus = jdbcTemplate.queryForObject(
                "select status from flagforge.configuration_outbox "
                        + "where revision_id = ?",
                String.class,
                revision.revisionId());
        assertThat(outboxStatus).isEqualTo("PENDING");
    }

    @Test
    void draftEditsRemainInvisibleUntilTheNextPublication() {
        TenantFixture fixture = createFixture("draft-boundary", "actor-owner");
        PublishedRevision first = publicationService.publish(
                fixture.environment().id(),
                0);
        SdkPrincipal principal = principal(fixture);

        EvaluationSnapshot initial = snapshotProvider.load(principal, "checkout-v2")
                .orElseThrow();
        assertThat(initial.defaultVariant()).isEqualTo("disabled");
        assertThat(initial.configurationVersion()).contains("revision-1-");

        jdbcTemplate.update(
                "update flagforge.feature_flags set default_variant_key = 'enabled' "
                        + "where id = ?",
                fixture.flag().id());

        EvaluationSnapshot beforeRepublish = snapshotProvider.load(
                        principal,
                        "checkout-v2")
                .orElseThrow();
        assertThat(beforeRepublish.defaultVariant()).isEqualTo("disabled");
        assertThat(beforeRepublish.configurationVersion())
                .isEqualTo(initial.configurationVersion());

        PublishedRevision second = publicationService.publish(
                fixture.environment().id(),
                first.publicationVersion());
        EvaluationSnapshot afterRepublish = snapshotProvider.load(
                        principal,
                        "checkout-v2")
                .orElseThrow();

        assertThat(first.revisionNumber()).isOne();
        assertThat(second.revisionNumber()).isEqualTo(2);
        assertThat(second.publicationVersion()).isEqualTo(2);
        assertThat(afterRepublish.defaultVariant()).isEqualTo("enabled");
        assertThat(afterRepublish.configurationVersion()).contains("revision-2-");
        assertThat(count("configuration_revisions", fixture.environment().id()))
                .isEqualTo(2);
    }

    @Test
    void twoConcurrentPublicationsFromTheSameVersionProduceOneConflict()
            throws Exception {
        TenantFixture fixture = createFixture("concurrent-publication", "actor-owner");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> attempt = () -> {
                authenticate(fixture.organization().id(), fixture.actorId());
                ready.countDown();
                start.await();
                try {
                    return publicationService.publish(fixture.environment().id(), 0);
                } catch (PublicationException exception) {
                    return exception;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };

            Future<Object> first = executor.submit(attempt);
            Future<Object> second = executor.submit(attempt);
            ready.await();
            start.countDown();

            List<Object> results = List.of(first.get(), second.get());
            assertThat(results).filteredOn(PublishedRevision.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(PublicationException.class::isInstance).hasSize(1);

            PublicationException conflict = results.stream()
                    .filter(PublicationException.class::isInstance)
                    .map(PublicationException.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(conflict.code()).isEqualTo(PublicationError.VERSION_CONFLICT);
            assertThat(conflict.expectedVersion()).isZero();
            assertThat(conflict.currentVersion()).isOne();
            assertThat(conflict.currentRevisionId()).isNotNull();
            assertThat(conflict.currentRevisionNumber()).isOne();
            assertThat(conflict.currentChecksum()).matches("[0-9a-f]{64}");
            assertThat(conflict.currentUpdatedAt()).isNotNull();

            assertThat(count("configuration_revisions", fixture.environment().id())).isOne();
            assertThat(count("configuration_snapshots", fixture.environment().id())).isOne();
            assertThat(count("publication_audit_events", fixture.environment().id())).isOne();
            assertThat(count("configuration_outbox", fixture.environment().id())).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleExpectedVersionConflictsBeforeCreatingHistory() {
        TenantFixture fixture = createFixture("stale-publication", "actor-owner");
        PublishedRevision current = publicationService.publish(
                fixture.environment().id(),
                0);

        PublicationException conflict = assertThrows(
                PublicationException.class,
                () -> publicationService.publish(fixture.environment().id(), 0));

        assertThat(conflict.code()).isEqualTo(PublicationError.VERSION_CONFLICT);
        assertThat(conflict.expectedVersion()).isZero();
        assertThat(conflict.currentVersion()).isEqualTo(current.publicationVersion());
        assertThat(conflict.currentRevisionId()).isEqualTo(current.revisionId());
        assertThat(conflict.currentRevisionNumber()).isEqualTo(current.revisionNumber());
        assertThat(conflict.currentChecksum()).isEqualTo(current.checksum());
        assertThat(count("configuration_revisions", fixture.environment().id())).isOne();
        assertThat(count("configuration_snapshots", fixture.environment().id())).isOne();
        assertThat(count("publication_audit_events", fixture.environment().id())).isOne();
        assertThat(count("configuration_outbox", fixture.environment().id())).isOne();
    }

    @Test
    void failedPublicationLeavesThePreviousRevisionFullyEffective() {
        TenantFixture fixture = createFixture("failed-publication", "actor-owner");
        PublishedRevision first = publicationService.publish(
                fixture.environment().id(),
                0);
        SdkPrincipal principal = principal(fixture);

        jdbcTemplate.update(
                "update flagforge.feature_flags set default_variant_key = 'missing' "
                        + "where id = ?",
                fixture.flag().id());

        assertThrows(
                PublicationException.class,
                () -> publicationService.publish(
                        fixture.environment().id(),
                        first.publicationVersion()));

        Map<String, Object> currentState = jdbcTemplate.queryForMap(
                "select current_revision_number, pointer_version "
                        + "from flagforge.environment_publication_state "
                        + "where environment_id = ?",
                fixture.environment().id());
        EvaluationSnapshot effective = snapshotProvider.load(
                        principal,
                        "checkout-v2")
                .orElseThrow();

        assertThat(currentState.get("current_revision_number")).isEqualTo(1L);
        assertThat(currentState.get("pointer_version")).isEqualTo(1L);
        assertThat(effective.defaultVariant()).isEqualTo("disabled");
        assertThat(effective.configurationVersion()).contains(first.checksum());
        assertThat(count("configuration_revisions", fixture.environment().id())).isOne();
        assertThat(count("configuration_snapshots", fixture.environment().id())).isOne();
        assertThat(count("publication_audit_events", fixture.environment().id())).isOne();
        assertThat(count("configuration_outbox", fixture.environment().id())).isOne();
    }

    @Test
    void evaluatorUsesTheGraphStoredInsideThePublishedSnapshot() {
        TenantFixture fixture = createFixture("published-graph", "actor-owner");
        TargetingConfiguration graph = new TargetingConfiguration(
                List.of(new FlagTarget(
                        "checkout-v2",
                        Set.of("disabled", "enabled"),
                        "disabled",
                        List.of(),
                        List.of(new TargetingRule(
                                "internal-users",
                                10,
                                List.of(new EqualityCondition(
                                        "group",
                                        new StringValue("internal"))),
                                "enabled")))),
                List.of());

        publicationService.publish(fixture.environment().id(), 0, graph);
        EvaluationSnapshot snapshot = snapshotProvider.load(
                        principal(fixture),
                        "checkout-v2")
                .orElseThrow();
        var result = TargetingEngine.evaluate(
                snapshot.targetingConfiguration(),
                "checkout-v2",
                new EvaluationContext(
                        "user-42",
                        Map.of("group", new StringValue("internal"))));

        assertThat(result.reason()).isEqualTo(EvaluationReason.TARGETING_MATCH);
        assertThat(result.variantKey()).isEqualTo("enabled");
        assertThat(result.matchedRuleKey()).isEqualTo("internal-users");
        assertThat(snapshot.targetingConfiguration().flags().getFirst().rules())
                .extracting(TargetingRule::key)
                .containsExactly("internal-users");
    }

    @Test
    void cyclicPrerequisiteGraphLeavesNoPublicationHistory() {
        TenantFixture fixture = createFixture("cyclic-graph", "actor-owner");
        TargetingConfiguration cyclic = new TargetingConfiguration(
                List.of(new FlagTarget(
                        "checkout-v2",
                        Set.of("disabled", "enabled"),
                        "disabled",
                        List.of(new Prerequisite("checkout-v2", "enabled")),
                        List.of())),
                List.of());

        PublicationException failure = assertThrows(
                PublicationException.class,
                () -> publicationService.publish(
                        fixture.environment().id(),
                        0,
                        cyclic));

        assertThat(failure.code())
                .isEqualTo(PublicationError.INVALID_CONFIGURATION);
        assertThat(failure.validationCode())
                .isEqualTo("CYCLIC_PREREQUISITE");
        assertThat(count("configuration_revisions", fixture.environment().id())).isZero();
        assertThat(count("configuration_snapshots", fixture.environment().id())).isZero();
        assertThat(count("publication_audit_events", fixture.environment().id())).isZero();
        assertThat(count("configuration_outbox", fixture.environment().id())).isZero();
    }

    @Test
    void publishedHistoryRejectsUpdatesAndDeletesAtTheDatabaseBoundary() {
        TenantFixture fixture = createFixture("immutable-history", "actor-owner");
        PublishedRevision revision = publicationService.publish(
                fixture.environment().id(),
                0);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update flagforge.configuration_revisions "
                        + "set checksum = repeat('0', 64) where id = ?",
                revision.revisionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from flagforge.configuration_snapshots where revision_id = ?",
                revision.revisionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from flagforge.publication_audit_events where revision_id = ?",
                revision.revisionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void negativeExpectedVersionIsRejected() {
        TenantFixture fixture = createFixture("negative-version", "actor-owner");

        PublicationException failure = assertThrows(
                PublicationException.class,
                () -> publicationService.publish(fixture.environment().id(), -1));

        assertThat(failure.code())
                .isEqualTo(PublicationError.INVALID_EXPECTED_VERSION);
        assertThat(count("configuration_revisions", fixture.environment().id())).isZero();
    }

    @Test
    void viewerCannotPublishConfiguration() {
        TenantFixture fixture = createFixture("publication-rbac", "actor-owner");
        tenantHierarchyService.addMembership("actor-viewer", MembershipRole.VIEWER);
        authenticate(fixture.organization().id(), "actor-viewer");

        TenantAccessException failure = assertThrows(
                TenantAccessException.class,
                () -> publicationService.publish(fixture.environment().id(), 0));

        assertThat(failure.reason())
                .isEqualTo(TenantAccessException.Reason.ACCESS_DENIED);
        assertThat(count("configuration_revisions", fixture.environment().id())).isZero();
    }

    private TenantFixture createFixture(String prefix, String actorId) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Organization organization = tenantHierarchyService.registerOrganization(
                slug,
                prefix + " organization",
                actorId);
        authenticate(organization.id(), actorId);
        Project project = tenantHierarchyService.createProject(
                prefix + "-project",
                prefix + " project");
        Environment environment = tenantHierarchyService.createEnvironment(
                project.id(),
                "production",
                "Production");
        FeatureFlag flag = featureFlagService.create(new CreateFlagCommand(
                project.id(),
                "checkout-v2",
                "Checkout V2",
                null,
                actorId,
                ValueType.BOOLEAN,
                LifecycleType.OPERATIONAL,
                null,
                "disabled",
                List.of(
                        new BooleanVariant("disabled", false),
                        new BooleanVariant("enabled", true))));
        return new TenantFixture(organization, project, environment, flag, actorId);
    }

    private int count(String table, UUID environmentId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from flagforge." + table + " where environment_id = ?",
                Integer.class,
                environmentId);
        return count == null ? 0 : count;
    }

    private static SdkPrincipal principal(TenantFixture fixture) {
        return new SdkPrincipal(
                UUID.randomUUID(),
                fixture.organization().id(),
                fixture.environment().id(),
                CredentialScope.EVALUATE);
    }

    private static void authenticate(UUID organizationId, String actorId) {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new TenantPrincipal(organizationId, actorId),
                        null,
                        List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private record TenantFixture(
            Organization organization,
            Project project,
            Environment environment,
            FeatureFlag flag,
            String actorId) {
    }
}
