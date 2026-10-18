package com.positivity.extractionworker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;

/**
 * The one rule about how the web process may touch {@code internal.sandbox}: only through the three records
 * that cross the process boundary. Calling {@code ParseMain} (or any parser) in process would run hostile
 * input in the web JVM, which holds the secret.
 */
public final class SandboxBoundary {

    private static final Set<String> BOUNDARY_TYPES = Set.of("SandboxRequest", "SandboxResult", "SandboxLimits");

    private SandboxBoundary() {}

    public static ArchRule webProcessUsesOnlyTheBoundaryRecords() {
        DescribedPredicate<JavaClass> sandboxInternals =
                new DescribedPredicate<>("sandbox classes other than the request, result and limits records") {
                    @Override
                    public boolean test(JavaClass target) {
                        return target.getPackageName().startsWith("com.positivity.extractionworker.internal.sandbox")
                                && !BOUNDARY_TYPES.contains(
                                        target.getSimpleName().replaceAll("\\$.*", ""))
                                && !target.getFullName().contains("SandboxResult$")
                                && !target.getFullName().contains("SandboxRequest$");
                    }
                };
        return noClasses()
                .that()
                .resideOutsideOfPackage("..internal.sandbox..")
                .should()
                .dependOnClassesThat(sandboxInternals)
                .allowEmptyShould(true)
                .because("the web process must never run a parser in process; it talks to the child through"
                        + " SandboxRequest, SandboxResult and SandboxLimits only");
    }
}
