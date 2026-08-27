package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.ResolvePriceRequestDto;
import com.positivity.catalog.internal.dto.ResolvePriceResponseDto;
import com.positivity.catalog.internal.dto.ResolvePriceResponseDto.ResolvePriceSource;
import com.positivity.catalog.internal.entity.Category;
import com.positivity.catalog.internal.entity.PriceBookEntity;
import com.positivity.catalog.internal.entity.PriceBookRuleConditionType;
import com.positivity.catalog.internal.entity.PriceBookRuleEntity;
import com.positivity.catalog.internal.entity.PriceBookRuleStatus;
import com.positivity.catalog.internal.entity.PriceBookRuleTargetType;
import com.positivity.catalog.internal.entity.PriceBookScope;
import com.positivity.catalog.internal.entity.PriceBookStatus;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductMsrpEntity;
import com.positivity.catalog.internal.entity.Subcategory;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.PriceBookRepository;
import com.positivity.catalog.internal.repository.PriceBookRuleRepository;
import com.positivity.catalog.internal.repository.ProductMsrpRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

/**
 * Branch-coverage tests for {@link PriceBookServiceImpl#resolvePrice}, the read
 * path that decides what a product costs.
 *
 * <p>
 * Almost every branch in this method is a silent one. Price resolution walks two
 * independent precedence chains — first which price book applies, then which
 * rule inside it wins — and then falls back to MSRP and finally to "no price".
 * Every step of both chains produces a well-formed answer, so getting one wrong
 * does not fail, it quotes a different number. Only the {@code source} and
 * {@code fallbackReason} fields on the response distinguish "this is the
 * contract price" from "this is list price because no contract matched", and
 * nothing downstream re-derives them.
 *
 * <p>
 * These tests therefore assert the chosen rule and the source, not merely that a
 * price came back.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PriceBookServiceImpl.resolvePrice — precedence and fallback")
class PriceBookResolvePriceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID SUBCATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID BOOK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a5");
    private static final UUID TIER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a6");

    @Mock
    private PriceBookRepository priceBookRepository;

    @Mock
    private PriceBookRuleRepository priceBookRuleRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductMsrpRepository productMsrpRepository;

    private PriceBookServiceImpl service;
    private ProductEntity product;

    @BeforeEach
    void setUp() {
        service = new PriceBookServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                priceBookRepository,
                priceBookRuleRepository,
                productRepository,
                productMsrpRepository,
                new ObjectMapper());

        product = new ProductEntity();
        product.setId(PRODUCT_ID);
        Category category = new Category();
        category.setId(CATEGORY_ID);
        product.setCategory(category);
        Subcategory subcategory = new Subcategory();
        subcategory.setId(SUBCATEGORY_ID);
        // #1536: a subcategory always has a parent category; keep the fixture faithful to the schema.
        subcategory.setCategory(category);
        product.setSubcategory(subcategory);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productMsrpRepository.findActive(any(), any())).thenReturn(Optional.empty());
        defaultBookExists();
    }

    private void defaultBookExists() {
        PriceBookEntity book = new PriceBookEntity();
        book.setPriceBookId(BOOK_ID);
        when(priceBookRepository.findByScopeAndIsDefaultTrueAndStatus(
                        PriceBookScope.COMPANY_DEFAULT, PriceBookStatus.ACTIVE))
                .thenReturn(Optional.of(book));
    }

    private static PriceBookRuleEntity rule(
            String id, PriceBookRuleTargetType targetType, UUID targetId, Integer priority) {
        PriceBookRuleEntity entity = new PriceBookRuleEntity();
        entity.setRuleId(UUID.fromString("00000000-0000-0000-0000-0000000000" + id));
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setPriority(priority);
        entity.setConditionType(PriceBookRuleConditionType.NONE);
        entity.setStatus(PriceBookRuleStatus.ACTIVE);
        entity.setPricingLogic("{\"amounts\":{\"USD\":19.99}}");
        return entity;
    }

    private void rulesReturned(PriceBookRuleEntity... rules) {
        when(priceBookRuleRepository.findActiveRulesForBooks(any(), eq(PriceBookRuleStatus.ACTIVE), any()))
                .thenReturn(List.of(rules));
    }

    private static ResolvePriceRequestDto request() {
        ResolvePriceRequestDto request = new ResolvePriceRequestDto();
        request.setProductId(PRODUCT_ID);
        return request;
    }

    // ─── target precedence: SKU beats CATEGORY beats GLOBAL ──────────────────

    @Test
    @DisplayName("a SKU rule beats a category rule and a global rule, whatever their priorities")
    void skuRuleWinsOverCategoryAndGlobal() {
        // Priorities are deliberately inverted against the desired outcome: precedence by
        // target specificity is scored first and priority only breaks ties within a score, so a
        // high-priority global rule must still lose to a low-priority SKU rule.
        PriceBookRuleEntity global = rule("b1", PriceBookRuleTargetType.GLOBAL, null, 100);
        PriceBookRuleEntity category = rule("b2", PriceBookRuleTargetType.CATEGORY, CATEGORY_ID, 50);
        PriceBookRuleEntity sku = rule("b3", PriceBookRuleTargetType.SKU, PRODUCT_ID, 1);
        rulesReturned(global, category, sku);

        ResolvePriceResponseDto response = service.resolvePrice(request());

        assertThat(response.getSource()).isEqualTo(ResolvePriceSource.PRICE_BOOK_RULE);
        assertThat(response.getSourceRuleId()).isEqualTo(sku.getRuleId());
    }

    @Test
    @DisplayName("a category rule beats a global rule when no SKU rule applies")
    void categoryRuleWinsOverGlobal() {
        PriceBookRuleEntity global = rule("c1", PriceBookRuleTargetType.GLOBAL, null, 100);
        PriceBookRuleEntity category = rule("c2", PriceBookRuleTargetType.CATEGORY, CATEGORY_ID, 1);
        rulesReturned(global, category);

        assertThat(service.resolvePrice(request()).getSourceRuleId()).isEqualTo(category.getRuleId());
    }

    @Test
    @DisplayName("a category rule matches on the product's subcategory as well as its category")
    void categoryRuleMatchesSubcategory() {
        // A product sits at two taxonomy nodes and a rule may target either. Missing the
        // subcategory branch would silently drop half the category rules in the catalog.
        PriceBookRuleEntity onSubcategory = rule("c3", PriceBookRuleTargetType.CATEGORY, SUBCATEGORY_ID, 1);
        rulesReturned(onSubcategory);

        assertThat(service.resolvePrice(request()).getSourceRuleId()).isEqualTo(onSubcategory.getRuleId());
    }

    @Test
    @DisplayName("a category rule pointing at some other node does not apply")
    void unrelatedCategoryRuleDoesNotApply() {
        rulesReturned(rule("c4", PriceBookRuleTargetType.CATEGORY, UUID.randomUUID(), 100));

        // Falls all the way through to UNAVAILABLE: no rule applied and no MSRP is configured.
        assertThat(service.resolvePrice(request()).getSource()).isEqualTo(ResolvePriceSource.UNAVAILABLE);
    }

    @Test
    @DisplayName("a product with no taxonomy at all cannot match a category rule")
    void productWithoutTaxonomyMatchesNoCategoryRule() {
        product.setCategory(null);
        product.setSubcategory(null);
        rulesReturned(rule("c5", PriceBookRuleTargetType.CATEGORY, CATEGORY_ID, 100));

        assertThat(service.resolvePrice(request()).getSource()).isEqualTo(ResolvePriceSource.UNAVAILABLE);
    }

    // ─── tie-breaks within the same precedence score ─────────────────────────

    @Test
    @DisplayName("between two rules of equal specificity, the higher priority wins")
    void higherPriorityWinsWithinTheSameScore() {
        PriceBookRuleEntity low = rule("d1", PriceBookRuleTargetType.GLOBAL, null, 1);
        PriceBookRuleEntity high = rule("d2", PriceBookRuleTargetType.GLOBAL, null, 9);
        rulesReturned(low, high);

        assertThat(service.resolvePrice(request()).getSourceRuleId()).isEqualTo(high.getRuleId());
    }

    @Test
    @DisplayName("a rule with no priority ranks below one that has any priority")
    void nullPriorityRanksLast() {
        // nullsLast on a reversed comparator is easy to get backwards, and getting it backwards
        // means an unprioritised rule silently outranks every deliberately prioritised one.
        PriceBookRuleEntity noPriority = rule("d3", PriceBookRuleTargetType.GLOBAL, null, null);
        PriceBookRuleEntity lowest = rule("d4", PriceBookRuleTargetType.GLOBAL, null, 0);
        rulesReturned(noPriority, lowest);

        assertThat(service.resolvePrice(request()).getSourceRuleId()).isEqualTo(lowest.getRuleId());
    }

    @Test
    @DisplayName("equal priority is broken by the most recently effective rule")
    void newerEffectiveStartWins() {
        PriceBookRuleEntity older = rule("d5", PriceBookRuleTargetType.GLOBAL, null, 5);
        older.setEffectiveStartAt(NOW.minusSeconds(86_400));
        PriceBookRuleEntity newer = rule("d6", PriceBookRuleTargetType.GLOBAL, null, 5);
        newer.setEffectiveStartAt(NOW);
        rulesReturned(older, newer);

        assertThat(service.resolvePrice(request()).getSourceRuleId()).isEqualTo(newer.getRuleId());
    }

    @Test
    @DisplayName("a fully tied pair is broken deterministically by rule id")
    void fullTieIsBrokenByRuleId() {
        PriceBookRuleEntity first = rule("d7", PriceBookRuleTargetType.GLOBAL, null, 5);
        PriceBookRuleEntity second = rule("d8", PriceBookRuleTargetType.GLOBAL, null, 5);
        // Supplied in reverse so the result cannot come from input order. A tie must resolve the
        // same way on every node and every request, or two servers quote different prices for
        // the same basket.
        rulesReturned(second, first);

        assertThat(service.resolvePrice(request()).getSourceRuleId()).isEqualTo(first.getRuleId());
    }

    // ─── conditions ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a LOCATION-conditioned rule applies only to a request carrying that location")
    void locationConditionMustMatch() {
        PriceBookRuleEntity conditioned = rule("e1", PriceBookRuleTargetType.GLOBAL, null, 5);
        conditioned.setConditionType(PriceBookRuleConditionType.LOCATION);
        conditioned.setConditionValue(LOCATION_ID.toString());
        rulesReturned(conditioned);

        // No location on the request: the rule must not apply.
        assertThat(service.resolvePrice(request()).getSource()).isEqualTo(ResolvePriceSource.UNAVAILABLE);

        ResolvePriceRequestDto withLocation = request();
        withLocation.setLocationId(LOCATION_ID);
        assertThat(service.resolvePrice(withLocation).getSourceRuleId()).isEqualTo(conditioned.getRuleId());
    }

    @Test
    @DisplayName("a LOCATION-conditioned rule does not apply to a different location")
    void locationConditionRejectsOtherLocations() {
        PriceBookRuleEntity conditioned = rule("e2", PriceBookRuleTargetType.GLOBAL, null, 5);
        conditioned.setConditionType(PriceBookRuleConditionType.LOCATION);
        conditioned.setConditionValue(UUID.randomUUID().toString());
        rulesReturned(conditioned);

        ResolvePriceRequestDto withLocation = request();
        withLocation.setLocationId(LOCATION_ID);
        assertThat(service.resolvePrice(withLocation).getSource()).isEqualTo(ResolvePriceSource.UNAVAILABLE);
    }

    @ParameterizedTest(name = "customerTier={0} against a rule requiring GOLD")
    @CsvSource({"GOLD,true", "SILVER,false", ",false"})
    @DisplayName("a CUSTOMER_TIER-conditioned rule applies only to its own tier")
    void customerTierConditionMustMatch(String tier, boolean applies) {
        PriceBookRuleEntity conditioned = rule("e3", PriceBookRuleTargetType.GLOBAL, null, 5);
        conditioned.setConditionType(PriceBookRuleConditionType.CUSTOMER_TIER);
        conditioned.setConditionValue("GOLD");
        rulesReturned(conditioned);

        ResolvePriceRequestDto withTier = request();
        withTier.setCustomerTier(tier);

        ResolvePriceResponseDto response = service.resolvePrice(withTier);
        assertThat(response.getSource())
                .isEqualTo(applies ? ResolvePriceSource.PRICE_BOOK_RULE : ResolvePriceSource.UNAVAILABLE);
    }

    // ─── which book, and the fallback chain ──────────────────────────────────

    @Test
    @DisplayName("a location book takes precedence over the company default")
    void locationBookBeatsCompanyDefault() {
        PriceBookEntity locationBook = new PriceBookEntity();
        UUID locationBookId = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
        locationBook.setPriceBookId(locationBookId);
        when(priceBookRepository.findByScopeAndScopeIdAndStatus(
                        PriceBookScope.LOCATION, LOCATION_ID, PriceBookStatus.ACTIVE))
                .thenReturn(Optional.of(locationBook));
        rulesReturned(rule("f1", PriceBookRuleTargetType.GLOBAL, null, 1));

        ResolvePriceRequestDto withLocation = request();
        withLocation.setLocationId(LOCATION_ID);
        service.resolvePrice(withLocation);

        // The company default must not even be consulted once a location book exists — a
        // location's negotiated prices are not a fallback, they are the answer.
        verify(priceBookRuleRepository).findActiveRulesForBooks(eq(List.of(locationBookId)), any(), any());
        verify(priceBookRepository, never())
                .findByScopeAndIsDefaultTrueAndStatus(PriceBookScope.COMPANY_DEFAULT, PriceBookStatus.ACTIVE);
    }

    @Test
    @DisplayName("a location with no book of its own falls through to the customer tier book")
    void missingLocationBookFallsThroughToTierBook() {
        when(priceBookRepository.findByScopeAndScopeIdAndStatus(
                        PriceBookScope.LOCATION, LOCATION_ID, PriceBookStatus.ACTIVE))
                .thenReturn(Optional.empty());
        PriceBookEntity tierBook = new PriceBookEntity();
        UUID tierBookId = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
        tierBook.setPriceBookId(tierBookId);
        when(priceBookRepository.findByScopeAndScopeIdAndStatus(
                        PriceBookScope.CUSTOMER_TIER, TIER_ID, PriceBookStatus.ACTIVE))
                .thenReturn(Optional.of(tierBook));
        rulesReturned(rule("f2", PriceBookRuleTargetType.GLOBAL, null, 1));

        ResolvePriceRequestDto withBoth = request();
        withBoth.setLocationId(LOCATION_ID);
        withBoth.setCustomerTierId(TIER_ID);
        service.resolvePrice(withBoth);

        verify(priceBookRuleRepository).findActiveRulesForBooks(eq(List.of(tierBookId)), any(), any());
    }

    @Test
    @DisplayName("with no book at all, resolution falls to MSRP and says so")
    void noBookFallsBackToMsrp() {
        when(priceBookRepository.findByScopeAndIsDefaultTrueAndStatus(any(), any()))
                .thenReturn(Optional.empty());
        ProductMsrpEntity msrp = new ProductMsrpEntity();
        msrp.setAmount(new BigDecimal("24.50"));
        msrp.setCurrency("USD");
        when(productMsrpRepository.findActive(eq(PRODUCT_ID), any())).thenReturn(Optional.of(msrp));

        ResolvePriceResponseDto response = service.resolvePrice(request());

        // MSRP is list price, not a negotiated price. The fallbackReason is the only signal a
        // consumer has that no contract applied, so it is part of the contract.
        assertThat(response.getSource()).isEqualTo(ResolvePriceSource.MSRP);
        assertThat(response.getFallbackReason()).isEqualTo("MSRP_FALLBACK");
        assertThat(response.getResolvedAmount()).isEqualTo("24.5000");
        assertThat(response.getSourceRuleId()).isNull();
    }

    @Test
    @DisplayName("with neither a rule nor an MSRP, the answer is UNAVAILABLE rather than zero")
    void noPriceAtAllIsUnavailable() {
        rulesReturned();

        ResolvePriceResponseDto response = service.resolvePrice(request());

        // A missing price must never surface as an amount. Returning zero here would sell the
        // product for nothing; returning null with a reason forces the caller to decide.
        assertThat(response.getSource()).isEqualTo(ResolvePriceSource.UNAVAILABLE);
        assertThat(response.getFallbackReason()).isEqualTo("PRICE_BASE_DATA_MISSING");
        assertThat(response.getResolvedAmount()).isNull();
    }

    @Test
    @DisplayName("resolution is rejected outright without a product id")
    void productIdIsRequired() {
        assertThatThrownBy(() -> service.resolvePrice(new ResolvePriceRequestDto()))
                .isInstanceOf(CatalogValidationException.class)
                .hasMessageContaining("productId");
    }

    // ─── currency selection on the winning rule ──────────────────────────────

    @Test
    @DisplayName("an explicitly requested currency is used when the rule configures it")
    void requestedCurrencyIsHonoured() {
        PriceBookRuleEntity multi = rule("1a", PriceBookRuleTargetType.GLOBAL, null, 1);
        multi.setPricingLogic("{\"amounts\":{\"USD\":19.99,\"EUR\":17.50}}");
        rulesReturned(multi);

        ResolvePriceRequestDto inEuros = request();
        inEuros.setCurrency("eur");

        ResolvePriceResponseDto response = service.resolvePrice(inEuros);

        // Case-insensitive, but the amount must be the euro one — falling back to USD here
        // would quote a euro price at a dollar number.
        assertThat(response.getCurrency()).isEqualTo("EUR");
        assertThat(response.getResolvedAmount()).isEqualTo("17.5000");
    }

    @Test
    @DisplayName("a requested currency the rule does not configure is an error, not a substitution")
    void unconfiguredRequestedCurrencyIsRejected() {
        PriceBookRuleEntity usdOnly = rule("1b", PriceBookRuleTargetType.GLOBAL, null, 1);
        usdOnly.setPricingLogic("{\"amounts\":{\"USD\":19.99}}");
        rulesReturned(usdOnly);

        ResolvePriceRequestDto inYen = request();
        inYen.setCurrency("JPY");

        // Quietly answering in USD would hand the caller a number they would treat as yen.
        assertThatThrownBy(() -> service.resolvePrice(inYen)).isInstanceOf(CatalogValidationException.class);
    }

    @Test
    @DisplayName("with one configured currency and no request, that currency is used")
    void singleConfiguredCurrencyIsUnambiguous() {
        rulesReturned(rule("1c", PriceBookRuleTargetType.GLOBAL, null, 1));

        ResolvePriceResponseDto response = service.resolvePrice(request());

        assertThat(response.getCurrency()).isEqualTo("USD");
        assertThat(response.getResolvedAmount()).isEqualTo("19.9900");
    }

    @Test
    @DisplayName("with several configured currencies and no request, resolution refuses to guess")
    void ambiguousCurrencyIsRejected() {
        PriceBookRuleEntity multi = rule("1d", PriceBookRuleTargetType.GLOBAL, null, 1);
        multi.setPricingLogic("{\"amounts\":{\"USD\":19.99,\"EUR\":17.50}}");
        rulesReturned(multi);

        // Picking either one arbitrarily would be a coin flip over which currency the customer
        // is billed in.
        assertThatThrownBy(() -> service.resolvePrice(request())).isInstanceOf(CatalogValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"amounts\":{}}",
                "{\"amounts\":[]}",
                "{\"nothing\":1}",
                "not json at all",
                "{\"amounts\":{\"USD\":0}}",
                "{\"amounts\":{\"USD\":-1}}",
                "{\"amounts\":{\"USD\":null}}"
            })
    @DisplayName("a malformed or non-positive pricing payload is rejected rather than resolved")
    void malformedPricingLogicIsRejected(String pricingLogic) {
        PriceBookRuleEntity broken = rule("2a", PriceBookRuleTargetType.GLOBAL, null, 1);
        broken.setPricingLogic(pricingLogic);
        rulesReturned(broken);

        // Zero and negative are in this list deliberately: a rule that resolves to nothing or
        // to a credit is a data error, and letting it through prices the product at that value.
        assertThatThrownBy(() -> service.resolvePrice(request())).isInstanceOf(CatalogValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"US", "USDD", "1"})
    @DisplayName("a currency that is not a three-letter code is rejected")
    void nonIsoCurrencyIsRejected(String currency) {
        rulesReturned(rule("2b", PriceBookRuleTargetType.GLOBAL, null, 1));

        ResolvePriceRequestDto bad = request();
        bad.setCurrency(currency);

        assertThatThrownBy(() -> service.resolvePrice(bad)).isInstanceOf(CatalogValidationException.class);
    }

    @Test
    @DisplayName("a blank requested currency is treated as no request rather than as an error")
    void blankCurrencyIsTreatedAsAbsent() {
        rulesReturned(rule("2c", PriceBookRuleTargetType.GLOBAL, null, 1));

        ResolvePriceRequestDto blank = request();
        blank.setCurrency("   ");

        // A UI that always sends its currency field, empty or not, must not be rejected.
        assertThat(service.resolvePrice(blank).getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("an explicit asOf date is used instead of today")
    void explicitAsOfIsUsed() {
        LocalDate asOf = LocalDate.of(2026, 1, 1);
        rulesReturned();
        ResolvePriceRequestDto dated = request();
        dated.setAsOf(asOf);

        service.resolvePrice(dated);

        // Backdated quoting is the whole point of asOf — resolving against today instead would
        // reprice a historical order at current prices.
        verify(productMsrpRepository).findActive(PRODUCT_ID, asOf);
    }
}
