package io.github.viniciusssantos.flagforge.tenancy.internal;

import java.util.Optional;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.tenancy.Project;

import org.springframework.data.repository.CrudRepository;

public interface ProjectRepository extends CrudRepository<Project, UUID> {

    Optional<Project> findByOrganizationIdAndId(
            UUID organizationId,
            UUID id);

    boolean existsByOrganizationIdAndKey(
            UUID organizationId,
            String key);
}
