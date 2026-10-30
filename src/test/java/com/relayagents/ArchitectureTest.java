package com.relayagents;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.relayagents", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..",
            "org.springframework..", "jakarta.persistence..", "org.apache.kafka..");

    @ArchTest
    static final ArchRule applicationDoesNotKnowAdapters = noClasses().that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule adaptersDoNotReachIntoOtherModulesAdapters = slices()
        .matching("com.relayagents.(*).adapter..").should().notDependOnEachOther();

    @ArchTest
    static final ArchRule modulesHaveNoCycles = slices().matching("com.relayagents.(*)..").should().beFreeOfCycles();
}
