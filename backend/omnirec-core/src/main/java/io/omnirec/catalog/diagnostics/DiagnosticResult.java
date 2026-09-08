package io.omnirec.catalog.diagnostics;

/** docsUrl is null on a healthy result — there's nothing to point the developer at. */
public record DiagnosticResult(boolean healthy, String message, String docsUrl) {

    public static DiagnosticResult healthy(String message) {
        return new DiagnosticResult(true, message, null);
    }

    public static DiagnosticResult unhealthy(String message, String docsUrl) {
        return new DiagnosticResult(false, message, docsUrl);
    }
}
