package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantKeyGeneratorTest {

    private static final UUID A = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID B = UUID.fromString("01900000-0000-7000-8000-000000000002");

    private final TenantKeyGenerator generator = new TenantKeyGenerator(new TenantResolver(new TenancyProperties()));

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void sameArgumentsProduceDifferentKeysForDifferentTenants() throws Exception {
        Method method = Object.class.getMethod("toString");
        Object keyA = TenantContext.callAs(A, () -> generator.generate(this, method, "sku-1"));
        Object keyB = TenantContext.callAs(B, () -> generator.generate(this, method, "sku-1"));
        Object keyAAgain = TenantContext.callAs(A, () -> generator.generate(this, method, "sku-1"));

        assertThat(keyA).isNotEqualTo(keyB).isEqualTo(keyAAgain);
    }

    @Test
    void refusesToCacheWithoutATenant() throws Exception {
        Method method = Object.class.getMethod("toString");
        assertThatThrownBy(() -> generator.generate(this, method, "sku-1"))
                .isInstanceOf(TenantContextMissingException.class);
    }
}
