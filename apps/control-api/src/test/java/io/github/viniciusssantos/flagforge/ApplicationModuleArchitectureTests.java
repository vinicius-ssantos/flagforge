package io.github.viniciusssantos.flagforge;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import io.github.viniciusssantos.flagforgefixtures.ArchitectureViolationFixture;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationModuleArchitectureTests {

    private static final ApplicationModules APPLICATION_MODULES =
            ApplicationModules.of(FlagForgeApplication.class);

    private static final JavaClasses VIOLATION_FIXTURE_CLASSES = new ClassFileImporter()
            .importPackages("io.github.viniciusssantos.flagforgefixtures");

    @Test
    void applicationModulesRespectDeclaredBoundaries() {
        APPLICATION_MODULES.verify();
    }

    @Test
    void architectureDocumentationCanBeGenerated() {
        new Documenter(APPLICATION_MODULES)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }

    @Test
    void intentionalFixtureIsRejectedBySpringModulith() {
        var invalidModules = ApplicationModules.of(ArchitectureViolationFixture.class);

        assertThatThrownBy(invalidModules::verify)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void intentionalFixtureProvesModuleCyclesFail() {
        ArchRule modulesMustBeFreeOfCycles = slices()
                .matching("io.github.viniciusssantos.flagforgefixtures.(*)..")
                .should()
                .beFreeOfCycles();

        assertThatThrownBy(() -> modulesMustBeFreeOfCycles.check(VIOLATION_FIXTURE_CLASSES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cycle");
    }

    @Test
    void intentionalFixtureProvesInternalPackageAccessFails() {
        ArchRule moduleInternalsMustRemainPrivate = noClasses()
                .that()
                .resideOutsideOfPackage("..flagforgefixtures.alpha..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..flagforgefixtures.alpha.internal..");

        assertThatThrownBy(() -> moduleInternalsMustRemainPrivate.check(VIOLATION_FIXTURE_CLASSES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("alpha.internal");
    }
}
