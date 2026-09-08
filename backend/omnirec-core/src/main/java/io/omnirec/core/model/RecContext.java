package io.omnirec.core.model;

public record RecContext(int numResults) {
    public static RecContext defaultContext() {
        return new RecContext(10);
    }
}
