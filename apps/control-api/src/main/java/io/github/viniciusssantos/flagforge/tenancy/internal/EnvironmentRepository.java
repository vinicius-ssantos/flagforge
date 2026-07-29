package io.github.viniciusssantos.flagforge.tenancy.internal;

import java.util.Optional;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.tenancy.Environment;

import org.springframework.data.repository.CrudRepository;

public interface EnvironmentRepository extends CrudRepository<Environment, UUID> {

    Optional<Environment> findByOrganizationIdAndId(
            UUID organizationId,
            UUID id);

    boolean existsByOrganizationIdAndProjectIdAndKey(
            UUID organizationId,
            UUID projectId,
            String key);
}
