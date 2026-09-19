package io.omnirec.commerce.validation;

import java.util.List;
import java.util.stream.Collectors;

/** Outcome of validating one event. Immutable; accumulates every problem rather than failing on the first. */
public record ValidationResult(boolean valid, List<ValidationError> errors) {

    private static final ValidationResult VALID = new ValidationResult(true, List.of());

    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ValidationResult ok() {
        return VALID;
    }

    public static ValidationResult invalid(List<ValidationError> errors) {
        return new ValidationResult(false, errors);
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
