package com.positivity.supplier.internal.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every capability port has an implementation, and keeps having one (#1349).
 *
 * <h2>Why this test is the point of the exercise</h2>
 *
 * The SPI was declared by the CAP-317 foundation and then went unused for six capabilities. Nothing
 * noticed, because nothing could: an interface with no implementations compiles forever. Adding
 * {@code implements} to seven runners fixes today; this fixes tomorrow.
 *
 * <p>The failure it prevents is not untidiness. An unimplemented signature is never tested against
 * reality, so it drifts into being unimplementable — which is what had happened to
 * {@code SupplierWorkorderAuthorizationPort} (nowhere to say how a fleet vehicle is identified) and
 * to every port's {@code SupplierExchange} wrapper (a non-null audit id that a deliberately
 * best-effort audit could not promise). By the time anybody tried to use them, they described
 * operations this platform could not perform.
 */
@DisplayName("Supplier SPI — every port is implemented (#1349)")
class SupplierPortAdoptionTest {

    private static final String SPI_PACKAGE = "com.positivity.supplier.internal.spi";

    /**
     * Ports allowed to have no implementation, each for a stated reason.
     *
     * <p>Deliberately a list of names rather than a marker annotation: adding to it should mean
     * writing down why, in a review somebody reads, rather than adding a symbol to a class.
     */
    private static final Set<String> DORMANT_BY_DESIGN = Set.of(
            // DOT / sidewall scanning is a placeholder pending confirmation that it is required
            // (architecture §12, decision 10). TireIdentificationDormancyTest asserts it stays one.
            "TireIdentificationPort");

    private static JavaClasses supplierClasses;

    @BeforeAll
    static void importClasses() {
        supplierClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.positivity.supplier");

        // Guards against the assertions below passing vacuously on an empty import, which is how an
        // architecture test comes to protect nothing at all.
        assertThat(supplierClasses).isNotEmpty();
    }

    @Test
    @DisplayName("every capability port has at least one implementation")
    void everyPortIsImplemented() {
        List<String> unimplemented = supplierClasses.stream()
                .filter(JavaClass::isInterface)
                .filter(clazz -> clazz.getPackageName().equals(SPI_PACKAGE))
                .filter(clazz -> clazz.getSimpleName().endsWith("Port"))
                .filter(clazz -> !DORMANT_BY_DESIGN.contains(clazz.getSimpleName()))
                .filter(clazz -> clazz.getAllSubclasses().isEmpty())
                .map(JavaClass::getSimpleName)
                .sorted()
                .toList();

        assertThat(unimplemented)
                .as("capability ports with no implementation — either implement it, or remove it and "
                        + "say why, because a port nothing implements is a description of a capability "
                        + "this platform does not have")
                .isEmpty();
    }

    @Test
    @DisplayName("the ports it is checking actually exist")
    void thePortsAreThere() {
        // Without this, deleting every port would make the rule above pass.
        List<String> ports = supplierClasses.stream()
                .filter(JavaClass::isInterface)
                .filter(clazz -> clazz.getPackageName().equals(SPI_PACKAGE))
                .filter(clazz -> clazz.getSimpleName().endsWith("Port"))
                .map(JavaClass::getSimpleName)
                .sorted()
                .toList();

        assertThat(ports)
                .as("declared capability ports")
                .contains(
                        "SupplierCatalogPort",
                        "SupplierInvoicePort",
                        "SupplierOrderStatusPort",
                        "SupplierPriceCatalogPort",
                        "SupplierStockInquiryPort",
                        "SupplierStockReportPort",
                        "SupplierWorkorderAuthorizationPort",
                        "TireIdentificationPort");
    }

    @Test
    @DisplayName("no port signature takes or returns a JPA entity, including inside generics")
    void portsAreFreeOfEntities() {
        // A port that needs an entity is describing bookkeeping rather than a capability — which is
        // exactly why order creation has no port. Entities also drag JPA into a package that is
        // framework-clean by mandate (ADR-0051 §2).
        //
        // Generic parameters are checked, not just raw types. A raw-type check passes a method
        // returning List<SomeEntity>, because its raw type is java.util.List — and a list of
        // entities is the likelier leak of the two, since a port returning one entity looks wrong
        // at a glance and a port returning a list of them does not.
        List<String> offenders = supplierClasses.stream()
                .filter(clazz -> clazz.getPackageName().equals(SPI_PACKAGE))
                .filter(clazz -> clazz.getSimpleName().endsWith("Port"))
                .flatMap(clazz -> clazz.getMethods().stream())
                .filter(SupplierPortAdoptionTest::mentionsAnEntity)
                .map(method -> method.getOwner().getSimpleName() + "." + method.getName())
                .sorted()
                .toList();

        assertThat(offenders).as("port methods touching entities").isEmpty();
    }

    /**
     * Whether a method's signature names an entity anywhere, generic arguments included.
     *
     * <p>Reads the generic signature rather than the erased one. {@code List<SomeEntity>} erases to
     * {@code java.util.List}, so a raw-type check would report a clean signature for the exact shape
     * most likely to smuggle persistence into this package.
     */
    private static boolean mentionsAnEntity(JavaMethod method) {
        Stream<String> genericTypeNames = Stream.concat(
                        method.getParameterTypes().stream(), Stream.of(method.getReturnType()))
                .flatMap(SupplierPortAdoptionTest::allTypeNames);
        Stream<String> rawTypeNames = Stream.concat(
                        method.getRawParameterTypes().stream(), Stream.of(method.getRawReturnType()))
                .map(JavaClass::getName);

        return Stream.concat(genericTypeNames, rawTypeNames).anyMatch(name -> name.contains(".internal.entity."));
    }

    /** Every type named by a generic type, itself and its arguments, however deeply nested. */
    private static Stream<String> allTypeNames(JavaType type) {
        Stream<String> self = Stream.of(type.getName());
        if (type instanceof JavaParameterizedType parameterized) {
            return Stream.concat(
                    self,
                    parameterized.getActualTypeArguments().stream().flatMap(SupplierPortAdoptionTest::allTypeNames));
        }
        return self;
    }
}
