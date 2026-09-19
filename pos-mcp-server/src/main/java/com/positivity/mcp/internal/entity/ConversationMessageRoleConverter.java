package com.positivity.mcp.internal.entity;

import com.positivity.mcp.internal.enums.ConversationMessageRole;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores {@link ConversationMessageRole} as its lower-case wire value ({@code mcp_message.role}). */
@Converter
public class ConversationMessageRoleConverter implements AttributeConverter<ConversationMessageRole, String> {

    @Override
    public String convertToDatabaseColumn(ConversationMessageRole attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public ConversationMessageRole convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ConversationMessageRole.fromWireValue(dbData);
    }
}
