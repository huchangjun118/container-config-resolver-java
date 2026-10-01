package com.github.huchangjun118.containerconfigresolverjava.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;

import java.io.IOException;

/**
 * 让 Jackson 能区分"键缺失"与"显式提供（含 null/空集合）"：
 * 键缺失 -> Presence.absent()；键存在（含 null）-> Presence.present(...)。
 */
public class PresenceDeserializer extends JsonDeserializer<Presence<?>> implements ContextualDeserializer {

    private final JavaType valueType;

    public PresenceDeserializer() {
        this(null);
    }

    private PresenceDeserializer(JavaType valueType) {
        this.valueType = valueType;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        JavaType type = property == null ? null : property.getType().containedType(0);
        return new PresenceDeserializer(type);
    }

    @Override
    public Presence<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        Object value;
        if (valueType != null) {
            value = ctxt.readValue(p, valueType);
        } else {
            value = p.readValueAs(Object.class);
        }
        return Presence.present(value);
    }

    @Override
    public Presence<?> getNullValue(DeserializationContext ctxt) {
        // JSON 中显式写 null 也属于"显式提交"
        return Presence.present(null);
    }
}
