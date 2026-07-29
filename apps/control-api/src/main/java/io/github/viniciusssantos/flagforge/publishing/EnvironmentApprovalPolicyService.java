package io.github.viniciusssantos.flagforge.publishing;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.audit.AuditTrailService;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditAction;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditCommand;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditResourceType;
import io.github.viniciusssantos.flagforge.tenancy.ControlPlanePermission;
import io.github.viniciusssantos.flagforge.tenancy.Environment;
import io.github.viniciusssantos.flagforge.tenancy.TenantAuthorizationService;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantIdentity;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnvironmentApprovalPolicyService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TenantAuthorizationService authorizationService;
    private final TenantHierarchyService tenantHierarchyService;
    private final AuditTrailService auditTrailService;

    public EnvironmentApprovalPolicyService(
            NamedParameterJdbcTemplate jdbcTemplate,
            TenantAuthorizationService authorizationService,
            TenantHierarchyService tenantHierarchyService,
            AuditTrailService auditTrailService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.tenantHierarchyService = tenantHierarchyService;
        this.auditTrailService = auditTrailService;
    }

    @Transactional(readOnly = true)
    public ApprovalPolicy get(UUID environmentId) {
        authorizationService.require(ControlPlanePermission.ENVIRONMENT_READ);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        return jdbcTemplate.query(
                        """
                        SELECT approval_required, prevent_self_approval, updated_by, updated_at
                        FROM flagforge.environment_approval_policies
                        WHERE organization_id = :organizationId
                          AND project_id = :projectId
                          AND environment_id = :environmentId
                        """,
                        parameters(environment),
                        (rs, rowNumber) -> new ApprovalPolicy(
                                rs.getBoolean("approval_required"),
                                rs.getBoolean("prevent_self_approval"),
                                rs.getString("updated_by"),
                                rs.getTimestamp("updated_at").toInstant()))
                .stream()
                .findFirst()
                .orElseGet(ApprovalPolicy::directPublication);
    }

    @Transactional
    public ApprovalPolicy update(
            UUID environmentId,
            boolean approvalRequired,
            boolean preventSelfApproval) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.ENVIRONMENT_WRITE);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                INSERT INTO flagforge.environment_approval_policies (
                    organization_id, project_id, environment_id,
                    approval_required, prevent_self_approval, updated_by, updated_at)
                VALUES (
                    :organizationId, :projectId, :environmentId,
                    :approvalRequired, :preventSelfApproval, :updatedBy, :updatedAt)
                ON CONFLICT (organization_id, project_id, environment_id)
                DO UPDATE SET approval_required = EXCLUDED.approval_required,
                              prevent_self_approval = EXCLUDED.prevent_self_approval,
                              updated_by = EXCLUDED.updated_by,
                              updated_at = EXCLUDED.updated_at
                """,
                parameters(environment)
                        .addValue("approvalRequired", approvalRequired)
                        .addValue("preventSelfApproval", preventSelfApproval)
                        .addValue("updatedBy", identity.actorId())
                        .addValue("updatedAt", now));
        auditTrailService.append(new AuditCommand(
                environment.organizationId(),
                environment.projectId(),
                environment.id(),
                identity.actorId(),
                AuditAction.ENVIRONMENT_APPROVAL_POLICY_CHANGED,
                AuditResourceType.ENVIRONMENT,
                environment.id(),
                null,
                null,
                Map.of(
                        "approvalRequired", Boolean.toString(approvalRequired),
                        "preventSelfApproval", Boolean.toString(preventSelfApproval)),
                now));
        return new ApprovalPolicy(
                approvalRequired,
                preventSelfApproval,
                identity.actorId(),
                now);
    }

    @Transactional(readOnly = true)
    public boolean approvalRequired(Environment environment) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                SELECT COALESCE((
                    SELECT approval_required
                    FROM flagforge.environment_approval_policies
                    WHERE organization_id = :organizationId
                      AND project_id = :projectId
                      AND environment_id = :environmentId
                ), FALSE)
                """,
                parameters(environment),
                Boolean.class));
    }

    @Transactional(readOnly = true)
    public boolean preventSelfApproval(Environment environment) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                SELECT COALESCE((
                    SELECT prevent_self_approval
                    FROM flagforge.environment_approval_policies
                    WHERE organization_id = :organizationId
                      AND project_id = :projectId
                      AND environment_id = :environmentId
                ), TRUE)
                """,
                parameters(environment),
                Boolean.class));
    }

    private static MapSqlParameterSource parameters(Environment environment) {
        return new MapSqlParameterSource()
                .addValue("organizationId", environment.organizationId())
                .addValue("projectId", environment.projectId())
                .addValue("environmentId", environment.id());
    }

    public record ApprovalPolicy(
            boolean approvalRequired,
            boolean preventSelfApproval,
            String updatedBy,
            Instant updatedAt) {

        static ApprovalPolicy directPublication() {
            return new ApprovalPolicy(false, true, null, null);
        }
    }
}
