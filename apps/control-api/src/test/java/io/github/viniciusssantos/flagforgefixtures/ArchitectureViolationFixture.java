package io.github.viniciusssantos.flagforgefixtures;

import org.springframework.modulith.Modulithic;

/**
 * Root marker for the deliberately invalid module arrangement used by architecture tests.
 */
@Modulithic
public final class ArchitectureViolationFixture {

    private ArchitectureViolationFixture() {
    }
}
