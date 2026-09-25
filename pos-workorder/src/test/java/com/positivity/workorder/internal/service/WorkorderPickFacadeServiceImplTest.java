package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.workorder.internal.config.InventoryCommandPublisher;
import com.positivity.workorder.internal.dto.pick.CompletePickTaskRequest;
import com.positivity.workorder.internal.dto.pick.ConfirmPickLineRequest;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsRequest;
import com.positivity.workorder.internal.dto.pick.ResolveScanRequest;
import com.positivity.workorder.internal.dto.pick.ResolveScanResponse;
import com.positivity.workorder.internal.entity.ExtPickListReplica;
import com.positivity.workorder.internal.entity.ExtPickTaskReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.repository.ExtPickListReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPickTaskReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Branch-coverage tests for {@link WorkorderPickFacadeServiceImpl}, which was at
 * 0% branch coverage.
 *
 * <p>
 * This facade sits over a replica of pos-inventory's pick state and turns
 * warehouse scans into asynchronous commands (ADR-0044, #901). Two families of
 * branch carry the weight:
 *
 * <ul>
 * <li><b>Scan classification.</b> A scan is graded MATCHED, SKU_MISMATCH,
 * LOCATION_MISMATCH or NO_MATCH from two independent booleans. The picker acts
 * on that word — it tells them whether they are at the wrong bin or holding the
 * wrong part — and the two mismatch cases are trivially swappable, since one is
 * decided by the sku comparison and the other by the location comparison. The
 * full truth table is asserted.</li>
 * <li><b>Refusal to publish half-formed commands.</b> Every write here becomes a
 * Kafka command that pos-inventory will act on, so the facade fails closed twice
 * over: 409 if the replica is not yet complete enough to describe the task, and
 * 503 if the publisher is absent or the broker refuses. Both must stay
 * distinguishable — a 409 is a data-timing problem the caller cannot fix by
 * retrying, a 503 is exactly the one they should retry.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderPickFacadeServiceImpl — scan grading and command publishing")
class WorkorderPickFacadeServiceImplTest {

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PICK_LIST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID SKU_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a5");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @Mock
    private ExtPickListReplicaRepository pickListReplicaRepository;

    @Mock
    private ExtPickTaskReplicaRepository pickTaskReplicaRepository;

    @Mock
    private ObjectProvider<InventoryCommandPublisher> publisherProvider;

    @Mock
    private InventoryCommandPublisher publisher;

    @Mock
    private WorkorderRepository workorderRepository;

    private WorkorderPickFacadeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkorderPickFacadeServiceImpl(
                pickListReplicaRepository, pickTaskReplicaRepository, publisherProvider, workorderRepository);
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);
        pickListExists();
        taskExists(task());
        // No workorder row by default: requireLocationScope skips (#2204), so every existing test
        // above is unaffected by the new gate unless it opts in via workorderAt(...).
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void pickListExists() {
        ExtPickListReplica list = new ExtPickListReplica();
        list.setPickListId(PICK_LIST_ID);
        list.setWorkorderId(WORKORDER_ID);
        list.setStatus("OPEN");
        when(pickListReplicaRepository.findByWorkorderIdOrderByPickListIdAsc(WORKORDER_ID))
                .thenReturn(List.of(list));
    }

    private void taskExists(ExtPickTaskReplica task) {
        when(pickTaskReplicaRepository.findByPickListIdOrderBySortOrderAsc(PICK_LIST_ID))
                .thenReturn(List.of(task));
    }

    private static ExtPickTaskReplica task() {
        ExtPickTaskReplica task = new ExtPickTaskReplica();
        task.setPickTaskId(TASK_ID);
        task.setPickListId(PICK_LIST_ID);
        task.setWorkorderId(WORKORDER_ID);
        task.setSkuId(SKU_ID);
        task.setLocationId(LOCATION_ID);
        task.setQuantityRequired(10);
        task.setQuantityPicked(0);
        task.setQuantityConsumed(0);
        task.setStatus("OPEN");
        return task;
    }

    private static ResolveScanRequest scan(UUID sku, UUID location) {
        ResolveScanRequest request = new ResolveScanRequest();
        request.setScannedSkuId(sku);
        request.setScannedLocationId(location);
        return request;
    }

    private static ResolveScanRequest scanByCode(String productCode, String locationCode) {
        ResolveScanRequest request = new ResolveScanRequest();
        request.setScannedProductCode(productCode);
        request.setScannedLocationCode(locationCode);
        return request;
    }

    // ─── location scope helpers (#2204) ────────────────────────────────────────

    private void workorderAt(UUID locationId) {
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setLocationId(locationId);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
    }

    /** A resolver whose only ancestor of a node is the node itself — enough for require()'s check. */
    private static final LocationAncestorResolver SELF_RESOLVER =
            locationId -> new AncestorSets(Set.of(locationId), Set.of(locationId));

    /** A post-rollout caller whose {@code permission} is scoped to exactly {@code assignedNode}. */
    private static void authenticateScopedOn(String permission, UUID assignedNode) {
        authenticateAs(
                LocationScope.of(Set.of(), Set.of(permission), Optional.of(Set.of(assignedNode)), true, SELF_RESOLVER));
    }

    /** A post-rollout caller whose token carries claims but {@code permission} is not scoped (global grant). */
    private static void authenticateGlobalOn(UUID assignedNode) {
        authenticateAs(LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(assignedNode)), true, SELF_RESOLVER));
    }

    /** A pre-rollout caller: no {@code loc_*} claims at all. */
    private static void authenticatePreRollout() {
        var token = new UsernamePasswordAuthenticationToken(
                "scope-test-user", null, List.of(new SimpleGrantedAuthority("ROLE_TEST")));
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "scope-test-user"));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static void authenticateAs(LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken(
                "scope-test-user", null, List.of(new SimpleGrantedAuthority("ROLE_TEST")));
        token.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "scope-test-user",
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                scope));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // ─── scan classification ─────────────────────────────────────────────────

    @ParameterizedTest(name = "sku {0}, location {1} -> {2}")
    @CsvSource({
        "right, right, MATCHED",
        "right, wrong, LOCATION_MISMATCH",
        "wrong, right, SKU_MISMATCH",
        "wrong, wrong, NO_MATCH",
    })
    @DisplayName("a scan is graded from the sku and location independently")
    void scanClassificationTruthTable(String skuCase, String locationCase, String expected) {
        UUID scannedSku = "right".equals(skuCase) ? SKU_ID : OTHER_ID;
        UUID scannedLocation = "right".equals(locationCase) ? LOCATION_ID : OTHER_ID;

        ResolveScanResponse response = service.resolveScan(WORKORDER_ID, TASK_ID, scan(scannedSku, scannedLocation));

        // The picker acts on this word: LOCATION_MISMATCH sends them to another bin,
        // SKU_MISMATCH tells them the part in their hand is wrong. The two are decided by
        // opposite comparisons and would be silently swappable without this table.
        assertThat(response.getMatchStatus()).isEqualTo(expected);
        assertThat(response.isMatched()).isEqualTo("MATCHED".equals(expected));
        // The scan is echoed back as-is, so a handheld can show what it read.
        assertThat(response.getResolvedSkuId()).isEqualTo(scannedSku);
        assertThat(response.getResolvedLocationId()).isEqualTo(scannedLocation);
    }

    @ParameterizedTest(name = "replica missing {0} grades as NO_MATCH rather than matching")
    @CsvSource({"sku", "location"})
    @DisplayName("a replica field that has not arrived yet cannot produce a match")
    void incompleteReplicaNeverMatches(String missing) {
        ExtPickTaskReplica task = task();
        if ("sku".equals(missing)) {
            task.setSkuId(null);
        } else {
            task.setLocationId(null);
        }
        taskExists(task);

        // Null on the replica side must compare as "does not match", never as "matches
        // anything" — the replica fills in asynchronously, and a null-tolerant comparison here
        // would confirm a pick against a task we cannot yet describe.
        ResolveScanResponse response = service.resolveScan(WORKORDER_ID, TASK_ID, scan(SKU_ID, LOCATION_ID));

        assertThat(response.isMatched()).isFalse();
        assertThat(response.getMatchStatus()).isIn("SKU_MISMATCH", "LOCATION_MISMATCH");
    }

    // ─── scan by code (#2217) ───────────────────────────────────────────────────

    @Test
    @DisplayName("a code-based scan matches when both the product code and a location code match")
    void codeScanMatches() {
        ExtPickTaskReplica task = task();
        task.setProductCode("0123456789012");
        task.setLocationName("Aisle 3 Bin 7");
        task.setLocationBarcode("LOC-0037");
        taskExists(task);

        ResolveScanResponse response =
                service.resolveScan(WORKORDER_ID, TASK_ID, scanByCode("0123456789012", "LOC-0037"));

        assertThat(response.isMatched()).isTrue();
        assertThat(response.getMatchStatus()).isEqualTo("MATCHED");
        // Resolved by code: the caller learns the task's own ids only once matched.
        assertThat(response.getResolvedSkuId()).isEqualTo(SKU_ID);
        assertThat(response.getResolvedLocationId()).isEqualTo(LOCATION_ID);
        assertThat(response.getExpectedProductCode()).isEqualTo("0123456789012");
        assertThat(response.getExpectedLocationCode()).isEqualTo("Aisle 3 Bin 7");
        assertThat(response.getExpectedLocationBarcode()).isEqualTo("LOC-0037");
    }

    @Test
    @DisplayName("a location code matches against either the name or the barcode")
    void locationCodeMatchesEitherNameOrBarcode() {
        ExtPickTaskReplica task = task();
        task.setProductCode("0123456789012");
        task.setLocationName("Aisle 3 Bin 7");
        task.setLocationBarcode("LOC-0037");
        taskExists(task);

        assertThat(service.resolveScan(WORKORDER_ID, TASK_ID, scanByCode("0123456789012", "Aisle 3 Bin 7"))
                        .isMatched())
                .isTrue();
        assertThat(service.resolveScan(WORKORDER_ID, TASK_ID, scanByCode("0123456789012", "  loc-0037  "))
                        .isMatched())
                .isTrue();
    }

    @Test
    @DisplayName("a wrong product code is SKU_MISMATCH when the location code matches")
    void codeScanProductMismatch() {
        ExtPickTaskReplica task = task();
        task.setProductCode("0123456789012");
        task.setLocationBarcode("LOC-0037");
        taskExists(task);

        ResolveScanResponse response =
                service.resolveScan(WORKORDER_ID, TASK_ID, scanByCode("9999999999999", "LOC-0037"));

        assertThat(response.isMatched()).isFalse();
        assertThat(response.getMatchStatus()).isEqualTo("SKU_MISMATCH");
        assertThat(response.getResolvedSkuId()).isNull();
    }

    @Test
    @DisplayName("a wrong location code is LOCATION_MISMATCH when the product code matches")
    void codeScanLocationMismatch() {
        ExtPickTaskReplica task = task();
        task.setProductCode("0123456789012");
        task.setLocationBarcode("LOC-0037");
        taskExists(task);

        ResolveScanResponse response =
                service.resolveScan(WORKORDER_ID, TASK_ID, scanByCode("0123456789012", "LOC-9999"));

        assertThat(response.isMatched()).isFalse();
        assertThat(response.getMatchStatus()).isEqualTo("LOCATION_MISMATCH");
        assertThat(response.getResolvedLocationId()).isNull();
    }

    @Test
    @DisplayName("neither code matching is NO_MATCH")
    void codeScanNoMatch() {
        ExtPickTaskReplica task = task();
        task.setProductCode("0123456789012");
        task.setLocationBarcode("LOC-0037");
        taskExists(task);

        ResolveScanResponse response =
                service.resolveScan(WORKORDER_ID, TASK_ID, scanByCode("9999999999999", "LOC-9999"));

        assertThat(response.getMatchStatus()).isEqualTo("NO_MATCH");
    }

    @Test
    @DisplayName("a scanned product code the task has no replicated code to compare against is "
            + "PRODUCT_CODE_UNAVAILABLE — unverifiable, not necessarily wrong")
    void codeScanProductUnavailable() {
        ExtPickTaskReplica task = task();
        task.setProductCode(null);
        task.setLocationBarcode("LOC-0037");
        taskExists(task);

        ResolveScanResponse response =
                service.resolveScan(WORKORDER_ID, TASK_ID, scanByCode("0123456789012", "LOC-0037"));

        assertThat(response.isMatched()).isFalse();
        assertThat(response.getMatchStatus()).isEqualTo("PRODUCT_CODE_UNAVAILABLE");
    }

    @Test
    @DisplayName("a scanned location code the task has no replicated code to compare against is "
            + "LOCATION_CODE_UNAVAILABLE")
    void codeScanLocationUnavailable() {
        ExtPickTaskReplica task = task();
        task.setProductCode("0123456789012");
        task.setLocationName(null);
        task.setLocationBarcode(null);
        taskExists(task);

        ResolveScanResponse response =
                service.resolveScan(WORKORDER_ID, TASK_ID, scanByCode("0123456789012", "LOC-0037"));

        assertThat(response.isMatched()).isFalse();
        assertThat(response.getMatchStatus()).isEqualTo("LOCATION_CODE_UNAVAILABLE");
    }

    @Test
    @DisplayName("product code unavailable takes priority over a location mismatch")
    void productUnavailableTakesPriorityOverLocationMismatch() {
        ExtPickTaskReplica task = task();
        task.setProductCode(null);
        task.setLocationBarcode("LOC-0037");
        taskExists(task);

        ResolveScanResponse response =
                service.resolveScan(WORKORDER_ID, TASK_ID, scanByCode("0123456789012", "LOC-9999"));

        assertThat(response.getMatchStatus()).isEqualTo("PRODUCT_CODE_UNAVAILABLE");
    }

    // ─── failing closed before publishing ────────────────────────────────────

    @ParameterizedTest(name = "confirm is refused when the replica has no {0}")
    @CsvSource({"pickListId", "skuId", "locationId"})
    @DisplayName("an incomplete replica is a 409, and no command is published")
    void incompleteReplicaIsConflict(String missingField) {
        ExtPickTaskReplica task = task();
        switch (missingField) {
            case "pickListId" -> task.setPickListId(null);
            case "skuId" -> task.setSkuId(null);
            default -> task.setLocationId(null);
        }
        taskExists(task);

        // 409, not 503: the caller cannot fix this by retrying sooner, they have to wait for
        // the replica to catch up. Publishing a command with a null sku would have
        // pos-inventory act on a task it cannot resolve.
        assertThatThrownBy(() -> service.confirmPickLine(WORKORDER_ID, TASK_ID, TASK_ID, confirm(5)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(publisher, never()).requestPickTaskConfirm(any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("an absent publisher is a 503 that names the switch to turn on")
    void absentPublisherIsServiceUnavailable() {
        when(publisherProvider.getIfAvailable()).thenReturn(null);

        // The pick workflow is asynchronous by design (ADR-0044, #901). Running without the
        // Kafka feed is a deployment mistake, and the message says which flag fixes it rather
        // than leaving an operator to guess from a bare 503.
        assertThatThrownBy(() -> service.confirmPickLine(WORKORDER_ID, TASK_ID, TASK_ID, confirm(5)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("workorder.kafka.enabled")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("a broker refusal becomes a retryable 503, not a 500")
    void brokerRefusalIsServiceUnavailable() {
        doThrow(new IllegalStateException("no broker"))
                .when(publisher)
                .requestPickTaskConfirm(any(), any(), any(), any(), anyInt());

        assertThatThrownBy(() -> service.confirmPickLine(WORKORDER_ID, TASK_ID, TASK_ID, confirm(5)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("a pickLineId that disagrees with the pickTaskId is rejected before anything else")
    void mismatchedPickLineIdIsRejected() {
        // The two are aliases of one another in this facade. Letting them diverge would mean
        // confirming a quantity against a different task than the caller named.
        assertThatThrownBy(() -> service.confirmPickLine(WORKORDER_ID, TASK_ID, OTHER_ID, confirm(5)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(pickListReplicaRepository, never()).findByWorkorderIdOrderByPickListIdAsc(any());
    }

    // ─── completion arithmetic ───────────────────────────────────────────────

    @Test
    @DisplayName("completing a task sends the full required quantity, not the remainder")
    void completeSendsRequiredNotRemaining() {
        ExtPickTaskReplica task = task();
        task.setQuantityPicked(4);
        taskExists(task);

        service.completePickTask(WORKORDER_ID, TASK_ID, new CompletePickTaskRequest());

        // #901: pos-inventory sets quantityPicked to the value it receives, so sending the
        // remaining 6 would leave the task recorded as 6 of 10 picked rather than complete.
        verify(publisher).requestPickTaskConfirm(eq(PICK_LIST_ID), eq(TASK_ID), any(), any(), eq(10));
    }

    @ParameterizedTest(name = "picked={0} of 10 required is already complete")
    @CsvSource({"10", "11"})
    @DisplayName("a task already at or past its required quantity publishes nothing")
    void alreadyCompleteTaskPublishesNothing(int picked) {
        ExtPickTaskReplica task = task();
        task.setQuantityPicked(picked);
        task.setStatus("PICKED");
        taskExists(task);

        var response = service.completePickTask(WORKORDER_ID, TASK_ID, new CompletePickTaskRequest());

        // Over-picking is included deliberately: the guard is `remaining <= 0`, and a strict
        // equality check would republish forever for any task that recorded more than required.
        assertThat(response.getStatus()).isEqualTo("PICKED");
        verify(publisher, never()).requestPickTaskConfirm(any(), any(), any(), any(), anyInt());
    }

    // ─── reads ───────────────────────────────────────────────────────────────

    /**
     * #1479: "this workorder has nothing to pick" is a normal state — a labour-only job, or a
     * promoted job whose generated list has not replicated yet — and answering it as an error made
     * every caller treat it as a failure. The seeder swallowed the 404 and skipped picking
     * entirely, which is why simulated jobs never moved stock.
     */
    @Test
    @DisplayName("a workorder with no pick list has an empty task list, not a 404")
    void noPickListMeansNoTasks() {
        when(pickListReplicaRepository.findByWorkorderIdOrderByPickListIdAsc(WORKORDER_ID))
                .thenReturn(List.of());

        assertThat(service.getPickTasksForWorkorder(WORKORDER_ID)).isEmpty();
    }

    /** The header read stays a 404: asked for one resource, it either exists or it does not. */
    @Test
    @DisplayName("but the pick list header read is still a 404")
    void noPickListHeaderIsNotFound() {
        when(pickListReplicaRepository.findByWorkorderIdOrderByPickListIdAsc(WORKORDER_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.getPickListForWorkorder(WORKORDER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("but a workorder with no pick list has an empty picked-items list, not a 404")
    void noPickListMeansNoPickedItems() {
        when(pickListReplicaRepository.findByWorkorderIdOrderByPickListIdAsc(WORKORDER_ID))
                .thenReturn(List.of());

        // Deliberately asymmetric with the read above: "what has been picked for this
        // workorder" has a correct answer when nothing has, and 404 would force every caller
        // to special-case it.
        assertThat(service.getPickedItemsForWorkorder(WORKORDER_ID)).isEmpty();
    }

    @ParameterizedTest(name = "status={0} picked={1} included={2}")
    @CsvSource({
        "PICKED, 0, true",
        "picked, 0, true",
        "OPEN,   3, true",
        "OPEN,   0, false",
    })
    @DisplayName("picked items include anything picked or marked PICKED, case-insensitively")
    void pickedItemsFilter(String status, int picked, boolean included) {
        ExtPickTaskReplica task = task();
        task.setStatus(status);
        task.setQuantityPicked(picked);
        taskExists(task);

        // The status check is case-insensitive because it comes off a replica fed by another
        // service; the quantity check is what catches a partially picked task whose status has
        // not advanced yet.
        assertThat(service.getPickedItemsForWorkorder(WORKORDER_ID)).hasSize(included ? 1 : 0);
    }

    @Test
    @DisplayName("remaining quantity never goes negative when more was consumed than picked")
    void remainingIsClampedAtZero() {
        ExtPickTaskReplica task = task();
        task.setQuantityPicked(3);
        task.setQuantityConsumed(5);
        task.setStatus("PICKED");
        taskExists(task);

        // Consumed exceeding picked is a replica-ordering artefact, not a real state. Reporting
        // -2 remaining would propagate a negative quantity into whatever consumes this view.
        assertThat(service.getPickedItemsForWorkorder(WORKORDER_ID))
                .singleElement()
                .extracting(item -> item.getQtyRemaining())
                .isEqualTo(0);
    }

    // ─── consumption ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("consuming an unknown pick task is a 404 and publishes nothing")
    void consumeUnknownTaskIsNotFound() {
        ConsumePickedItemsRequest request = new ConsumePickedItemsRequest();
        ConsumePickedItemsRequest.ConsumeItem item = new ConsumePickedItemsRequest.ConsumeItem();
        item.setPickTaskId(OTHER_ID);
        item.setQuantityToConsume(2);
        request.setItems(List.of(item));

        // The whole request is rejected rather than the unknown line being skipped: a partial
        // consume that silently drops a line would leave the caller believing it succeeded.
        assertThatThrownBy(() -> service.consumePickedItems(WORKORDER_ID, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(publisher, never()).requestItemsConsume(any(), any(), any());
    }

    @Test
    @DisplayName("a consume request reports PENDING and a zero total, because inventory has not acted yet")
    void consumeReportsPendingNotDone() {
        ConsumePickedItemsRequest request = new ConsumePickedItemsRequest();
        ConsumePickedItemsRequest.ConsumeItem item = new ConsumePickedItemsRequest.ConsumeItem();
        item.setPickTaskId(TASK_ID);
        item.setQuantityToConsume(2);
        request.setItems(List.of(item));

        var response = service.consumePickedItems(WORKORDER_ID, request);

        // totalItemsConsumed is 0 on purpose: the command has been queued, not applied. A
        // non-zero total here would tell the caller stock had moved when it had not.
        assertThat(response.getTotalItemsConsumed()).isZero();
        assertThat(response.getResults())
                .singleElement()
                .satisfies(result -> assertThat(result.getStatus().name()).isEqualTo("PENDING"));
        verify(publisher).requestItemsConsume(eq(WORKORDER_ID), eq(PICK_LIST_ID), any());
    }

    private static ConfirmPickLineRequest confirm(int quantity) {
        ConfirmPickLineRequest request = new ConfirmPickLineRequest();
        request.setQuantityPicked(quantity);
        return request;
    }

    // ─── location scope (#2204) ─────────────────────────────────────────────────

    private static final UUID IN_REACH_NODE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OUT_OF_REACH_NODE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    @DisplayName("getPickListForWorkorder: scoped caller with the workorder's location in reach succeeds")
    void getPickListInReach() {
        workorderAt(IN_REACH_NODE);
        authenticateScopedOn(WorkorderPermissions.INVENTORY_PICK_LIST_VIEW, IN_REACH_NODE);

        assertThat(service.getPickListForWorkorder(WORKORDER_ID)).isNotNull();
    }

    @Test
    @DisplayName("getPickListForWorkorder: scoped caller with the workorder's location out of reach is denied")
    void getPickListOutOfReach() {
        workorderAt(OUT_OF_REACH_NODE);
        authenticateScopedOn(WorkorderPermissions.INVENTORY_PICK_LIST_VIEW, IN_REACH_NODE);

        assertThatThrownBy(() -> service.getPickListForWorkorder(WORKORDER_ID))
                .isInstanceOf(LocationScopeDeniedException.class);
    }

    @Test
    @DisplayName("getPickTasksForWorkorder: out-of-reach caller is denied before reading tasks")
    void getPickTasksOutOfReach() {
        workorderAt(OUT_OF_REACH_NODE);
        authenticateScopedOn(WorkorderPermissions.INVENTORY_PICK_LIST_VIEW, IN_REACH_NODE);

        assertThatThrownBy(() -> service.getPickTasksForWorkorder(WORKORDER_ID))
                .isInstanceOf(LocationScopeDeniedException.class);
    }

    @Test
    @DisplayName("resolveScan: out-of-reach caller is denied before the scan is graded")
    void resolveScanOutOfReach() {
        workorderAt(OUT_OF_REACH_NODE);
        authenticateScopedOn(WorkorderPermissions.INVENTORY_PICK_LIST_EXECUTE, IN_REACH_NODE);

        assertThatThrownBy(() -> service.resolveScan(WORKORDER_ID, TASK_ID, scan(SKU_ID, LOCATION_ID)))
                .isInstanceOf(LocationScopeDeniedException.class);
    }

    @Test
    @DisplayName("confirmPickLine: in-reach caller is allowed to publish the confirm command")
    void confirmPickLineInReach() {
        workorderAt(IN_REACH_NODE);
        authenticateScopedOn(WorkorderPermissions.INVENTORY_PICK_LIST_EXECUTE, IN_REACH_NODE);

        service.confirmPickLine(WORKORDER_ID, TASK_ID, TASK_ID, confirm(5));

        verify(publisher).requestPickTaskConfirm(any(), any(), any(), any(), eq(5));
    }

    @Test
    @DisplayName("confirmPickLine: out-of-reach caller is denied and nothing is published")
    void confirmPickLineOutOfReach() {
        workorderAt(OUT_OF_REACH_NODE);
        authenticateScopedOn(WorkorderPermissions.INVENTORY_PICK_LIST_EXECUTE, IN_REACH_NODE);

        assertThatThrownBy(() -> service.confirmPickLine(WORKORDER_ID, TASK_ID, TASK_ID, confirm(5)))
                .isInstanceOf(LocationScopeDeniedException.class);
        verify(publisher, never()).requestPickTaskConfirm(any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("completePickTask: out-of-reach caller is denied and nothing is published")
    void completePickTaskOutOfReach() {
        workorderAt(OUT_OF_REACH_NODE);
        authenticateScopedOn(WorkorderPermissions.INVENTORY_PICK_LIST_EXECUTE, IN_REACH_NODE);

        assertThatThrownBy(() -> service.completePickTask(WORKORDER_ID, TASK_ID, new CompletePickTaskRequest()))
                .isInstanceOf(LocationScopeDeniedException.class);
        verify(publisher, never()).requestPickTaskConfirm(any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("consumePickedItems: out-of-reach caller is denied and nothing is published")
    void consumePickedItemsOutOfReach() {
        workorderAt(OUT_OF_REACH_NODE);
        authenticateScopedOn(WorkorderPermissions.PARTS_CONSUME, IN_REACH_NODE);
        ConsumePickedItemsRequest request = new ConsumePickedItemsRequest();
        ConsumePickedItemsRequest.ConsumeItem item = new ConsumePickedItemsRequest.ConsumeItem();
        item.setPickTaskId(TASK_ID);
        item.setQuantityToConsume(2);
        request.setItems(List.of(item));

        assertThatThrownBy(() -> service.consumePickedItems(WORKORDER_ID, request))
                .isInstanceOf(LocationScopeDeniedException.class);
        verify(publisher, never()).requestItemsConsume(any(), any(), any());
    }

    @Test
    @DisplayName("a pre-rollout token (no loc_* claims) keeps today's behaviour: unrestricted")
    void preRolloutTokenUnchanged() {
        workorderAt(OUT_OF_REACH_NODE);
        authenticatePreRollout();

        assertThat(service.getPickListForWorkorder(WORKORDER_ID)).isNotNull();
    }

    @Test
    @DisplayName("a caller whose grant is global (not location-scoped) is unrestricted")
    void globalGrantCallerPasses() {
        workorderAt(OUT_OF_REACH_NODE);
        authenticateGlobalOn(IN_REACH_NODE);

        assertThat(service.getPickListForWorkorder(WORKORDER_ID)).isNotNull();
    }

    @Test
    @DisplayName("a workorder whose locationId has not been backfilled skips the check rather than failing closed")
    void nullLocationIdSkipsTheCheck() {
        workorderAt(null);
        authenticateScopedOn(WorkorderPermissions.INVENTORY_PICK_LIST_VIEW, IN_REACH_NODE);

        assertThat(service.getPickListForWorkorder(WORKORDER_ID)).isNotNull();
    }

    @Test
    @DisplayName("a workorder this module does not hold skips the check — the 404 further down answers it")
    void unknownWorkorderSkipsTheCheck() {
        authenticateScopedOn(WorkorderPermissions.INVENTORY_PICK_LIST_VIEW, IN_REACH_NODE);
        // No workorderAt(...) stub: workorderRepository.findById returns empty.

        assertThatThrownBy(() -> service.getPickListForWorkorder(OTHER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
