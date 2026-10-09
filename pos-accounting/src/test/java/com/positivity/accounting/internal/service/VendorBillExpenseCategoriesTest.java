package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.readmodel.BankAccountCurrencies;
import com.positivity.accounting.internal.bankrec.readmodel.BankAccountLabels;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.VendorBillExpenseCategoryListResponse;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AP reads #2670 AC 1 and AC 2: the expense-category read lists the active {@code VENDOR_BILL} {@code EXPENSE_<CODE>}
 * keys with the account each resolves to on the business date, by label then key, and the vendor AP settings write
 * accepts exactly the keys it lists ({@link VendorBillExpenseKeys}, one test for both).
 */
@DisplayName("Vendor-bill expense categories: the read and the AP settings write share one key test (#2670)")
class VendorBillExpenseCategoriesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T15:00:00Z"), ZoneOffset.UTC);
    private static final UUID CATEGORY = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f0001");

    private final PostingCategoryRepository categories = mock(PostingCategoryRepository.class);
    private final MappingKeyRepository keys = mock(MappingKeyRepository.class);
    private final GLMappingRepository mappings = mock(GLMappingRepository.class);
    private final GLAccountRepository glAccounts = mock(GLAccountRepository.class);
    private final List<MappingKey> stored = new ArrayList<>();
    private final List<GLAccount> accountRows = new ArrayList<>();

    private VendorBillExpenseKeys expenseKeys;
    private ApChoicesServiceImpl read;

    @BeforeEach
    void wire() {
        expenseKeys = new VendorBillExpenseKeys(categories, keys);
        ApPayFromAccounts rule = new ApPayFromAccounts(
                CLOCK,
                TestZoneResolvers.utc(CLOCK),
                glAccounts,
                mock(BankAccountCurrencies.class),
                new LedgerCurrency("USD"));
        read = new ApChoicesServiceImpl(expenseKeys, mappings, glAccounts, rule, mock(BankAccountLabels.class));
        PostingCategory category = new PostingCategory();
        category.setPostingCategoryId(CATEGORY);
        category.setCategoryName("VENDOR_BILL");
        lenient().when(categories.findByCategoryName("VENDOR_BILL")).thenReturn(Optional.of(category));
        lenient().when(keys.findByPostingCategory_PostingCategoryId(CATEGORY)).thenReturn(stored);
        lenient()
                .when(keys.findByPostingCategory_PostingCategoryIdAndKeyName(eq(CATEGORY), any()))
                .thenAnswer(inv -> stored.stream()
                        .filter(k -> k.getKeyName().equals(inv.getArgument(1)))
                        .findFirst());
        lenient()
                .when(mappings.findEffectiveMapping(any(UUID.class), any(UUID.class), any()))
                .thenReturn(Optional.empty());
        lenient().when(glAccounts.findAllById(anyCollection())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            return accountRows.stream()
                    .filter(a -> ids.contains(a.getGlAccountId()))
                    .toList();
        });

        // The seeded tenant's nine keys (R__seed_reference_accounting.sql), as AC 1 sets them up.
        key("EXPENSE_SHOP_SUPPLIES", "Shop supplies", true, "6340", "Shop Supplies & Consumables");
        key("EXPENSE_SMALL_TOOLS", "Small tools", false, "6350", "Small Tools");
        key("EXPENSE_OFFICE_SUPPLIES", "Office supplies", true, "6310", "Office Supplies");
        key("EXPENSE_BUILDING_REPAIRS", "Building repairs", true, "6220", "Repairs - Building");
        key("EXPENSE_EQUIPMENT_REPAIRS", "Equipment repairs", true, "6230", "Repairs - Equipment");
        key("EXPENSE_POSTAGE_SHIPPING", "Postage and shipping", true, "6320", "Postage & Shipping");
        key("EXPENSE_CLEANING_JANITORIAL", "Cleaning and janitorial", true, "6240", "Cleaning & Janitorial");
        key("EXPENSE_STAFF_MEALS", "Staff meals", true, null, null);
        key("EXPENSE_VEHICLE_FUEL", "Vehicle fuel", true, "6410", "Vehicle Fuel");
        // Keys of the category that are not expense keys.
        key("ACCOUNTS_PAYABLE", "Credit side of an approved bill", true, "2000", "Accounts Payable");
        key("EXPENSE_", "A prefix without a code", true, "6999", "Nothing");
    }

    private void key(String name, String label, boolean active, String accountNumber, String accountName) {
        MappingKey key = new MappingKey();
        key.setMappingKeyId(UUID.nameUUIDFromBytes(name.getBytes()));
        key.setKeyName(name);
        key.setDescription(label);
        key.setIsActive(active);
        stored.add(key);
        if (accountNumber != null) {
            GLAccount account = new GLAccount();
            account.setGlAccountId(UUID.nameUUIDFromBytes(accountNumber.getBytes()));
            account.setAccountCode(accountNumber);
            account.setAccountName(accountName);
            accountRows.add(account);
            GLMapping mapping = new GLMapping();
            mapping.setGlAccountId(account.getGlAccountId());
            lenient()
                    .when(mappings.findEffectiveMapping(
                            CATEGORY,
                            key.getMappingKeyId(),
                            LocalDate.of(2026, 10, 9).atStartOfDay()))
                    .thenReturn(Optional.of(mapping));
        }
    }

    @Test
    @DisplayName("AC 1: eight categories by label; Shop supplies posts to 6340; Staff meals has no account; the"
            + " deactivated Small tools is absent")
    void seededTenant() {
        VendorBillExpenseCategoryListResponse listed = read.expenseCategories();

        assertThat(listed.asOf()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(listed.categories())
                .extracting(VendorBillExpenseCategoryListResponse.Category::label)
                .containsExactly(
                        "Building repairs",
                        "Cleaning and janitorial",
                        "Equipment repairs",
                        "Office supplies",
                        "Postage and shipping",
                        "Shop supplies",
                        "Staff meals",
                        "Vehicle fuel");
        assertThat(listed.categories())
                .filteredOn(c -> c.mappingKey().equals("EXPENSE_SHOP_SUPPLIES"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.accountNumber()).isEqualTo("6340");
                    assertThat(c.accountName()).isEqualTo("Shop Supplies & Consumables");
                });
        assertThat(listed.categories())
                .filteredOn(c -> c.mappingKey().equals("EXPENSE_STAFF_MEALS"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.accountNumber()).isNull();
                    assertThat(c.accountName()).isNull();
                });
        assertThat(listed.categories())
                .extracting(VendorBillExpenseCategoryListResponse.Category::mappingKey)
                .doesNotContain("EXPENSE_SMALL_TOOLS", "ACCOUNTS_PAYABLE", "EXPENSE_");
    }

    @Test
    @DisplayName("order: label case-insensitively, a null label as its key, then key")
    void order() {
        stored.clear();
        key("EXPENSE_ZETA", null, true, null, null);
        key("EXPENSE_B", "apples", true, null, null);
        key("EXPENSE_A", "Apples", true, null, null);
        key("EXPENSE_C", "Bananas", true, null, null);

        assertThat(read.expenseCategories().categories())
                .extracting(VendorBillExpenseCategoryListResponse.Category::mappingKey)
                .containsExactly("EXPENSE_A", "EXPENSE_B", "EXPENSE_C", "EXPENSE_ZETA");
    }

    @Test
    @DisplayName("no expense key, or no VENDOR_BILL category, is an empty list")
    void empty() {
        stored.clear();
        assertThat(read.expenseCategories().categories()).isEmpty();
        when(categories.findByCategoryName("VENDOR_BILL")).thenReturn(Optional.empty());
        assertThat(read.expenseCategories().categories()).isEmpty();
    }

    @Test
    @DisplayName("AC 2: the AP settings write accepts exactly the keys the read lists")
    void readAndWriteAgree() {
        List<String> listed = read.expenseCategories().categories().stream()
                .map(VendorBillExpenseCategoryListResponse.Category::mappingKey)
                .toList();

        for (MappingKey key : stored) {
            assertThat(expenseKeys.isActive(key.getKeyName()))
                    .as("key %s", key.getKeyName())
                    .isEqualTo(listed.contains(key.getKeyName()));
        }
        assertThat(expenseKeys.isActive("EXPENSE_NOT_A_KEY")).isFalse();
    }

    @Test
    @DisplayName("the one key test: the EXPENSE_ prefix, a code after it, and active")
    void keyTest() {
        assertThat(VendorBillExpenseKeys.isListed("EXPENSE_SHOP_SUPPLIES", true))
                .isTrue();
        assertThat(VendorBillExpenseKeys.isListed("EXPENSE_SHOP_SUPPLIES", false))
                .isFalse();
        assertThat(VendorBillExpenseKeys.isListed("EXPENSE_SHOP_SUPPLIES", null))
                .isFalse();
        assertThat(VendorBillExpenseKeys.isListed("EXPENSE_", true)).isFalse();
        assertThat(VendorBillExpenseKeys.isListed("FREIGHT_IN", true)).isFalse();
        assertThat(VendorBillExpenseKeys.isListed(null, true)).isFalse();
    }
}
