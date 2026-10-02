package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/**
 * #2382: the invoicing and payments guide ({@code accounting.invoicing-payments}) is the document
 * whose primary subject is the {@code invoice} and {@code payment} entities, which before it reached
 * documents only through borrowed shop-management and cross-domain playbook documents.
 *
 * <p>Defends the preload entry in both profiles, the entity coverage, the {@code invoice} tool
 * domain's RAG scope, the seeding of bake-off style messages in en, fr-CA and es, and the document's
 * grounding: every status value and permission code it names is read from the owning module's
 * source in the reactor checkout, so a renamed enum constant or a retired code fails here.
 */
class InvoicingPaymentsGuideTest {

    private static final String DOC_ID = "accounting.invoicing-payments";
    private static final String SOURCE_PATH = "classpath:rag/invoicing-payments-guide.md";
    private static final Set<String> GUIDE_ENTITIES = Set.of("invoice", "payment");

    private static final Path MODULE_DIR = Paths.get(System.getProperty("user.dir"));
    private static final Path INVOICE_ENUMS =
            MODULE_DIR.resolve("../pos-invoice/src/main/java/com/positivity/invoice/internal/enums");
    private static final Path INVOICE_SRC =
            MODULE_DIR.resolve("../pos-invoice/src/main/java/com/positivity/invoice/internal");
    private static final Path ACCOUNTING_SRC =
            MODULE_DIR.resolve("../pos-accounting/src/main/java/com/positivity/accounting/internal");
    private static final Path ACCOUNTING_ENUMS =
            MODULE_DIR.resolve("../pos-accounting/src/main/java/com/positivity/accounting/internal/enums");

    private static final Pattern ENUM_CONSTANT = Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)\\b");
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");
    private static final Pattern PERMISSION_NAME = Pattern.compile("-\\s*name:\\s*\"([^\"]+)\"");
    private static final Pattern BACKTICKED_CODE =
            Pattern.compile("`((?:invoice|accounting|reporting|workorder):[a-z0-9_:-]+)`");

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("the guide is preloaded in both profiles with its scope, permissions and entities")
    void preloadEntryInBothProfiles(String profile) {
        StaticDocEntry entry = guide(profile);

        assertThat(entry.sourcePath()).isEqualTo(SOURCE_PATH);
        assertThat(entry.ragScope()).isEqualTo("accounting");
        assertThat(entry.requiredPermissions())
                .containsExactlyInAnyOrder("invoice:invoice:view", "accounting:payment:apply");
        assertThat(entry.entities()).containsExactlyInAnyOrder("invoice", "payment");
        assertThat(new ClassPathResource("rag/invoicing-payments-guide.md").exists())
                .isTrue();
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("invoice and payment are each covered by a document whose primary subject they are")
    void invoiceAndPaymentHaveTheirOwnDocument(String profile) {
        List<StaticDocEntry> docs = ScopeGraphRealConfigValidationTest.ragDocs(profile);

        for (String entity : GUIDE_ENTITIES) {
            assertThat(docs)
                    .as("a %s document about %s alone (not a borrowed multi-domain one)", profile, entity)
                    .anySatisfy(doc -> {
                        assertThat(doc.entities()).contains(entity);
                        assertThat(GUIDE_ENTITIES).containsAll(doc.entities());
                        assertThat(doc.ragScope()).isNotEqualTo("master");
                    });
        }
    }

    @Test
    @DisplayName("the invoice tool domain's RAG scope is the guide's scope, for both of its entities")
    void invoiceDomainMapsToTheGuideScope() {
        EntityLexicon lexicon = EntityLexiconLoader.loadDefault();

        assertThat(lexicon.domainScopes()).containsEntry("invoice", "accounting");
        lexicon.entities().stream()
                .filter(entity -> GUIDE_ENTITIES.contains(entity.key()))
                .forEach(entity -> assertThat(lexicon.domainScopes().getOrDefault(entity.domain(), entity.domain()))
                        .as("RAG scope of %s's domain %s", entity.key(), entity.domain())
                        .isEqualTo(guide("default").ragScope()));
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "en    | Show unpaid invoices for Smith                       | invoice",
                "en    | Record a payment on INV-1727712000000-0a1b2c3d       | payment",
                "en    | Record a payment on INV-1727712000000-0a1b2c3d       | invoice",
                "en    | Was the deposit applied to the workorder invoice?   | payment",
                "fr-CA | Quelles factures sont en retard ?                    | invoice",
                "fr-CA | Enregistrer un paiement sur la facture de Tremblay   | payment",
                "es    | ¿Qué facturas están vencidas?                        | invoice",
                "es    | Registrar un pago en la factura de García            | payment",
            })
    @DisplayName("bake-off style invoice and payment messages seed the intended entity")
    void bakeOffMessagesSeed(String language, String message, String entity) {
        assertThat(seededEntities(message)).as("%s: %s", language, message).contains(entity);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "How many tires are in stock at the north location?",
                "Combien de pneus avons-nous en stock ?",
                "¿Cuántas llantas hay en inventario?",
            })
    @DisplayName("unrelated messages seed neither invoice nor payment")
    void unrelatedMessagesDoNotSeed(String message) {
        assertThat(seededEntities(message)).doesNotContainAnyElementsOf(GUIDE_ENTITIES);
    }

    @Test
    @DisplayName("every lifecycle and payment status the guide relies on is a real enum constant, and is named")
    void guideNamesTheRealStatusValues() throws IOException {
        String doc = guideText();

        for (Path enumFile : List.of(
                INVOICE_ENUMS.resolve("InvoiceStatus.java"),
                INVOICE_ENUMS.resolve("PaymentIntentStatus.java"),
                INVOICE_ENUMS.resolve("PaymentFlow.java"),
                INVOICE_ENUMS.resolve("PaymentTerms.java"),
                INVOICE_ENUMS.resolve("DepositCreditStatus.java"),
                INVOICE_ENUMS.resolve("DepositSourceType.java"),
                INVOICE_ENUMS.resolve("InvoiceAdjustmentType.java"),
                INVOICE_ENUMS.resolve("RefundReason.java"),
                ACCOUNTING_ENUMS.resolve("AllocationStrategy.java"),
                ACCOUNTING_ENUMS.resolve("PaymentStatus.java"))) {
            Set<String> constants = enumConstants(enumFile);
            assertThat(constants).as("constants of %s", enumFile.getFileName()).isNotEmpty();
            constants.forEach(constant -> assertThat(doc)
                    .as("%s.%s named in the guide", enumFile.getFileName(), constant)
                    .contains("`" + constant + "`"));
        }
    }

    @Test
    @DisplayName("every permission code the guide names is registered by its owning module")
    void guidePermissionCodesAreRegistered() throws IOException {
        Set<String> registered = new LinkedHashSet<>();
        for (String module : List.of("pos-invoice", "pos-accounting", "pos-workorder")) {
            registered.addAll(
                    permissionNames(MODULE_DIR.resolve("../" + module + "/src/main/resources/permissions.yaml")));
        }
        Matcher matcher = BACKTICKED_CODE.matcher(guideText());
        Set<String> named = new LinkedHashSet<>();
        while (matcher.find()) {
            named.add(matcher.group(1));
        }

        assertThat(named)
                .as("codes the guide names")
                .contains(
                        "invoice:invoice:view",
                        "accounting:payment:apply",
                        "invoice:payment:refund",
                        "invoice:payment:process",
                        "invoice:payment:limit_override",
                        "invoice:payment:flow_select",
                        "invoice:payment:capture");
        assertThat(registered)
                .as("pos-invoice, pos-accounting and pos-workorder permissions.yaml")
                .containsAll(named);
    }

    @Test
    @DisplayName("#2393: the card payment permissions the guide names are the ones pos-invoice enforces")
    void cardPaymentPermissionsMatchTheCode() throws IOException {
        String permissions = Files.readString(INVOICE_SRC.resolve("security/InvoicePermissions.java"));
        String controller = Files.readString(INVOICE_SRC.resolve("controller/PaymentController.java"));
        String service = Files.readString(INVOICE_SRC.resolve("service/PaymentServiceImpl.java"));
        String doc = flatGuideText();

        assertThat(permissions)
                .contains("PAYMENT_PROCESS = \"invoice:payment:process\"")
                .contains("PAYMENT_LIMIT_OVERRIDE = \"invoice:payment:limit_override\"")
                .contains("PAYMENT_FLOW_SELECT = \"invoice:payment:flow_select\"")
                .contains("PAYMENT_CAPTURE = \"invoice:payment:capture\"");
        // The two unconditional codes are the endpoints' own @PreAuthorize...
        assertThat(controller)
                .containsPattern(
                        "@PostMapping\\(\"/\\{invoiceId}/payments\"\\)\\s*"
                                + "@PreAuthorize\\(\"hasAuthority\\('\" \\+ InvoicePermissions\\.PAYMENT_PROCESS \\+ \"'\\)\"\\)")
                .containsPattern(
                        "@PostMapping\\(\"/\\{invoiceId}/payments/\\{paymentId}/capture\"\\)\\s*"
                                + "@PreAuthorize\\(\"hasAuthority\\('\" \\+ InvoicePermissions\\.PAYMENT_CAPTURE \\+ \"'\\)\"\\)");
        // ...and the two conditional ones are checked in the service against the 500.00 threshold
        // (strictly above it) and the AUTH_ONLY flow.
        assertThat(service)
                .contains("PAYMENT_LIMIT_THRESHOLD = new BigDecimal(\"500.00\")")
                .contains("request.getAmount().compareTo(PAYMENT_LIMIT_THRESHOLD) > 0")
                .containsPattern("exceedsPaymentLimit\\(request\\)\\s*&& !SecurityContextHelper\\.hasAuthority\\("
                        + "InvoicePermissions\\.PAYMENT_LIMIT_OVERRIDE\\)")
                .containsPattern("request\\.getPaymentFlow\\(\\) == PaymentFlow\\.AUTH_ONLY\\s*"
                        + "&& !SecurityContextHelper\\.hasAuthority\\(InvoicePermissions\\.PAYMENT_FLOW_SELECT\\)");

        assertThat(doc)
                .contains("| Take card tender | `POST /v1/invoices/{invoiceId}/payments` | `invoice:payment:process` ")
                .contains(
                        "| Capture an authorized hold | `POST /v1/invoices/{invoiceId}/payments/{paymentId}/capture` |"
                                + " `invoice:payment:capture` |")
                .contains("`invoice:payment:limit_override` when the amount is above 500.00")
                .contains("`invoice:payment:flow_select` when the request asks for the `AUTH_ONLY` flow");

        // The retired raw strings no role could hold are gone from the guide and from the code.
        for (String legacy :
                List.of("PROCESS_PAYMENT", "OVERRIDE_PAYMENT_LIMIT", "SELECT_PAYMENT_FLOW", "MANUAL_CAPTURE")) {
            assertThat(doc).as("guide").doesNotContain(legacy);
            assertThat(controller).as("PaymentController").doesNotContain(legacy);
            assertThat(service).as("PaymentServiceImpl").doesNotContain(legacy);
        }
    }

    @Test
    @DisplayName("a declined payment persists nothing: class-level transaction, unchecked decline exception")
    void declinedPaymentRollsBack() throws IOException {
        String service = Files.readString(INVOICE_SRC.resolve("service/PaymentServiceImpl.java"));
        String exception = Files.readString(INVOICE_SRC.resolve("exception/PaymentDeclinedException.java"));

        assertThat(service).containsPattern("(?m)^@Transactional\\s*\\npublic class PaymentServiceImpl");
        assertThat(service).doesNotContain("noRollbackFor");
        assertThat(exception).contains("class PaymentDeclinedException extends RuntimeException");
        assertThat(flatGuideText()).contains("leaves **no** payment intent").contains("leaves the intent `AUTHORIZED`");
    }

    @Test
    @DisplayName("application reversal: single reversal refused for multi-invoice requests, whole-payment endpoint")
    void applicationReversalRulesMatchTheCode() throws IOException {
        String handler = Files.readString(ACCOUNTING_SRC.resolve("config/AccountingExceptionHandler.java"));
        String controller = Files.readString(ACCOUNTING_SRC.resolve("controller/PaymentApplicationController.java"));
        String permissions = Files.readString(ACCOUNTING_SRC.resolve("security/AccountingPermissions.java"));
        String doc = flatGuideText();

        assertThat(handler).containsPattern("UNPROCESSABLE_CONTENT,\\s*\"WHOLE_REQUEST_REVERSAL_REQUIRED\"");
        assertThat(controller)
                .containsPattern(
                        "@PostMapping\\(\"/payments/\\{paymentId}/reverse\"\\)(?s:(?!@PostMapping).)*AccountingPermissions\\.AP_PAY\\b");
        assertThat(permissions).contains("AP_PAY = \"accounting:ap:pay\"");
        assertThat(doc)
                .contains("422 `WHOLE_REQUEST_REVERSAL_REQUIRED`")
                .contains("`POST /v1/accounting/payments/{paymentId}/reverse` (`accounting:ap:pay`");
    }

    @Test
    @DisplayName("no invoice or payment-status response exposes the due date, as the guide says")
    void noResponseExposesTheDueDate() throws IOException {
        for (Path dto : List.of(
                INVOICE_SRC.resolve("dto/InvoiceDetailsResponse.java"),
                INVOICE_SRC.resolve("dto/InvoiceSearchResult.java"),
                ACCOUNTING_SRC.resolve("dto/InvoiceStatusResponse.java"),
                ACCOUNTING_SRC.resolve("dto/AgedReceivablesRow.java"))) {
            assertThat(Files.readString(dto)).as("%s", dto.getFileName()).doesNotContainIgnoringCase("dueDate");
        }
        assertThat(guideText()).contains("**No invoice-level overdue lookup.**");
    }

    private static StaticDocEntry guide(String profile) {
        return ScopeGraphRealConfigValidationTest.ragDocs(profile).stream()
                .filter(doc -> DOC_ID.equals(doc.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(DOC_ID + " is not preloaded under profile " + profile));
    }

    private static Set<String> seededEntities(String message) {
        return new LexiconLookup()
                .seeds(message).stream().map(ScopeSet.Seed::entity).collect(Collectors.toSet());
    }

    /** The guide with every whitespace run folded to one space, so assertions survive re-wrapping. */
    private static String flatGuideText() throws IOException {
        return guideText().replaceAll("\\s+", " ");
    }

    private static String guideText() throws IOException {
        return new ClassPathResource("rag/invoicing-payments-guide.md").getContentAsString(StandardCharsets.UTF_8);
    }

    /** The constants of an enum: the comma-separated identifiers before its first {@code ;}. */
    private static Set<String> enumConstants(Path enumFile) throws IOException {
        String source = COMMENT.matcher(Files.readString(enumFile)).replaceAll(" ");
        String body = source.substring(source.indexOf('{', source.indexOf(" enum ")) + 1);
        int end = body.indexOf(';');
        String constantList = body.substring(0, end >= 0 ? end : body.indexOf('}'));
        Set<String> constants = new LinkedHashSet<>();
        for (String token : constantList.split(",")) {
            Matcher matcher = ENUM_CONSTANT.matcher(token);
            if (matcher.find()) {
                constants.add(matcher.group(1));
            }
        }
        return constants;
    }

    private static Set<String> permissionNames(Path permissionsYaml) throws IOException {
        Matcher matcher = PERMISSION_NAME.matcher(Files.readString(permissionsYaml));
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
