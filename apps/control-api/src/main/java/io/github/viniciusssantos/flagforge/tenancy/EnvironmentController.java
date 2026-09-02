package io.github.viniciusssantos.flagforge.tenancy;

import java.time.Instant;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/environments")
final class EnvironmentController {

    private final TenantHierarchyService tenantHierarchyService;

    EnvironmentController(TenantHierarchyService tenantHierarchyService) {
        this.tenantHierarchyService = tenantHierarchyService;
    }

    @GetMapping("/{environmentId}")
    EnvironmentResponse find(@PathVariable UUID environmentId) {
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        return new EnvironmentResponse(
                environment.id(),
                environment.projectId(),
                environment.key(),
                environment.displayName(),
                environment.createdAt());
    }

    record EnvironmentResponse(
            UUID id,
            UUID projectId,
            String key,
            String displayName,
            Instant createdAt) {
    }
}
