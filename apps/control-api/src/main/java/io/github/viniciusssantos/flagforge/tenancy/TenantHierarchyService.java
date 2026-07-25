package io.github.viniciusssantos.flagforge.tenancy;

import java.time.Instant;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.tenancy.internal.EnvironmentRepository;
import io.github.viniciusssantos.flagforge.tenancy.internal.MembershipRepository;
import io.github.viniciusssantos.flagforge.tenancy.internal.OrganizationRepository;
import io.github.viniciusssantos.flagforge.tenancy.internal.ProjectRepository;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantHierarchyService {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;
    private final ProjectRepository projectRepository;
    private final EnvironmentRepository environmentRepository;
    private final TenantAuthorizationService authorizationService;

    public TenantHierarchyService(
            OrganizationRepository organizationRepository,
            MembershipRepository membershipRepository,
            ProjectRepository projectRepository,
            EnvironmentRepository environmentRepository,
            TenantAuthorizationService authorizationService) {
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public Organization registerOrganization(
            String slug,
            String displayName,
            String foundingActorId) {
        Instant now = Instant.now();
        Organization organization = Organization.create(slug, displayName, now);
        if (organizationRepository.existsBySlug(organization.slug())) {
            throw new IllegalArgumentException("Organization slug already exists");
        }

        Organization savedOrganization = organizationRepository.save(organization);
        membershipRepository.save(Membership.active(
                savedOrganization.id(),
                foundingActorId,
                MembershipRole.OWNER,
                now));
        return savedOrganization;
    }

    @Transactional(readOnly = true)
    public Organization currentOrganization() {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.ORGANIZATION_READ);
        return organizationRepository.findById(identity.organizationId())
                .orElseThrow(TenantAccessException::resourceNotFound);
    }

    @Transactional
    public Membership addMembership(String actorId) {
        return addMembership(actorId, MembershipRole.VIEWER);
    }

    @Transactional
    public Membership addMembership(String actorId, MembershipRole role) {
        TenantIdentity identity = authorizeMembershipRoleChange(role);
        if (membershipRepository.findByOrganizationIdAndActorId(
                identity.organizationId(), actorId).isPresent()) {
            throw new IllegalArgumentException("Membership already exists");
        }

        return membershipRepository.save(Membership.active(
                identity.organizationId(),
                actorId,
                role,
                Instant.now()));
    }

    @Transactional
    public Membership changeMembershipRole(String actorId, MembershipRole role) {
        TenantIdentity identity = authorizeMembershipRoleChange(role);
        Membership membership = membershipRepository.findByOrganizationIdAndActorId(
                        identity.organizationId(), actorId)
                .orElseThrow(TenantAccessException::resourceNotFound);
        if (membership.role() == MembershipRole.OWNER) {
            authorizationService.require(ControlPlanePermission.ORGANIZATION_MANAGE);
        }
        return membershipRepository.save(membership.changeRole(role, Instant.now()));
    }

    @Transactional
    public Project createProject(String key, String displayName) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.PROJECT_WRITE);
        Project project = Project.create(
                identity.organizationId(),
                key,
                displayName,
                Instant.now());
        if (projectRepository.existsByOrganizationIdAndKey(
                identity.organizationId(), project.key())) {
            throw new IllegalArgumentException("Project key already exists");
        }
        return projectRepository.save(project);
    }

    @Transactional(readOnly = true)
    public Project findProject(UUID projectId) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.PROJECT_READ);
        return findProject(identity, projectId);
    }

    @Transactional
    public Project renameProject(UUID projectId, String displayName) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.PROJECT_WRITE);
        Project project = findProject(identity, projectId);
        return projectRepository.save(project.rename(displayName, Instant.now()));
    }

    @Transactional
    public Environment createEnvironment(
            UUID projectId,
            String key,
            String displayName) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.ENVIRONMENT_WRITE);
        Project project = findProject(identity, projectId);
        Environment environment = Environment.create(
                identity.organizationId(),
                project.id(),
                key,
                displayName,
                Instant.now());
        if (environmentRepository.existsByOrganizationIdAndProjectIdAndKey(
                identity.organizationId(), project.id(), environment.key())) {
            throw new IllegalArgumentException("Environment key already exists");
        }
        return environmentRepository.save(environment);
    }

    @Transactional(readOnly = true)
    public Environment findEnvironment(UUID environmentId) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.ENVIRONMENT_READ);
        return findEnvironment(identity, environmentId);
    }

    @Transactional
    public Environment renameEnvironment(UUID environmentId, String displayName) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.ENVIRONMENT_WRITE);
        Environment environment = findEnvironment(identity, environmentId);
        return environmentRepository.save(environment.rename(displayName, Instant.now()));
    }

    @Transactional(readOnly = true)
    public TenantAuditContext currentAuditContext() {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.ORGANIZATION_READ);
        return new TenantAuditContext(
                identity.organizationId(),
                identity.actorId(),
                MDC.get(CORRELATION_ID_MDC_KEY));
    }

    private TenantIdentity authorizeMembershipRoleChange(MembershipRole role) {
        if (role == MembershipRole.OWNER) {
            return authorizationService.require(
                    ControlPlanePermission.ORGANIZATION_MANAGE);
        }
        return authorizationService.require(
                ControlPlanePermission.MEMBERSHIP_MANAGE);
    }

    private Project findProject(TenantIdentity identity, UUID projectId) {
        return projectRepository.findByOrganizationIdAndId(
                        identity.organizationId(),
                        projectId)
                .orElseThrow(TenantAccessException::resourceNotFound);
    }

    private Environment findEnvironment(TenantIdentity identity, UUID environmentId) {
        return environmentRepository.findByOrganizationIdAndId(
                        identity.organizationId(),
                        environmentId)
                .orElseThrow(TenantAccessException::resourceNotFound);
    }
}
