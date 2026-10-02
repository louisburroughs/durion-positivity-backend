package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/**
 * #2396: the purchase-order guide ({@code inventory.purchase-orders}) and the glossary's "PO number"
 * entry described the {@code pos-inventory} copy of the aggregate for months after #1331 and #1334
 * moved it into {@code pos-order}, so the assistant cited endpoints and permission codes that no
 * longer exist.
 *
 * <p>Pins the facts most likely to rot again, each against the owning module's source in the reactor
 * checkout rather than a hard-coded copy: the {@code order:purchase_order:*} codes come from
 * pos-order's {@code permissions.yaml}, the endpoints and event ids from {@code
 * PurchaseOrderController}, the statuses from {@code PurchaseOrderStatus}, the receiving step from
 * pos-inventory's {@code AsnController}, and the number format from {@code
 * PurchaseOrderServiceImpl}. The retired names are the only hard-coded list, because nothing in the
 * code states them any more.
 */
class PurchaseOrderRagCoverageTest {

    private static final String DOC_ID = "inventory.purchase-orders";
    private static final String GUIDE = "rag/inventory-purchase-orders-rag.md";
    private static final String CODES = "rag/inventory-codes-rag.md";
    private static final String GLOSSARY = "rag/glossary-identifiers.md";
    private static final String CLASSPATH_PREFIX = "classpath:";

    private static final Path MODULE_DIR = Paths.get(System.getProperty("user.dir"));
    private static final Path ORDER_SRC =
            MODULE_DIR.resolve("../pos-order/src/main/java/com/positivity/order/internal");
    private static final Path ORDER_PERMISSIONS =
            MODULE_DIR.resolve("../pos-order/src/main/resources/permissions.yaml");
    private static final Path INVENTORY_SRC =
            MODULE_DIR.resolve("../pos-inventory/src/main/java/com/positivity/inventory/internal");
    private static final Path INVENTORY_PERMISSIONS =
            MODULE_DIR.resolve("../pos-inventory/src/main/resources/permissions.yaml");

    /** Retired by #1334: pos-order registers {@code order:purchase_order:*} in their place. */
    private static final List<String> RETIRED_PERMISSIONS = List.of(
            "inventory:purchase_order:create", "inventory:purchase_order:view", "inventory:purchase_order:approve");

    private static final String RETIRED_BRACE_FORM = "inventory:purchase_order:{";
    private static final String RETIRED_BASE_PATH = "/v1/inventory/purchase-orders";

    /** The event ids of the pos-inventory controller that #1334 removed. */
    private static final List<String> RETIRED_EVENT_IDS = List.of(
            "INVENTORY_PURCHASE_ORDER_CREATE",
            "INVENTORY_PURCHASE_ORDER_GET",
            "INVENTORY_PURCHASE_ORDER_LIST",
            "INVENTORY_PURCHASE_ORDER_APPROVE",
            "INVENTORY_PURCHASE_ORDER_REVISE",
            "INVENTORY_PURCHASE_ORDER_CANCEL");

    private static final String STILL_DECLARED_RECEIVE_CODE = "inventory:purchase_order:receive";

    private static final Pattern PERMISSION_NAME = Pattern.compile("-\\s*name:\\s*\"([^\"]+)\"");
    private static final Pattern BACKTICKED_CODE = Pattern.compile("`((?:order|inventory):[a-z0-9_:-]+)`");
    private static final Pattern EMIT_EVENT = Pattern.compile("@EmitEvent\\(id = \"([A-Z_]+)\"");
    private static final Pattern ORDER_EVENT_ID = Pattern.compile("\\bORDER_PURCHASE_ORDER_[A-Z_]+\\b");
    private static final Pattern MAPPING =
            Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping(?:\\(\"([^\"]*)\"\\))?");
    private static final Pattern GUIDE_ORDER_ENDPOINT =
            Pattern.compile("`((?:GET|POST|PUT|PATCH|DELETE) /v1/orders/purchase-orders[^`]*)`");
    private static final Pattern REQUEST_MAPPING = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)");
    private static final Pattern ENUM_CONSTANT = Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)\\b");
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");
    private static final Pattern FRONT_MATTER_PERMISSION = Pattern.compile("(?m)^\\s+-\\s+(\\S+)\\s*$");
    private static final Pattern INLINE_PERMISSIONS = Pattern.compile("(?m)^Required permissions:\\s*(.+?)\\s*$");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("the guide keeps its id, file and scope, and is gated on the pos-order view code")
    void preloadEntryInBothProfiles(String profile) {
        StaticDocEntry entry = ScopeGraphRealConfigValidationTest.ragDocs(profile).stream()
                .filter(doc -> DOC_ID.equals(doc.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(DOC_ID + " is not preloaded under profile " + profile));

        assertThat(entry.sourcePath()).isEqualTo(CLASSPATH_PREFIX + GUIDE);
        assertThat(entry.ragScope()).isEqualTo("inventory");
        assertThat(entry.requiredPermissions()).containsExactly("order:purchase_order:view");
        assertThat(entry.entities()).containsExactly("purchase-order");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {GUIDE, CODES})
    @DisplayName("the inline header states the same permissions as the front matter")
    void inlineHeaderAgreesWithFrontMatter(String resource) {
        // RagDocumentHeaderAgreementTest reads the front matter when a document has one and never
        // looks at the inline lines, which is how these two came to disagree with themselves.
        String text = read(resource);
        int end = text.indexOf("\n---", 3);
        assertThat(text).as("%s front matter", resource).startsWith("---\n");
        assertThat(end).as("%s front matter end", resource).isPositive();

        String frontMatter = text.substring(0, end);
        Set<String> declared = new TreeSet<>();
        Matcher item =
                FRONT_MATTER_PERMISSION.matcher(frontMatter.substring(frontMatter.indexOf("required_permissions:")));
        while (item.find()) {
            declared.add(item.group(1));
        }
        Matcher inline = INLINE_PERMISSIONS.matcher(text.substring(end));
        assertThat(inline.find())
                .as("%s inline Required permissions line", resource)
                .isTrue();
        // Both inline spellings in use: backticked codes, or bare codes separated by commas.
        Set<String> stated = new TreeSet<>(matches(BACKTICKED, inline.group(1), 1));
        if (stated.isEmpty()) {
            for (String bare : inline.group(1).split(",")) {
                stated.add(bare.strip());
            }
        }

        assertThat(declared).as("%s front matter permissions", resource).isNotEmpty();
        assertThat(stated).as("%s inline permissions vs front matter", resource).isEqualTo(declared);
    }

    @Test
    @DisplayName("the guide names pos-order, its base path and every order:purchase_order code it registers")
    void guideNamesTheOwnerAndEveryRegisteredCode() throws IOException {
        String guide = read(GUIDE);
        Set<String> purchaseOrderCodes = permissionNames(ORDER_PERMISSIONS).stream()
                .filter(name -> name.startsWith("order:purchase_order:"))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(purchaseOrderCodes)
                .as("order:purchase_order:* in pos-order permissions.yaml")
                .contains("order:purchase_order:view")
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(guide).contains("pos-order").contains("/v1/orders/purchase-orders");
        purchaseOrderCodes.forEach(
                code -> assertThat(guide).as("%s named in the guide", code).contains("`" + code + "`"));
    }

    @Test
    @DisplayName("every endpoint of PurchaseOrderController is in the guide with its event id")
    void guideListsEveryEndpointAndEventId() throws IOException {
        String controller = Files.readString(ORDER_SRC.resolve("controller/PurchaseOrderController.java"));
        String guide = read(GUIDE);
        Matcher base = REQUEST_MAPPING.matcher(controller);
        assertThat(base.find()).as("@RequestMapping on PurchaseOrderController").isTrue();
        assertThat(base.group(1)).isEqualTo("/v1/orders/purchase-orders");

        Matcher mapping = MAPPING.matcher(controller);
        Set<String> endpoints = new TreeSet<>();
        while (mapping.find()) {
            String method = mapping.group(1).toUpperCase(java.util.Locale.ROOT);
            endpoints.add(method + " " + base.group(1) + (mapping.group(2) == null ? "" : mapping.group(2)));
        }
        assertThat(endpoints).as("mappings found in PurchaseOrderController").hasSizeGreaterThanOrEqualTo(7);
        // Both directions: a row the guide keeps after its mapping is removed or renamed is the
        // stale surface this test exists to catch, so the two sets must be equal, not one within
        // the other.
        assertThat(new TreeSet<>(matches(GUIDE_ORDER_ENDPOINT, guide, 1)))
                .as("purchase-order endpoints the guide lists vs PurchaseOrderController's mappings")
                .isEqualTo(endpoints);

        Set<String> emitted = new TreeSet<>(matches(EMIT_EVENT, controller, 1));
        assertThat(emitted).as("@EmitEvent ids on PurchaseOrderController").hasSameSizeAs(endpoints);
        assertThat(new TreeSet<>(matches(ORDER_EVENT_ID, guide, 0)))
                .as("ORDER_PURCHASE_ORDER_* ids the guide names vs the controller's @EmitEvent ids")
                .isEqualTo(emitted);

        String registry = Files.readString(ORDER_SRC.resolve("config/EventTypes.java"));
        matches(ORDER_EVENT_ID, guide, 0)
                .forEach(id -> assertThat(registry)
                        .as("%s registered in pos-order EventTypes", id)
                        .contains("\"" + id + "\""));
    }

    @Test
    @DisplayName("every PurchaseOrderStatus constant is named, and every code the guide names is registered")
    void guideNamesRealStatusesAndRegisteredCodes() throws IOException {
        String guide = read(GUIDE);

        Set<String> statuses = enumConstants(ORDER_SRC.resolve("enums/PurchaseOrderStatus.java"));
        assertThat(statuses).as("PurchaseOrderStatus constants").contains("DRAFT", "APPROVED");
        statuses.forEach(status -> assertThat(guide)
                .as("PurchaseOrderStatus.%s named in the guide", status)
                .contains("`" + status + "`"));

        Set<String> registered = new LinkedHashSet<>(permissionNames(ORDER_PERMISSIONS));
        registered.addAll(permissionNames(INVENTORY_PERMISSIONS));
        assertThat(registered)
                .as("pos-order and pos-inventory permissions.yaml")
                .containsAll(matches(BACKTICKED_CODE, guide, 1));
    }

    @Test
    @DisplayName("receiving stays in pos-inventory: the goods-receipt endpoint, its code and its event id")
    void guideKeepsTheReceivingStepAsPosInventoryImplementsIt() throws IOException {
        String asnController = Files.readString(INVENTORY_SRC.resolve("controller/AsnController.java"));
        String registry = Files.readString(INVENTORY_SRC.resolve("security/InventoryPermissionRegistry.java"));
        String guide = flat(read(GUIDE));

        assertThat(asnController).contains("@RequestMapping(\"/v1/inventory\")");
        assertThat(asnController)
                .containsPattern("@PostMapping\\(\"/goods-receipts\"\\)(?s:(?!@PostMapping|@GetMapping).)*"
                        + "InventoryPermissionRegistry\\.GOODS_RECEIPT_CREATE(?s:(?!@PostMapping|@GetMapping).)*"
                        + "@EmitEvent\\(id = \"INVENTORY_GOODS_RECEIPT_CREATE\"");
        assertThat(registry).contains("GOODS_RECEIPT_CREATE = \"inventory:goods_receipt:create\"");
        assertThat(guide)
                .contains("`POST /v1/inventory/goods-receipts`")
                .contains("`inventory:goods_receipt:create`")
                .contains("`INVENTORY_GOODS_RECEIPT_CREATE`");

        // The guide says the old receive code is still declared and gates nothing. Both halves are
        // read from pos-inventory, so wiring the code to an endpoint, or deleting it, fails here.
        assertThat(permissionNames(INVENTORY_PERMISSIONS)).contains(STILL_DECLARED_RECEIVE_CODE);
        try (Stream<Path> controllers = Files.walk(INVENTORY_SRC.resolve("controller"))) {
            for (Path controller : controllers.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(controller))
                        .as("%s is not gated on the purchase-order receive code", controller.getFileName())
                        .doesNotContain("PURCHASE_ORDER_RECEIVE")
                        .doesNotContain("hasAuthority('" + STILL_DECLARED_RECEIVE_CODE + "')");
            }
        }
        assertThat(guide).contains("`" + STILL_DECLARED_RECEIVE_CODE + "`").contains("no endpoint is gated by it");
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("no preloaded document names a retired purchase-order code, path or event id")
    void noDocumentNamesTheRetiredSurface(String profile) {
        for (StaticDocEntry doc : ScopeGraphRealConfigValidationTest.ragDocs(profile)) {
            String text = read(doc.sourcePath().substring(CLASSPATH_PREFIX.length()));
            assertThat(text)
                    .as("%s (%s)", doc.sourcePath(), profile)
                    .doesNotContain(RETIRED_PERMISSIONS)
                    .doesNotContain(RETIRED_EVENT_IDS)
                    .doesNotContain(RETIRED_BRACE_FORM, RETIRED_BASE_PATH);
        }
    }

    @Test
    @DisplayName("the glossary's PO number entry names pos-order as owner and the format the code produces")
    void glossaryPoNumberEntryMatchesTheGenerator() throws IOException {
        String glossary = read(GLOSSARY);
        int start = glossary.indexOf("\n## PO number\n");
        assertThat(start).as("the 'PO number' section").isPositive();
        String section = flat(glossary.substring(start, glossary.indexOf("\n## ", start + 1)));

        assertThat(section)
                .contains("owned by `pos-order`")
                .contains("_Verified: `pos-order` `PurchaseOrderServiceImpl.generatePoNumber()`")
                .contains("`purchase_order_number_seq`")
                .contains("`order:purchase_order:view`")
                .doesNotContain("owned by `pos-inventory`")
                .doesNotContain("NOT pos-order")
                .doesNotContain("inventory:purchase_order:");

        String service = Files.readString(ORDER_SRC.resolve("service/PurchaseOrderServiceImpl.java"));
        String repository = Files.readString(ORDER_SRC.resolve("repository/PurchaseOrderRepository.java"));
        assertThat(service)
                .contains("private String generatePoNumber()")
                .contains("String.format(Locale.ROOT, \"%8s\", Long.toString(sequence, 36))")
                .contains(".replace(' ', '0')")
                .contains(".toUpperCase(Locale.ROOT)");
        assertThat(repository).contains("SELECT nextval('purchase_order_number_seq')");
        assertThat(section).contains("8-character, zero-padded, uppercase base-36");
    }

    private static Set<String> matches(Pattern pattern, String text, int group) {
        Matcher matcher = pattern.matcher(text);
        Set<String> found = new LinkedHashSet<>();
        while (matcher.find()) {
            found.add(matcher.group(group));
        }
        return found;
    }

    /** Every whitespace run folded to one space, so assertions survive re-wrapping. */
    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String read(String resource) {
        try {
            return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
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
        return matches(PERMISSION_NAME, Files.readString(permissionsYaml), 1);
    }
}
