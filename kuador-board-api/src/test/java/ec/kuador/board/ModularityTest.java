package ec.kuador.board;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(KuadorBoardApplication.class).verify();
    }

    @Test
    void domainDoesNotDependOnFrameworks() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..")
                .check(new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("ec.kuador.board"));
    }
}
