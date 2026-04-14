package com.positivity.shared.id;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.EventType;
import org.hibernate.id.IdentifierGenerator;

/**
 * Hibernate {@link IdentifierGenerator} that delegates UUID creation to
 * {@link UUIDv7Generator}.
 */
public class UUIDv7HibernateGenerator implements IdentifierGenerator {

    @Override
    public Object generate(SharedSessionContractImplementor session, Object object) {
        return UUIDv7Generator.generate();
    }

    @Override
    public Object generate(
            SharedSessionContractImplementor session, Object owner, Object currentValue, EventType eventType) {
        return currentValue != null ? currentValue : UUIDv7Generator.generate();
    }

    @Override
    public boolean allowAssignedIdentifiers() {
        return true;
    }
}
