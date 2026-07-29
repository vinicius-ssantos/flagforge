package io.github.viniciusssantos.flagforge.tenancy.internal;

import java.util.UUID;

import io.github.viniciusssantos.flagforge.tenancy.Organization;

import org.springframework.data.repository.CrudRepository;

public interface OrganizationRepository extends CrudRepository<Organization, UUID> {

    boolean existsBySlug(String slug);
}
