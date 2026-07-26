package io.github.viniciusssantos.flagforge;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.flags.FeatureFlagService;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.BooleanVariant;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.CreateFlagCommand;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.FeatureFlag;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.FlagState;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.FlagValidationException;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.LifecycleType;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.StringVariant;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.ValidationCode;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.ValueType;
import io.github.viniciusssantos.flagforge.tenancy.MembershipRole;
import io.github.viniciusssantos.flagforge.tenancy.Organization;
import io.github.viniciusssantos.flagforge.tenancy.Project;
import io.github.viniciusssantos.flagforge.tenancy.TenantAccessException;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class TypedFeatureFlagIntegrationTests extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private TenantHierarchyService tenantHierarchyService;

    @Autowired
    private FeatureFlagService featureFlagService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsBooleanAndNamedStringVariantsWithoutCoercion() {
        TenantFixture fixture = createTenantFixture("typed", "actor-owner");
        authenticate(fixture.organization().id(), fixture.actorId());

        FeatureFlag booleanFlag = featureFlagService.create(new CreateFlagCommand(
                fixture.project().id(),
                "checkout-v2",
                "Checkout V2",
                "Progressive checkout release",
                fixture.actorId(),
                ValueType.BOOLEAN,
                LifecycleType.RELEASE,
                LocalDate.now().plusDays(30),
                "disabled",
                List.of(
                        new BooleanVariant("disabled", false),
                        new BooleanVariant("enabled", true))));

        FeatureFlag stringFlag = featureFlagService.create(new CreateFlagCommand(
                fixture.project().id(),
                "checkout-layout",
                "Checkout Layout",
                null,
                fixture.actorId(),
                ValueType.STRING,
                LifecycleType.EXPERIMENT,
                LocalDate.now().plusDays(14),
                "control",
                List.of(
                        new StringVariant("control", "classic"),
                        new StringVariant("compact", "compact-v2"))));

        FeatureFlag reloadedBoolean = featureFlagService.find(
                fixture.project().id(),
                booleanFlag.id());
        FeatureFlag reloadedString = featureFlagService.findActiveByKey(
                fixture.project().id(),
                "checkout-layout");

        assertThat(reloadedBoolean.valueType()).isEqualTo(ValueType.BOOLEAN);
        assertThat(reloadedBoolean.variants())
                .containsExactlyInAnyOrder(
                        new BooleanVariant("disabled", false),
                        new BooleanVariant("enabled", true));
        assertThat(reloadedString.valueType()).isEqualTo(ValueType.STRING);
        assertThat(reloadedString.defaultVariantKey()).isEqualTo("control");
        assertThat(reloadedString.variants())
                .containsExactlyInAnyOrder(
                        new StringVariant("control", "classic"),
                        new StringVariant("compact", "compact-v2"));
    }

    @Test
    void rejectsInvalidTypesVariantsAndLifecycleMetadataWithStableCodes() {
        TenantFixture fixture = createTenantFixture("validation", "actor-owner");
        authenticate(fixture.organization().id(), fixture.actorId());

        assertValidation(
                ValidationCode.TYPE_MISMATCH,
                () -> featureFlagService.create(new CreateFlagCommand(
                        fixture.project().id(),
                        "mixed-types",
                        "Mixed Types",
                        null,
                        fixture.actorId(),
                        ValueType.BOOLEAN,
                        LifecycleType.OPERATIONAL,
                        null,
                        "enabled",
                        List.of(new StringVariant("enabled", "true")))));

        assertValidation(
                ValidationCode.DEFAULT_VARIANT_NOT_FOUND,
                () -> featureFlagService.create(new CreateFlagCommand(
                        fixture.project().id(),
                        "missing-default",
                        "Missing Default",
                        null,
                        fixture.actorId(),
                        ValueType.BOOLEAN,
                        LifecycleType.OPERATIONAL,
                        null,
                        "missing",
                        List.of(new BooleanVariant("enabled", true)))));

        assertValidation(
                ValidationCode.DUPLICATE_VARIANT_KEY,
                () -> featureFlagService.create(new CreateFlagCommand(
                        fixture.project().id(),
                        "duplicate-variants",
                        "Duplicate Variants",
                        null,
                        fixture.actorId(),
                        ValueType.STRING,
                        LifecycleType.OPERATIONAL,
                        null,
                        "control",
                        List.of(
                                new StringVariant("control", "a"),
                                new StringVariant("control", "b")))));

        assertValidation(
                ValidationCode.REMOVAL_DATE_REQUIRED,
                () -> featureFlagService.create(new CreateFlagCommand(
                        fixture.project().id(),
                        "release-without-date",
                        "Release Without Date",
                        null,
                        fixture.actorId(),
                        ValueType.BOOLEAN,
                        LifecycleType.RELEASE,
                        null,
                        "disabled",
                        List.of(new BooleanVariant("disabled", false)))));

        assertValidation(
                ValidationCode.UNSUPPORTED_VALUE_TYPE,
                () -> featureFlagService.create(new CreateFlagCommand(
                        fixture.project().id(),
                        "reserved-number",
                        "Reserved Number",
                        null,
                        fixture.actorId(),
                        ValueType.NUMBER,
                        LifecycleType.OPERATIONAL,
                        null,
                        "default",
                        List.of(new BooleanVariant("default", false)))));
    }

    @Test
    void archivedFlagIsPreservedButCannotBeResolvedAsActiveOrReused() {
        TenantFixture fixture = createTenantFixture("archive", "actor-owner");
        authenticate(fixture.organization().id(), fixture.actorId());
        CreateFlagCommand command = booleanCommand(
                fixture.project().id(),
                fixture.actorId(),
                "temporary-release");
        FeatureFlag created = featureFlagService.create(command);

        FeatureFlag archived = featureFlagService.archive(
                fixture.project().id(),
                created.id());

        assertThat(archived.state()).isEqualTo(FlagState.ARCHIVED);
        assertThat(archived.archivedAt()).isNotNull();
        assertThat(featureFlagService.find(fixture.project().id(), created.id()).state())
                .isEqualTo(FlagState.ARCHIVED);
        assertResourceNotFound(() -> featureFlagService.findActiveByKey(
                fixture.project().id(),
                created.key()));
        assertValidation(
                ValidationCode.FLAG_KEY_ALREADY_EXISTS,
                () -> featureFlagService.create(new CreateFlagCommand(
                        fixture.project().id(),
                        created.key(),
                        "Incompatible Reuse",
                        null,
                        fixture.actorId(),
                        ValueType.STRING,
                        LifecycleType.OPERATIONAL,
                        null,
                        "control",
                        List.of(new StringVariant("control", "new-value")))));
    }

    @Test
    void enforcesFlagPermissionsAndTenantIsolation() {
        TenantFixture tenantA = createTenantFixture("alpha-flags", "actor-alpha");
        TenantFixture tenantB = createTenantFixture("bravo-flags", "actor-bravo");

        authenticate(tenantA.organization().id(), tenantA.actorId());
        FeatureFlag flagA = featureFlagService.create(booleanCommand(
                tenantA.project().id(), tenantA.actorId(), "private-flag"));
        tenantHierarchyService.addMembership("actor-viewer", MembershipRole.VIEWER);
        tenantHierarchyService.addMembership("actor-developer", MembershipRole.DEVELOPER);

        authenticate(tenantA.organization().id(), "actor-viewer");
        assertThat(featureFlagService.find(tenantA.project().id(), flagA.id()))
                .isEqualTo(flagA);
        assertAccessDenied(() -> featureFlagService.create(booleanCommand(
                tenantA.project().id(), "actor-viewer", "viewer-write")));

        authenticate(tenantA.organization().id(), "actor-developer");
        assertThat(featureFlagService.create(booleanCommand(
                        tenantA.project().id(),
                        "actor-developer",
                        "developer-write"))
                .organizationId())
                .isEqualTo(tenantA.organization().id());

        authenticate(tenantB.organization().id(), tenantB.actorId());
        assertResourceNotFound(() -> featureFlagService.find(
                tenantA.project().id(), flagA.id()));
        assertResourceNotFound(() -> featureFlagService.find(
                tenantB.project().id(), flagA.id()));
    }

    private TenantFixture createTenantFixture(String prefix, String actorId) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Organization organization = tenantHierarchyService.registerOrganization(
                slug,
                prefix + " organization",
                actorId);
        authenticate(organization.id(), actorId);
        Project project = tenantHierarchyService.createProject(
                prefix + "-project",
                prefix + " project");
        return new TenantFixture(organization, project, actorId);
    }

    private static CreateFlagCommand booleanCommand(
            UUID projectId,
            String ownerId,
            String key) {
        return new CreateFlagCommand(
                projectId,
                key,
                key,
                null,
                ownerId,
                ValueType.BOOLEAN,
                LifecycleType.OPERATIONAL,
                null,
                "disabled",
                List.of(
                        new BooleanVariant("disabled", false),
                        new BooleanVariant("enabled", true)));
    }

    private static void authenticate(UUID organizationId, String actorId) {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new TenantPrincipal(organizationId, actorId),
                        null,
                        List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static void assertValidation(
            ValidationCode code,
            Executable operation) {
        FlagValidationException failure = assertThrows(
                FlagValidationException.class,
                operation);
        assertThat(failure.code()).isEqualTo(code);
    }

    private static void assertAccessDenied(Executable operation) {
        TenantAccessException failure = assertThrows(
                TenantAccessException.class,
                operation);
        assertThat(failure.reason())
                .isEqualTo(TenantAccessException.Reason.ACCESS_DENIED);
    }

    private static void assertResourceNotFound(Executable operation) {
        TenantAccessException failure = assertThrows(
                TenantAccessException.class,
                operation);
        assertThat(failure.reason())
                .isEqualTo(TenantAccessException.Reason.RESOURCE_NOT_FOUND);
    }

    private record TenantFixture(
            Organization organization,
            Project project,
            String actorId) {
    }
}
