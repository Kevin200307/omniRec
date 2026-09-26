// SPDX-License-Identifier: Apache-2.0
package io.omnirec.core.model;

import java.util.Map;

public record Recommendation(String productId, double score, Map<String, Object> metadata) {
}
