package com.positivity.tenancy;

import java.lang.reflect.Method;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;

/**
 * Spring cache key generator that prefixes every key with the bound tenant (ADR-0062 §6), so a
 * cached row can never be served to another tenant. Registered as the {@code keyGenerator} bean, so
 * {@code @Cacheable} without an explicit generator picks it up; the ArchUnit rule forbids naming any
 * other one.
 */
public class TenantKeyGenerator implements KeyGenerator {

    public static final String BEAN_NAME = "tenantKeyGenerator";

    private final TenantResolver tenantResolver;

    public TenantKeyGenerator(TenantResolver tenantResolver) {
        this.tenantResolver = tenantResolver;
    }

    @Override
    public Object generate(Object target, Method method, Object... params) {
        Object[] tenantFirst = new Object[params.length + 1];
        tenantFirst[0] = tenantResolver.require();
        System.arraycopy(params, 0, tenantFirst, 1, params.length);
        return SimpleKeyGenerator.generateKey(tenantFirst);
    }
}
