package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Seed;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #2383: the accounts-payable and bank-reconciliation RAG documents, and the lexicon terms that let the
 * bake-off phrasings (en, fr-CA, es) seed {@code supplier}, {@code vendor-bill}, {@code credit-memo} and
 * {@code bank-reconciliation}. Before this change no preload document listed any of the four entities,
 * so every scope that named one borrowed accounting or purchase-order documents instead.
 */
class AccountsPayableBankRecRagCoverageTest {

    private static final String AP_DOC = "accounting.supplier-vendor-bills";
    private static final String BANK_REC_DOC = "accounting.bank-reconciliation";

    private static final TermMatcher LEXICON_MATCHER = TermMatcher.of(EntityLexiconLoader.loadDefault());

    private static StaticDocEntry entry(String profile, String id) {
        return ScopeGraphRealConfigValidationTest.ragDocs(profile).stream()
                .filter(doc -> doc.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError(id + " is missing from the " + profile + " preload list"));
    }

    private static Set<String> seededEntities(String message) {
        return LEXICON_MATCHER.match(message).stream().map(Seed::entity).collect(Collectors.toSet());
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("the AP guide is preloaded in accounting scope, gated on the AP and credit-memo audiences")
    void accountsPayableGuideEntry(String profile) {
        StaticDocEntry doc = entry(profile, AP_DOC);

        assertThat(doc.sourcePath()).isEqualTo("classpath:rag/supplier-vendor-bills-guide.md");
        assertThat(doc.ragScope()).isEqualTo("accounting");
        assertThat(doc.requiredPermissions()).containsExactly("accounting:ap:view", "accounting:credit-memo:read");
        assertThat(doc.entities()).containsExactly("supplier", "vendor-bill", "credit-memo");
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("the bank reconciliation document is preloaded in accounting scope, gated on reconciliation view")
    void bankReconciliationEntry(String profile) {
        StaticDocEntry doc = entry(profile, BANK_REC_DOC);

        assertThat(doc.sourcePath()).isEqualTo("classpath:rag/accounting-bank-reconciliation-rag.md");
        assertThat(doc.ragScope()).isEqualTo("accounting");
        assertThat(doc.requiredPermissions()).containsExactly("accounting:reconciliation:view");
        assertThat(doc.entities()).containsExactly("bank-reconciliation", "journal-entry");
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("supplier, vendor-bill, credit-memo and bank-reconciliation are each explained by a document")
    void everyFormerlyUncoveredEntityHasADocument(String profile) {
        List<StaticDocEntry> docs = ScopeGraphRealConfigValidationTest.ragDocs(profile);

        for (String entity : List.of("supplier", "vendor-bill", "credit-memo", "bank-reconciliation")) {
            assertThat(docs)
                    .as("%s preload documents listing %s", profile, entity)
                    .anySatisfy(doc -> assertThat(doc.entities()).contains(entity));
        }
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" seeds {1}")
    @CsvSource(delimiter = '|', textBlock = """
            What do we owe Michelin?                                         | vendor-bill
            What do we owe in accounts payable?                              | vendor-bill
            Show open vendor bills                                           | vendor-bill
            Quelles factures fournisseurs sont dues dans les 14 prochains jours? | vendor-bill
            ¿Qué facturas de proveedor vencen en los próximos 14 días?       | vendor-bill
            Issue a credit memo                                              | credit-memo
            Émets une note de crédit pour la facture                         | credit-memo
            Emite una nota de crédito para la factura                        | credit-memo
            Aplica el crédito del cliente a la factura abierta               | credit-memo
            Who is our supplier for wiper blades?                            | supplier
            Qui est notre fournisseur d'essuie-glaces?                       | supplier
            ¿Quién es nuestro proveedor de plumillas?                        | supplier
            Finish the bank reconciliation for the operating account         | bank-reconciliation
            Which bank transactions are still unmatched?                     | bank-reconciliation
            Rapprochement bancaire                                           | bank-reconciliation
            Quelles transactions bancaires ne sont pas encore rapprochées?   | bank-reconciliation
            conciliación bancaria                                            | bank-reconciliation
            ¿Qué movimientos bancarios siguen sin conciliar?                 | bank-reconciliation
            """)
    @DisplayName("representative en, fr-CA and es messages seed the intended entity from the shipped lexicon")
    void representativeMessagesSeedTheIntendedEntity(String message, String entity) {
        assertThat(seededEntities(message))
                .as("entities seeded by \"%s\"", message)
                .contains(entity);
    }

    @Test
    @DisplayName("the new terms stay narrow: customer debts, obligations and stock movements seed none of the four")
    void unrelatedMessagesDoNotSeedThem() {
        Set<String> guarded = Set.of("supplier", "vendor-bill", "credit-memo", "bank-reconciliation");

        assertThat(seededEntities("How much does the customer owe us?")).doesNotContainAnyElementsOf(guarded);
        assertThat(seededEntities("Nous devons commander plus de pneus")).doesNotContainAnyElementsOf(guarded);
        assertThat(seededEntities("Debemos pedir más llantas")).doesNotContainAnyElementsOf(guarded);
        assertThat(seededEntities("Transfer stock to the north branch")).doesNotContainAnyElementsOf(guarded);
        assertThat(seededEntities("Registra un movimiento de inventario")).doesNotContainAnyElementsOf(guarded);
        assertThat(seededEntities("Show open invoices for ACME Fleet"))
                .contains("invoice")
                .doesNotContainAnyElementsOf(guarded);
    }
}
