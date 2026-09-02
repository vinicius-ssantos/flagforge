package io.github.viniciusssantos.flagforge.tenancy;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
final class ProjectController {

    private final TenantHierarchyService tenantHierarchyService;

    ProjectController(TenantHierarchyService tenantHierarchyService) {
        this.tenantHierarchyService = tenantHierarchyService;
    }

    @PostMapping
    ResponseEntity<ProjectResponse> create(@RequestBody CreateProjectRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        Project project = tenantHierarchyService.createProject(request.key(), request.displayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.of(project));
    }

    @GetMapping("/{projectId}")
    ProjectResponse find(@PathVariable UUID projectId) {
        return ProjectResponse.of(tenantHierarchyService.findProject(projectId));
    }

    @PostMapping("/{projectId}/environments")
    ResponseEntity<EnvironmentResponse> createEnvironment(
            @PathVariable UUID projectId,
            @RequestBody CreateEnvironmentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        Environment environment = tenantHierarchyService.createEnvironment(
                projectId,
                request.key(),
                request.displayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(EnvironmentResponse.of(environment));
    }

    record CreateProjectRequest(String key, String displayName) {
    }

    record CreateEnvironmentRequest(String key, String displayName) {
    }

    record ProjectResponse(
            UUID id,
            String key,
            String displayName,
            Instant createdAt) {

        static ProjectResponse of(Project project) {
            return new ProjectResponse(
                    project.id(),
                    project.key(),
                    project.displayName(),
                    project.createdAt());
        }
    }

    record EnvironmentResponse(
            UUID id,
            UUID projectId,
            String key,
            String displayName,
            Instant createdAt) {

        static EnvironmentResponse of(Environment environment) {
            return new EnvironmentResponse(
                    environment.id(),
                    environment.projectId(),
                    environment.key(),
                    environment.displayName(),
                    environment.createdAt());
        }
    }
}
