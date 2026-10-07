package com.positivity.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.entity.CustomerCredit;
import com.positivity.accounting.internal.service.PaymentApplicationServiceImpl;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ArchitectureTest#customer_credits_are_issued_only_on_the_guarded_posted_path} catches a second
 * {@code CustomerCredit} issuer and passes the guarded, posted one (#2554).
 */
@DisplayName("ArchUnit: a CustomerCredit outside the guarded, posted path is caught (#2554)")
class CustomerCreditIssuanceRuleTest {

    /** The shape of the deleted legacy overpayment path: a credit with no CASH guard and no GL posting. */
    static final class IssuesAnUnpostedCredit {
        CustomerCredit overpayment(BigDecimal excess) {
            CustomerCredit credit = new CustomerCredit();
            credit.setAmount(excess);
            return credit;
        }
    }

    static final class ReadsACredit {
        BigDecimal amount(CustomerCredit credit) {
            return credit.getAmount();
        }
    }

    private static boolean violates(Class<?>... types) {
        // Only the fixtures themselves: no classpath resolution (archunit.properties), so the import stays small.
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importClasses(types);
        return ArchitectureTest.customer_credits_are_issued_only_on_the_guarded_posted_path
                .evaluate(classes)
                .hasViolation();
    }

    @Test
    @DisplayName("a second class constructing a CustomerCredit violates; reading one does not")
    void catchesASecondIssuer() {
        assertThat(violates(IssuesAnUnpostedCredit.class)).isTrue();
        assertThat(violates(ReadsACredit.class)).isFalse();
    }

    @Test
    @DisplayName("PaymentApplicationServiceImpl, the CASH-guarded and GL-posted issuer, passes")
    void passesTheGuardedPostedIssuer() {
        // ReadsACredit keeps the rule's subject set non-empty: the issuer itself is excluded by name.
        assertThat(violates(PaymentApplicationServiceImpl.class, ReadsACredit.class))
                .isFalse();
    }
}
