// SPDX-License-Identifier: Apache-2.0
package io.omnirec.core.model;

import java.util.List;
import java.util.Map;

public record SearchResult(List<Map<String, Object>> hits, int totalHits) {
}
