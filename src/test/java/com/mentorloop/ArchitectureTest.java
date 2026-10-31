package com.mentorloop;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.mentorloop", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "java.sql..",
                    "com.fasterxml.jackson..", "..application..", "..adapter..");

    @ArchTest
    static final ArchRule applicationDoesNotReachIntoAdapters = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule applicationDoesNotTouchInfrastructure = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..",
                    "org.springframework.web..", "java.sql..", "org.springframework.kafka..",
                    "org.springframework.data.redis..", "java.net.http..");

    /** The tutor learns about quizzes only through its own QuizStatus port, never assessment's types. */
    @ArchTest
    static final ArchRule tutorDoesNotDependOnAssessment = noClasses().that().resideInAPackage("com.mentorloop.tutor..")
            .should().dependOnClassesThat().resideInAPackage("com.mentorloop.assessment..");

    /** Only the LLM adapters know a model is remote. */
    @ArchTest
    static final ArchRule onlyLlmAdaptersSpeakHttpToModels = noClasses().that()
            .resideOutsideOfPackage("com.mentorloop.llm.adapter..")
            .should().dependOnClassesThat().resideInAPackage("com.mentorloop.llm.adapter..");

    @ArchTest
    static final ArchRule modulesHaveNoCycles = slices().matching("com.mentorloop.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule controllersLiveInAdapters = classes().that()
            .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAPackage("..adapter..").andShould().notBePublic();
}
