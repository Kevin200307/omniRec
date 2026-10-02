// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.catalog;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One field of an event as the catalog defines it, with the event's own
 * constraints merged onto the block's.
 *
 * @param type       catalog type: string, integer, number, boolean, timestamp, money, enum, array, object
 * @param required   whether the field must be present and non-empty
 * @param minimum    inclusive lower bound for numeric types, or {@code null}
 * @param maximum    inclusive upper bound for numeric types, or {@code null}
 * @param minItems   minimum array length, or {@code null}
 * @param maxLength  maximum string length, or {@code null}
 * @param pattern    regular expression a string must match, or {@code null}
 * @param vocabulary vocabulary name for {@code enum} fields, or {@code null}
 * @param items      element definition for arrays, or {@code null}
 * @param fields     nested fields for objects; empty otherwise
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
public record FieldDefinition(
        String type,
        boolean required,
        BigDecimal minimum,
        BigDecimal maximum,
        Integer minItems,
        Integer maxLength,
        String pattern,
        String vocabulary,
        FieldDefinition items,
        Map<String, FieldDefinition> fields
) {
    public FieldDefinition {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    public boolean isArray() {
        return "array".equals(type);
    }
}
