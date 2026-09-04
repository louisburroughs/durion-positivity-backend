package com.positivity.archunit;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Issue #1699 build-time guard: every type bound as a Spring {@code @RequestBody} must be
 * constructible by Jackson. A Lombok {@code @Value @Builder} class without {@code @Jacksonized}
 * compiles cleanly, passes every other check, and yet has no creator Jackson can use — exactly
 * the #1699 defect, previously only caught at runtime by a full {@code @SpringBootTest} exercising
 * the real {@code ObjectMapper}. This rule catches the shape at build time instead.
 *
 * <p>A request-body type is accepted when it is:
 *
 * <ul>
 *   <li>a record (its canonical constructor is a valid creator);
 *   <li>annotated {@code @Jacksonized} — which Lombok compiles to
 *       {@code tools.jackson.databind.annotation.JsonDeserialize(builder = ...)} on the class, so
 *       that is what survives to bytecode and what this rule actually looks for;
 *   <li>carrying a constructor or static factory annotated {@code @JsonCreator}; or
 *   <li>declaring a visible (non-private) no-arg constructor, the default bean-deserialization
 *       path (e.g. Lombok {@code @Data}).
 * </ul>
 */
class RequestBodyCreatorGuardTest {

    private static final String REQUEST_BODY_ANNOTATION = "org.springframework.web.bind.annotation.RequestBody";
    private static final String JSON_DESERIALIZE_ANNOTATION = "tools.jackson.databind.annotation.JsonDeserialize";
    private static final String JSON_CREATOR_ANNOTATION = "com.fasterxml.jackson.annotation.JsonCreator";

    private static JavaClasses allClasses;

    @BeforeAll
    static void setup() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.positivity");
    }

    @Test
    void requestBodyTypesMustHaveAJacksonCreator() {
        List<String> violations = new ArrayList<>();

        for (JavaClass javaClass : allClasses) {
            for (JavaMethod method : javaClass.getMethods()) {
                for (JavaParameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotatedWith(REQUEST_BODY_ANNOTATION)) {
                        continue;
                    }
                    JavaClass bodyType = parameter.getRawType();
                    if (!bodyType.getName().startsWith("com.positivity.")) {
                        // Not this platform's type to fix (e.g. a library-provided body type).
                        continue;
                    }
                    if (!hasJacksonCreator(bodyType)) {
                        violations.add(javaClass.getFullName() + "#" + method.getName() + "(param "
                                + parameter.getIndex() + ") -> " + bodyType.getFullName());
                    }
                }
            }
        }

        Assertions.assertTrue(
                violations.isEmpty(),
                "Types bound as @RequestBody must be constructible by Jackson: a record, @Jacksonized, a"
                        + " @JsonCreator constructor/factory, or a visible no-arg constructor (issue #1699)."
                        + " Offenders:\n" + String.join("\n", violations));
    }

    private static boolean hasJacksonCreator(JavaClass type) {
        if (type.isRecord()) {
            return true;
        }
        if (type.isAnnotatedWith(JSON_DESERIALIZE_ANNOTATION)) {
            return true;
        }
        boolean hasFactoryCreator =
                type.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(JSON_CREATOR_ANNOTATION));
        if (hasFactoryCreator) {
            return true;
        }
        for (JavaConstructor constructor : type.getConstructors()) {
            if (constructor.isAnnotatedWith(JSON_CREATOR_ANNOTATION)) {
                return true;
            }
            boolean noArg = constructor.getRawParameterTypes().isEmpty();
            boolean visible = !constructor.getModifiers().contains(JavaModifier.PRIVATE);
            if (noArg && visible) {
                return true;
            }
        }
        return false;
    }
}
