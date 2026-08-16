package com.positivity.supplier.internal.adapter.michelins2s;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.domain.model.SupplierWorkorderAuthorization;
import com.positivity.supplier.internal.domain.model.WorkorderAuthorizationRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Michelin S2S workorder authorization codec (CAP-323 #1229).
 *
 * <p>The behaviours worth pinning are the ones where being wrong authorises work: a status word
 * misread as a grant, a 202 whose {@code Location} was dropped, or a granted authorization whose id
 * was not read and so can never be approved when the job completes.
 */
@DisplayName("MichelinS2SWorkorderAuthCodec — reading a fleet decision (#1229)")
class MichelinS2SWorkorderAuthCodecTest {

    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-00000000ab01");

    private final MichelinS2SWorkorderAuthCodec codec =
            new MichelinS2SWorkorderAuthCodec(JsonMapper.builder().build());

    @Nested
    @DisplayName("acceptance that is not yet a decision")
    class Acceptance {

        @Test
        @DisplayName("a 202 becomes PENDING carrying the Location to come back to")
        void acceptedRequestCarriesItsPollLocation() {
            SupplierWorkorderAuthorization decision =
                    codec.decodeDecision(null, 202, "/api/v1/workOrders/WO-778", WORKORDER_ID);

            assertThat(decision.status()).isEqualTo(SupplierWorkorderAuthorization.Status.PENDING);
            assertThat(decision.pollLocation()).isEqualTo("/api/v1/workOrders/WO-778");
        }

        @Test
        @DisplayName("a 202 without a Location is refused, because its decision could never be read")
        void acceptedWithoutLocationIsRefused() {
            // Recording this as "pending" would leave a shop waiting on an answer that has nowhere
            // to arrive from -- the row would be polled forever against a null address.
            assertThatThrownBy(() -> codec.decodeDecision(null, 202, null, WORKORDER_ID))
                    .isInstanceOf(WorkorderAuthDecodeException.class)
                    .hasMessageContaining("never be read");
        }

        @Test
        @DisplayName("a 201 with neither body nor Location is refused rather than read as a grant")
        void createdWithNothingInItIsRefused() {
            assertThatThrownBy(() -> codec.decodeDecision("  ", 201, null, WORKORDER_ID))
                    .isInstanceOf(WorkorderAuthDecodeException.class);
        }
    }

    @Nested
    @DisplayName("reading the vendor's word for it")
    class StatusWords {

        @ParameterizedTest(name = "\"{0}\" is a grant")
        @ValueSource(strings = {"GRANTED", "approved", "Authorised", "AUTHORIZED", "accepted"})
        void grantSynonymsAreRead(String word) {
            SupplierWorkorderAuthorization decision =
                    codec.decodeDecision("{\"id\":\"WO-1\",\"status\":\"" + word + "\"}", 201, null, WORKORDER_ID);
            assertThat(decision.status()).isEqualTo(SupplierWorkorderAuthorization.Status.GRANTED);
        }

        @ParameterizedTest(name = "\"{0}\" is a refusal")
        @ValueSource(strings = {"DENIED", "refused", "Rejected", "declined"})
        void refusalSynonymsAreRead(String word) {
            SupplierWorkorderAuthorization decision =
                    codec.decodeDecision("{\"id\":\"WO-1\",\"status\":\"" + word + "\"}", 201, null, WORKORDER_ID);
            assertThat(decision.status()).isEqualTo(SupplierWorkorderAuthorization.Status.DENIED);
        }

        @Test
        @DisplayName("an unrecognised status raises rather than defaulting either way")
        void unknownStatusRefusesToGuess() {
            // There is no safe default. Defaulting to granted authorises work nobody agreed to pay
            // for; defaulting to denied turns away a paying fleet customer. Both are silent.
            assertThatThrownBy(() ->
                            codec.decodeDecision("{\"id\":\"WO-1\",\"status\":\"ESCALATED\"}", 201, null, WORKORDER_ID))
                    .isInstanceOf(WorkorderAuthDecodeException.class)
                    .hasMessageContaining("ESCALATED");
        }

        @Test
        @DisplayName("no status and no error is refused, not assumed")
        void silenceIsNotADecision() {
            assertThatThrownBy(() -> codec.decodeDecision("{\"id\":\"WO-1\"}", 201, null, WORKORDER_ID))
                    .isInstanceOf(WorkorderAuthDecodeException.class);
        }

        @Test
        @DisplayName("errors with no status word are read as a refusal, with the vendor's own code")
        void errorsWithoutAStatusAreARefusal() {
            SupplierWorkorderAuthorization decision = codec.decodeDecision("""
                    {"id":"WO-1","errors":[{"code":"CONTRACT_EXPIRED","detail":"Contract 44 ended 2026-01-31"}]}
                    """, 201, null, WORKORDER_ID);

            assertThat(decision.status()).isEqualTo(SupplierWorkorderAuthorization.Status.DENIED);
            assertThat(decision.vendorReasonCode()).isEqualTo("CONTRACT_EXPIRED");
            assertThat(decision.vendorReason()).contains("ended 2026-01-31");
        }
    }

    @Nested
    @DisplayName("the authorization id, which completion approval depends on")
    class AuthorizationId {

        @Test
        @DisplayName("read from either field the vendor may have used")
        void idIsReadFromEitherCandidate() {
            assertThat(codec.decodeDecision(
                                    "{\"workOrderId\":\"WO-9\",\"status\":\"granted\"}", 201, null, WORKORDER_ID)
                            .vendorAuthorizationId())
                    .isEqualTo("WO-9");
            assertThat(codec.decodeDecision("{\"id\":\"WO-8\",\"status\":\"granted\"}", 201, null, WORKORDER_ID)
                            .vendorAuthorizationId())
                    .isEqualTo("WO-8");
        }

        @Test
        @DisplayName("this side's workorder id is carried through, never taken from the vendor's echo")
        void workorderIdIsOurs() {
            SupplierWorkorderAuthorization decision = codec.decodeDecision(
                    "{\"id\":\"WO-1\",\"workOrderId\":\"not-a-uuid\",\"status\":\"granted\"}", 201, null, WORKORDER_ID);
            assertThat(decision.workorderId()).isEqualTo(WORKORDER_ID);
        }
    }

    @Nested
    @DisplayName("the authorized ceiling")
    class Ceiling {

        @Test
        @DisplayName("comma decimal separators are normalised, as in every sibling codec")
        void commaDecimalsAreRead() {
            SupplierWorkorderAuthorization decision = codec.decodeDecision(
                    "{\"id\":\"WO-1\",\"status\":\"granted\",\"authorizedAmount\":\"1234,56\",\"currency\":\"EUR\"}",
                    201,
                    null,
                    WORKORDER_ID);
            assertThat(decision.authorizedAmount()).isEqualByComparingTo(new BigDecimal("1234.56"));
        }

        @Test
        @DisplayName("an amount with no currency is dropped rather than guessed at")
        void amountWithoutCurrencyIsDropped() {
            // A number nobody can name a currency for is not a commercial ceiling, and inferring
            // the profile's currency here would invent a term the vendor never stated.
            SupplierWorkorderAuthorization decision = codec.decodeDecision(
                    "{\"id\":\"WO-1\",\"status\":\"granted\",\"authorizedAmount\":\"400.00\"}",
                    201,
                    null,
                    WORKORDER_ID);

            assertThat(decision.authorizedAmount()).isNull();
            assertThat(decision.currency()).isNull();
        }

        @Test
        @DisplayName("an unreadable amount leaves no ceiling, rather than a zero one")
        void unreadableAmountIsNotZero() {
            SupplierWorkorderAuthorization decision = codec.decodeDecision(
                    "{\"id\":\"WO-1\",\"status\":\"granted\",\"authorizedAmount\":\"see contract\",\"currency\":\"EUR\"}",
                    201,
                    null,
                    WORKORDER_ID);
            assertThat(decision.authorizedAmount()).isNull();
        }
    }

    @Nested
    @DisplayName("the requests it builds")
    class Requests {

        @Test
        @DisplayName("an authorization request is not idempotent, and carries partnerId and API-Version")
        void authorizationRequestIsNotRedispatchable() {
            SupplierRequestSpec spec = codec.buildAuthorizationRequest(request(), "SP01234", "1");

            // Not idempotent: a blind re-send can open a second authorization against one contract.
            assertThat(spec.idempotent()).isFalse();
            assertThat(spec.method()).isEqualTo("POST");
            assertThat(spec.pathSuffix()).isEqualTo("/api/v1/workOrders");
            assertThat(spec.queryParams()).containsEntry("partnerId", "SP01234");
            assertThat(spec.headers()).containsEntry("API-Version", "1");
            assertThat(spec.body()).contains("ABC-123");
        }

        @Test
        @DisplayName("an approval is idempotent, because restating a completed fact is safe")
        void approvalIsRedispatchable() {
            SupplierRequestSpec spec = codec.buildApprovalRequest("WO-42", "SP01234", "1");

            assertThat(spec.idempotent()).isTrue();
            assertThat(spec.method()).isEqualTo("PATCH");
            assertThat(spec.pathSuffix()).isEqualTo("/api/v1/workOrders/WO-42/approve");
        }

        @Test
        @DisplayName("a vendor id is encoded, so it cannot alter the path it sits in")
        void identifiersCannotEscapeTheirPathSegment() {
            SupplierRequestSpec spec = codec.buildApprovalRequest("WO/../../admin", "SP01234", "1");

            // The separators are what make traversal possible, so those are what must be encoded.
            // The literal dots survive and are inert inside one segment.
            assertThat(spec.pathSuffix()).isEqualTo("/api/v1/workOrders/WO%2F..%2F..%2Fadmin/approve");
            assertThat(spec.pathSuffix().split("/")).hasSize(6);
        }

        @Test
        @DisplayName("an unconfigured API version is omitted rather than defaulted")
        void blankApiVersionSendsNoHeader() {
            assertThat(codec.buildAuthorizationRequest(request(), "SP01234", "").headers())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the lookups")
    class Lookups {

        @Test
        @DisplayName("a contract's dates are read, and an unparseable one is left absent")
        void contractsAreRead() {
            var contracts = codec.decodeContracts("""
                    [{"id":"C-1","name":"Fleet A","contractor":{"id":"K-9","name":"Haulage Ltd"},
                      "startDate":"2026-01-01","endDate":"not a date"}]
                    """);

            assertThat(contracts).hasSize(1);
            assertThat(contracts.getFirst().vendorContractId()).isEqualTo("C-1");
            assertThat(contracts.getFirst().contractorName()).isEqualTo("Haulage Ltd");
            assertThat(contracts.getFirst().startDate()).isNotNull();
            assertThat(contracts.getFirst().endDate()).isNull();
        }

        @Test
        @DisplayName("a policy body in an unexpected shape yields no policies rather than failing a quote")
        void unreadablePoliciesAreEmpty() {
            // Policies are advisory. Failing a service advisor's screen because an advisory read
            // came back oddly would be the wrong trade.
            assertThat(codec.decodePolicies("{\"unexpected\":true}")).isEmpty();
            assertThat(codec.decodePolicies(null)).isEmpty();
        }

        @Test
        @DisplayName("black and white lists are read out of the EDIWheel reference vocabulary")
        void policyListsAreRead() {
            var policies = codec.decodePolicies("""
                    [{"id":"P-1","name":"Tyres only","useBlacklist":true,"useWhiteList":false,
                      "blackList":[{"references":{"typeCode":"EAN","value":"4001111"}}],
                      "whiteList":[]}]
                    """);

            assertThat(policies).hasSize(1);
            assertThat(policies.getFirst().useBlacklist()).isTrue();
            assertThat(policies.getFirst().blacklisted()).containsExactly("4001111");
        }
    }

    private static WorkorderAuthorizationRequest request() {
        return new WorkorderAuthorizationRequest(
                WORKORDER_ID,
                null,
                "ABC-123",
                null,
                null,
                "C-1",
                "104500",
                List.of(new WorkorderAuthorizationRequest.Line("EAN-1", "Front tyres", 2, "1L")));
    }
}
