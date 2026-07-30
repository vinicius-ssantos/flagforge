package io.github.viniciusssantos.flagforge;

import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.flags.FeatureFlagService;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.BooleanVariant;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.CreateFlagCommand;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.LifecycleType;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.ValueType;
import io.github.viniciusssantos.flagforge.publishing.ChangeRequestService;
import io.github.viniciusssantos.flagforge.publishing.ChangeRequestService.ChangeRequestError;
import io.github.viniciusssantos.flagforge.publishing.ChangeRequestService.ChangeRequestException;
import io.github.viniciusssantos.flagforge.publishing.ChangeRequestService.ChangeRequestState;
import io.github.viniciusssantos.flagforge.publishing.EnvironmentApprovalPolicyService;
import io.github.viniciusssantos.flagforge.tenancy.Environment;
import io.github.viniciusssantos.flagforge.tenancy.MembershipRole;
import io.github.viniciusssantos.flagforge.tenancy.Organization;
import io.github.viniciusssantos.flagforge.tenancy.Project;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProtectedChangeRequestIntegrationTests
        extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantHierarchyService tenantHierarchyService;

    @Autowired
    private FeatureFlagService featureFlagService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EnvironmentApprovalPolicyService policyService;

    @Autowired
    private ChangeRequestService changeRequestService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requiresIndependentApprovalAndPublishesExactCandidate() {
        Fixture fixture = fixture("protected-happy");
        tenantHierarchyService.addMembership("reviewer", MembershipRole.ADMIN);
        policyService.update(fixture.environment().id(), true, true);

        assertThatThrownBy(() -> changeRequestService.requireDirectPublicationAllowed(
                fixture.environment().id()))
                .isInstanceOf(ChangeRequestException.class)
                .extracting(exception -> ((ChangeRequestException) exception).code())
                .isEqualTo(ChangeRequestError.APPROVAL_REQUIRED);

        authenticate(fixture.organization().id(), "requester");
        var created = changeRequestService.create(
                fixture.environment().id(), 0, "Enable checkout", "Production rollout");
        var submitted = changeRequestService.submit(
                fixture.environment().id(), created.id());
        assertThat(submitted.state()).isEqualTo(ChangeRequestState.IN_REVIEW);

        assertThatThrownBy(() -> changeRequestService.approve(
                fixture.environment().id(), created.id(), "self review"))
                .isInstanceOf(ChangeRequestException.class)
                .extracting(exception -> ((ChangeRequestException) exception).code())
                .isEqualTo(ChangeRequestError.SELF_APPROVAL_FORBIDDEN);

        authenticate(fixture.organization().id(), "reviewer");
        var approved = changeRequestService.approve(
                fixture.environment().id(), created.id(), "approved independently");
        assertThat(approved.state()).isEqualTo(ChangeRequestState.APPROVED);
        assertThat(approved.reviewerId()).isEqualTo("reviewer");

        authenticate(fixture.organization().id(), "requester");
        var published = changeRequestService.publish(
                fixture.environment().id(), created.id());
        assertThat(published.state()).isEqualTo(ChangeRequestState.PUBLISHED);
        assertThat(published.publishedRevisionNumber()).isEqualTo(1L);
        assertThat(published.candidateChecksum()).hasSize(64);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT checksum
                FROM flagforge.configuration_revisions
                WHERE id = ?
                """,
                String.class,
                published.publishedRevisionId()))
                .isEqualTo(created.candidateChecksum());
        assertThat(auditActions(created.id())).containsExactly(
                "CHANGE_REQUEST_CREATED",
                "CHANGE_REQUEST_SUBMITTED",
                "CHANGE_REQUEST_APPROVED",
                "CHANGE_REQUEST_PUBLISHED");
    }

    @Test
    void recordsRejectionAndAllowsANewRequest() {
        Fixture fixture = fixture("protected-rejection");
        tenantHierarchyService.addMembership("reviewer", MembershipRole.ADMIN);
        policyService.update(fixture.environment().id(), true, true);

        authenticate(fixture.organization().id(), "requester");
        var request = changeRequestService.create(
                fixture.environment().id(), 0, "Unsafe rollout", null);
        changeRequestService.submit(fixture.environment().id(), request.id());

        authenticate(fixture.organization().id(), "reviewer");
        var rejected = changeRequestService.reject(
                fixture.environment().id(),
                request.id(),
                "Missing release evidence");

        assertThat(rejected.state()).isEqualTo(ChangeRequestState.REJECTED);
        assertThat(rejected.decisionNote()).isEqualTo("Missing release evidence");
        assertThat(auditActions(request.id())).containsExactly(
                "CHANGE_REQUEST_CREATED",
                "CHANGE_REQUEST_SUBMITTED",
                "CHANGE_REQUEST_REJECTED");

        authenticate(fixture.organization().id(), "requester");
        assertThat(changeRequestService.create(
                fixture.environment().id(), 0, "Corrected rollout", null).state())
                .isEqualTo(ChangeRequestState.DRAFT);
    }

    @Test
    void invalidatesApprovalWhenDraftChanges() {
        Fixture fixture = fixture("candidate-change");
        tenantHierarchyService.addMembership("reviewer", MembershipRole.ADMIN);
        policyService.update(fixture.environment().id(), true, true);

        authenticate(fixture.organization().id(), "requester");
        var request = changeRequestService.create(
                fixture.environment().id(), 0, "Candidate", null);
        changeRequestService.submit(fixture.environment().id(), request.id());

        authenticate(fixture.organization().id(), "reviewer");
        changeRequestService.approve(
                fixture.environment().id(), request.id(), "looks good");

        authenticate(fixture.organization().id(), "requester");
        createFlag(fixture.project(), "late-change");

        assertThatThrownBy(() -> changeRequestService.publish(
                fixture.environment().id(), request.id()))
                .isInstanceOf(ChangeRequestException.class)
                .extracting(exception -> ((ChangeRequestException) exception).code())
                .isEqualTo(ChangeRequestError.CANDIDATE_CHANGED);
    }

    @Test
    void exposesCandidateDiffToViewerThroughApi() throws Exception {
        Fixture fixture = fixture("candidate-diff-api");
        tenantHierarchyService.addMembership("viewer", MembershipRole.VIEWER);
        var request = changeRequestService.create(
                fixture.environment().id(), 0, "Review exact candidate", null);

        mockMvc.perform(get("/api/v1/environments/"
                        + fixture.environment().id()
                        + "/change-requests/"
                        + request.id()
                        + "/diff")
                        .with(authentication(tenantAuthentication(
                                fixture.organization().id(),
                                "viewer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeRequestId").value(
                        request.id().toString()))
                .andExpect(jsonPath("$.candidateRevision").value(1))
                .andExpect(jsonPath("$.candidateValid").value(true))
                .andExpect(jsonPath("$.differences[0].path").value(
                        "flags.checkout-v2.defaultVariant"))
                .andExpect(jsonPath("$.differences[0].type").value("ADDED"));
    }

    @Test
    void allowsDirectPublicationPolicyForDevelopmentAndViewerReads() {
        Fixture fixture = fixture("development-policy");
        tenantHierarchyService.addMembership("viewer", MembershipRole.VIEWER);

        assertThat(policyService.get(fixture.environment().id()).approvalRequired())
                .isFalse();
        changeRequestService.requireDirectPublicationAllowed(
                fixture.environment().id());

        var request = changeRequestService.create(
                fixture.environment().id(), 0, "Reviewable candidate", null);

        authenticate(fixture.organization().id(), "viewer");
        assertThat(changeRequestService.list(fixture.environment().id()))
                .extracting(item -> item.id())
                .containsExactly(request.id());
        assertThatThrownBy(() -> changeRequestService.create(
                fixture.environment().id(), 0, "Forbidden", null))
                .isInstanceOf(RuntimeException.class);
    }

    private List<String> auditActions(UUID changeRequestId) {
        return jdbcTemplate.queryForList(
                """
                SELECT action
                FROM flagforge.audit_events
                WHERE resource_type = 'CHANGE_REQUEST'
                  AND resource_id = ?
                ORDER BY occurred_at, id
                """,
                String.class,
                changeRequestId);
    }

    private Fixture fixture(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Organization organization = tenantHierarchyService.registerOrganization(
                slug, prefix + " organization", "requester");
        authenticate(organization.id(), "requester");
        Project project = tenantHierarchyService.createProject(
                prefix + "-project", prefix + " project");
        Environment environment = tenantHierarchyService.createEnvironment(
                project.id(), "production", "Production");
        createFlag(project, "checkout-v2");
        return new Fixture(organization, project, environment);
    }

    private void createFlag(Project project, String key) {
        featureFlagService.create(new CreateFlagCommand(
                project.id(), key, key, null, "requester",
                ValueType.BOOLEAN, LifecycleType.OPERATIONAL, null, "disabled",
                List.of(
                        new BooleanVariant("disabled", false),
                        new BooleanVariant("enabled", true))));
    }

    private static void authenticate(UUID organizationId, String actorId) {
        SecurityContextHolder.getContext().setAuthentication(
                tenantAuthentication(organizationId, actorId));
    }

    private static Authentication tenantAuthentication(
            UUID organizationId,
            String actorId) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new TenantPrincipal(organizationId, actorId),
                null,
                List.of());
    }

    private record Fixture(
            Organization organization,
            Project project,
            Environment environment) {
    }
}
