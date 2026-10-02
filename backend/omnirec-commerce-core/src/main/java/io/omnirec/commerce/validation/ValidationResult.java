// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.validation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Outcome of validating one event. Immutable; accumulates every problem rather than failing on the first.
 *
 * @param unplanned true when the event's name is in neither the catalog nor the
 *                  tenant's tracking plan and the validator ran in permissive
 *                  mode. Such an event is accepted but flagged.
 */
public record ValidationResult(boolean valid, List<ValidationError> errors, boolean unplanned) {

    private static final ValidationResult VALID = new ValidationResult(true, List.of(), false);
    private static final ValidationResult VALID_UNPLANNED = new ValidationResult(true, List.of(), true);

    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ValidationResult ok() {
        return VALID;
    }

    /** Accepted, but the event is not in the catalog or plan. */
    public static ValidationResult okUnplanned() {
        return VALID_UNPLANNED;
    }

    public static ValidationResult invalid(List<ValidationError> errors) {
        return new ValidationResult(false, errors, false);
    }

    /** Single-line summary, safe to log: field names and rules only, never field values. */
    public String describe() {
        return errors.stream()
                .map(error -> error.field() + ": " + error.message())
                .collect(Collectors.joining("; "));
    }

    public record ValidationError(String field, String message) {
    }
}
