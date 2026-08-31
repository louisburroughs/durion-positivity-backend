package com.positivity.invoice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.invoice.internal.security.InvoicePermissions;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Issue #1612: reading an invoice must not require authority to change one.
 *
 * <p>Every read route in this module used to be guarded by {@link InvoicePermissions#MANAGE} —
 * {@code GET /v1/invoices/search}, {@code /items/search}, {@code /{invoiceId}}, the two deposit
 * credit reads and the refund list. Seven roles that needed to look at an invoice were therefore
 * offered write authority or nothing, and {@code InvoiceFacadeTool} was unreachable for them: all
 * three of its methods are reads.
 *
 * <p>The guards are annotations, so the controller tests in this package cannot see them —
 * standalone MockMvc does not run method security, which is why the drift was free to happen. This
 * test reads the annotations directly. It is deliberately a rule rather than a list: a GET added
 * later and guarded on {@code MANAGE} fails here without anyone remembering this issue existed.
 */
@DisplayName("Invoice read routes require invoice:invoice:view (#1612)")
class InvoiceReadAuthorityTest {

    /**
     * Controllers whose reads are permission-guarded. {@code InvoiceArtifactController} and
     * {@code InvoiceArtifactDownloadController} are excluded on purpose: the first is
     * {@code isAuthenticated()} and the second is {@code permitAll()} behind a signed download
     * token, neither of which is a permission decision.
     */
    private static final List<Class<?>> GUARDED_CONTROLLERS = List.of(
            InvoiceController.class,
            InvoiceSearchController.class,
            DepositCreditController.class,
            PaymentReversalController.class);

    @Test
    @DisplayName("no GET handler is guarded on invoice:manage")
    void noReadRouteRequiresManage() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> controller : GUARDED_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isGet(method)) {
                    continue;
                }
                effectiveGuard(method)
                        .filter(guard -> guard.contains(InvoicePermissions.MANAGE))
                        .ifPresent(guard -> offenders.add(controller.getSimpleName() + "#" + method.getName()));
            }
        }

        assertThat(offenders)
                .as("read routes still demanding write authority — see #1612")
                .isEmpty();
    }

    @Test
    @DisplayName("every permission-guarded GET handler names invoice:invoice:view")
    void readRoutesRequireView() {
        List<String> checked = new ArrayList<>();
        for (Class<?> controller : GUARDED_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isGet(method)) {
                    continue;
                }
                String guard = effectiveGuard(method).orElse("");
                // A GET with no permission guard at all would silently pass the negative test
                // above, so require the positive statement here too.
                assertThat(guard)
                        .as("%s#%s", controller.getSimpleName(), method.getName())
                        .contains(InvoicePermissions.VIEW);
                checked.add(controller.getSimpleName() + "#" + method.getName());
            }
        }

        // Vacuity guard: this whole test is trivially green if the reflection finds no handlers,
        // which is exactly what a package move or an annotation change would cause.
        assertThat(checked)
                .as("no GET handlers found — the reflection, not the code, is what broke")
                .hasSize(6);
    }

    private static boolean isGet(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return true;
        }
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        return mapping != null && List.of(mapping.method()).contains(RequestMethod.GET);
    }

    /**
     * The method-level guard when present, else the class-level one — Spring resolves the most
     * specific {@code @PreAuthorize}, and three of these controllers carry a class-level
     * {@code MANAGE} that their reads now override.
     */
    private static Optional<String> effectiveGuard(Method method) {
        PreAuthorize onMethod = method.getAnnotation(PreAuthorize.class);
        if (onMethod != null) {
            return Optional.of(onMethod.value());
        }
        PreAuthorize onClass = method.getDeclaringClass().getAnnotation(PreAuthorize.class);
        return Optional.ofNullable(onClass).map(PreAuthorize::value);
    }
}
