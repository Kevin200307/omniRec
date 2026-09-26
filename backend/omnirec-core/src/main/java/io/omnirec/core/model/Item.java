// SPDX-License-Identifier: Apache-2.0
package io.omnirec.core.model;

import java.util.Map;

public record Item(String id, Map<String, Object> attributes) {
}
