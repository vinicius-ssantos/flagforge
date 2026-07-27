package io.github.viniciusssantos.flagforge;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublicationException;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublishedRevision;
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
                fixture.environment().id());

        assertThat(revision.revisionNumber()).isOne();
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
        assertThat(pointer.get("pointer_version")).isEqualTo(0L);

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
        PublishedRevision first = publicationService.publish(fixture.environment().id());
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

        PublishedRevision second = publicationService.publish(fixture.environment().id());
        EvaluationSnapshot afterRepublish = snapshotProvider.load(
                        principal,
                        "checkout-v2")
                .orElseThrow();

        assertThat(first.revisionNumber()).isOne();
        assertThat(second.revisionNumber()).isEqualTo(2);
        assertThat(afterRepublish.defaultVariant()).isEqualTo("enabled");
        assertThat(afterRepublish.configurationVersion()).contains("revision-2-");
        assertThat(count("configuration_revisions", fixture.environment().id()))
                .isEqualTo(2);
    }

    @Test
    void failedPublicationLeavesThePreviousRevisionFullyEffective() {
        TenantFixture fixture = createFixture("failed-publication", "actor-owner");
        PublishedRevision first = publicationService.publish(fixture.environment().id());
        SdkPrincipal principal = principal(fixture);

        jdbcTemplate.update(
                "update flagforge.feature_flags set default_variant_key = 'missing' "
                        + "where id = ?",
                fixture.flag().id());

        assertThrows(
                PublicationException.class,
                () -> publicationService.publish(fixture.environment().id()));

        Long currentRevision = jdbcTemplate.queryForObject(
                "select current_revision_number "
                        + "from flagforge.environment_publication_state "
                        + "where environment_id = ?",
                Long.class,
                fixture.environment().id());
        EvaluationSnapshot effective = snapshotProvider.load(
                        principal,
                        "checkout-v2")
                .orElseThrow();

        assertThat(currentRevision).isOne();
        assertThat(effective.defaultVariant()).isEqualTo("disabled");
        assertThat(effective.configurationVersion()).contains(first.checksum());
        assertThat(count("configuration_revisions", fixture.environment().id())).isOne();
        assertThat(count("configuration_snapshots", fixture.environment().id())).isOne();
        assertThat(count("publication_audit_events", fixture.environment().id())).isOne();
        assertThat(count("configuration_outbox", fixture.environment().id())).isOne();
    }

    @Test
    void publishedHistoryRejectsUpdatesAndDeletesAtTheDatabaseBoundary() {
        TenantFixture fixture = createFixture("immutable-history", "actor-owner");
        PublishedRevision revision = publicationService.publish(fixture.environment().id());

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
    void viewerCannotPublishConfiguration() {
        TenantFixture fixture = createFixture("publication-rbac", "actor-owner");
        tenantHierarchyService.addMembership("actor-viewer", MembershipRole.VIEWER);
        authenticate(fixture.organization().id(), "actor-viewer");

        TenantAccessException failure = assertThrows(
                TenantAccessException.class,
                () -> publicationService.publish(fixture.environment().id()));

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
