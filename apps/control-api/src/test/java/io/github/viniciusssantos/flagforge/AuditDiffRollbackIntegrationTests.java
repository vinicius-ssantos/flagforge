package io.github.viniciusssantos.flagforge;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.audit.AuditTrailService;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditAction;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.CredentialScope;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.IssuedCredential;
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
import io.github.viniciusssantos.flagforge.publishing.PublicationService.RevisionKind;
import io.github.viniciusssantos.flagforge.publishing.RevisionHistoryService;
import io.github.viniciusssantos.flagforge.publishing.RevisionHistoryService.DifferenceType;
import io.github.viniciusssantos.flagforge.publishing.RevisionHistoryService.RevisionDiff;
import io.github.viniciusssantos.flagforge.publishing.RevisionHistoryService.RevisionSummary;
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
class AuditDiffRollbackIntegrationTests extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private TenantHierarchyService tenantHierarchyService;

    @Autowired
    private FeatureFlagService featureFlagService;

    @Autowired
    private SdkCredentialService sdkCredentialService;

    @Autowired
    private PublicationService publicationService;

    @Autowired
    private RevisionHistoryService revisionHistoryService;

    @Autowired
    private AuditTrailService auditTrailService;

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
    void comparesImmutableRevisionsAndRollsBackThroughANewRevision() {
        TenantFixture fixture = createFixture("rollback-history", "actor-owner");
        MDC.put("correlationId", "rollback-history-correlation");
        PublishedRevision first = publicationService.publish(
                fixture.environment().id(),
                0);

        jdbcTemplate.update(
                "update flagforge.feature_flags set default_variant_key = 'enabled' "
                        + "where id = ?",
                fixture.flag().id());
        PublishedRevision second = publicationService.publish(
                fixture.environment().id(),
                first.publicationVersion());

        RevisionDiff diff = revisionHistoryService.diff(
                fixture.environment().id(),
                first.revisionNumber(),
                second.revisionNumber());
        assertThat(diff.differences())
                .anySatisfy(difference -> {
                    assertThat(difference.path())
                            .isEqualTo("flags.checkout-v2.defaultVariant");
                    assertThat(difference.type()).isEqualTo(DifferenceType.CHANGED);
                    assertThat(difference.beforeValue()).isEqualTo("disabled");
                    assertThat(difference.afterValue()).isEqualTo("enabled");
                });

        PublishedRevision rollback = publicationService.rollback(
                fixture.environment().id(),
                first.revisionNumber(),
                second.publicationVersion());

        assertThat(rollback.revisionNumber()).isEqualTo(3);
        assertThat(rollback.publicationVersion()).isEqualTo(3);
        assertThat(rollback.revisionKind()).isEqualTo(RevisionKind.ROLLBACK);
        assertThat(rollback.sourceRevisionId()).isEqualTo(first.revisionId());
        assertThat(rollback.sourceRevisionNumber()).isEqualTo(first.revisionNumber());
        assertThat(rollback.revisionId())
                .isNotEqualTo(first.revisionId())
                .isNotEqualTo(second.revisionId());

        EvaluationSnapshot effective = snapshotProvider.load(
                        principal(fixture),
                        "checkout-v2")
                .orElseThrow();
        assertThat(effective.defaultVariant()).isEqualTo("disabled");
        assertThat(effective.configurationVersion()).contains("revision-3-");

        List<RevisionSummary> history = revisionHistoryService.history(
                fixture.environment().id());
        assertThat(history)
                .extracting(RevisionSummary::revisionNumber)
                .containsExactly(3L, 2L, 1L);
        assertThat(history.getFirst().current()).isTrue();
        assertThat(history.getFirst().revisionKind()).isEqualTo(RevisionKind.ROLLBACK);
        assertThat(history.getFirst().sourceRevisionNumber()).isEqualTo(1L);
        assertThat(history.get(1).current()).isFalse();
        assertThat(history.get(2).current()).isFalse();

        assertThat(auditTrailService.history(fixture.environment().id()))
                .extracting(event -> event.action())
                .contains(
                        AuditAction.FEATURE_FLAG_CREATED,
                        AuditAction.CONFIGURATION_PUBLISHED,
                        AuditAction.CONFIGURATION_ROLLED_BACK);
        assertThat(count("configuration_revisions", fixture.environment().id()))
                .isEqualTo(3);
        assertThat(count("configuration_snapshots", fixture.environment().id()))
                .isEqualTo(3);
        assertThat(count("publication_audit_events", fixture.environment().id()))
                .isEqualTo(3);
        assertThat(count("configuration_outbox", fixture.environment().id()))
                .isEqualTo(3);
        assertThat(count("audit_events", fixture.environment().id()))
                .isEqualTo(3);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update flagforge.audit_events set actor_id = 'tampered' "
                        + "where revision_id = ?",
                rollback.revisionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from flagforge.audit_events where revision_id = ?",
                rollback.revisionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void failedPublicationLeavesPointerHistoryAuditAndOutboxUnchanged() {
        TenantFixture fixture = createFixture("atomic-audit", "actor-owner");
        PublishedRevision first = publicationService.publish(
                fixture.environment().id(),
                0);
        int revisionsBefore = count(
                "configuration_revisions",
                fixture.environment().id());
        int snapshotsBefore = count(
                "configuration_snapshots",
                fixture.environment().id());
        int publicationAuditBefore = count(
                "publication_audit_events",
                fixture.environment().id());
        int auditBefore = count("audit_events", fixture.environment().id());
        int outboxBefore = count("configuration_outbox", fixture.environment().id());

        jdbcTemplate.update(
                "update flagforge.feature_flags set default_variant_key = 'missing' "
                        + "where id = ?",
                fixture.flag().id());

        assertThrows(
                PublicationException.class,
                () -> publicationService.publish(
                        fixture.environment().id(),
                        first.publicationVersion()));

        PublishedRevision current = publicationService.current(
                        fixture.environment().id())
                .orElseThrow();
        assertThat(current.revisionId()).isEqualTo(first.revisionId());
        assertThat(current.publicationVersion()).isEqualTo(first.publicationVersion());
        assertThat(count("configuration_revisions", fixture.environment().id()))
                .isEqualTo(revisionsBefore);
        assertThat(count("configuration_snapshots", fixture.environment().id()))
                .isEqualTo(snapshotsBefore);
        assertThat(count("publication_audit_events", fixture.environment().id()))
                .isEqualTo(publicationAuditBefore);
        assertThat(count("audit_events", fixture.environment().id()))
                .isEqualTo(auditBefore);
        assertThat(count("configuration_outbox", fixture.environment().id()))
                .isEqualTo(outboxBefore);
    }

    @Test
    void auditReadIsAuthorizedSeparatelyFromConfigurationWrite() {
        TenantFixture fixture = createFixture("audit-rbac", "actor-owner");
        publicationService.publish(fixture.environment().id(), 0);
        tenantHierarchyService.addMembership("actor-viewer", MembershipRole.VIEWER);
        tenantHierarchyService.addMembership("actor-developer", MembershipRole.DEVELOPER);

        authenticate(fixture.organization().id(), "actor-viewer");
        assertThat(auditTrailService.history(fixture.environment().id())).isNotEmpty();
        assertThat(revisionHistoryService.history(fixture.environment().id())).hasSize(1);
        assertThrows(
                TenantAccessException.class,
                () -> publicationService.rollback(
                        fixture.environment().id(),
                        1,
                        1));

        authenticate(fixture.organization().id(), "actor-developer");
        TenantAccessException auditFailure = assertThrows(
                TenantAccessException.class,
                () -> auditTrailService.history(fixture.environment().id()));
        TenantAccessException revisionFailure = assertThrows(
                TenantAccessException.class,
                () -> revisionHistoryService.history(fixture.environment().id()));
        assertThat(auditFailure.reason())
                .isEqualTo(TenantAccessException.Reason.ACCESS_DENIED);
        assertThat(revisionFailure.reason())
                .isEqualTo(TenantAccessException.Reason.ACCESS_DENIED);
    }

    @Test
    void credentialAuditNeverStoresPlaintextHashOrKeyPrefix() {
        TenantFixture fixture = createFixture("credential-audit", "actor-owner");
        IssuedCredential issued = sdkCredentialService.create(
                fixture.environment().id(),
                "evaluation-service");
        String persistedHash = jdbcTemplate.queryForObject(
                "select secret_hash from flagforge.sdk_credentials where id = ?",
                String.class,
                issued.metadata().id());
        IssuedCredential rotated = sdkCredentialService.rotate(
                issued.metadata().id());
        sdkCredentialService.revoke(rotated.metadata().id());

        String auditDetails = jdbcTemplate.queryForObject(
                "select string_agg(details::text, ' ') "
                        + "from flagforge.audit_events "
                        + "where organization_id = ? "
                        + "and environment_id = ? "
                        + "and action like 'SDK_CREDENTIAL_%'",
                String.class,
                fixture.organization().id(),
                fixture.environment().id());
        assertThat(auditDetails)
                .doesNotContain(issued.plaintext())
                .doesNotContain(rotated.plaintext())
                .doesNotContain(persistedHash)
                .doesNotContain(issued.metadata().keyPrefix())
                .doesNotContain("evaluation-service");

        List<String> actions = jdbcTemplate.queryForList(
                "select action from flagforge.audit_events "
                        + "where organization_id = ? "
                        + "and environment_id = ? "
                        + "and action like 'SDK_CREDENTIAL_%' "
                        + "order by occurred_at",
                String.class,
                fixture.organization().id(),
                fixture.environment().id());
        assertThat(actions).containsExactly(
                AuditAction.SDK_CREDENTIAL_CREATED.name(),
                AuditAction.SDK_CREDENTIAL_ROTATED.name(),
                AuditAction.SDK_CREDENTIAL_REVOKED.name());
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
        return new TenantFixture(
                organization,
                project,
                environment,
                flag,
                actorId);
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
