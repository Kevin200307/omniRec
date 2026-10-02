// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@link EventData} token by token so decimals come straight from the
 * JSON text: {@code 2400.00} stays {@code 2400.00}. Going through a generic
 * {@code Map} would let the caller's ObjectMapper turn it into the double
 * {@code 2400.0}, whatever its configuration.
 */
public class EventDataDeserializer extends StdDeserializer<EventData> {

    public EventDataDeserializer() {
        super(EventData.class);
    }

    @Override
    public EventData deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        Object value = read(parser, context);
        if (value == null) return EventData.empty();
        if (!(value instanceof Map<?, ?> map)) {
            return context.reportInputMismatch(EventData.class, "data must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) map;
        return EventData.of(object);
    }

    @Override
    public EventData getNullValue(DeserializationContext context) {
        return EventData.empty();
    }

    private Object read(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == null) token = parser.nextToken();
        switch (token) {
            case START_OBJECT -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (JsonToken t = parser.nextToken(); t != JsonToken.END_OBJECT; t = parser.nextToken()) {
                    String name = parser.currentName();
                    parser.nextToken();
                    out.put(name, read(parser, context));
                }
                return out;
            }
            case START_ARRAY -> {
                List<Object> out = new ArrayList<>();
                for (JsonToken t = parser.nextToken(); t != JsonToken.END_ARRAY; t = parser.nextToken()) {
                    out.add(read(parser, context));
                }
                return out;
            }
            case VALUE_STRING -> {
                return parser.getText();
            }
            case VALUE_NUMBER_INT -> {
                return parser.getNumberValue();
            }
            case VALUE_NUMBER_FLOAT -> {
                return parser.getDecimalValue();
            }
            case VALUE_TRUE -> {
                return Boolean.TRUE;
            }
            case VALUE_FALSE -> {
                return Boolean.FALSE;
            }
            case VALUE_NULL -> {
                return null;
            }
            default -> {
                return context.handleUnexpectedToken(Object.class, parser);
            }
        }
    }
}
