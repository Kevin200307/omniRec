// SPDX-License-Identifier: Apache-2.0
package io.omnirec.web.dto;

import java.util.List;

public record EventBatchRequest(String tenantId, List<EventDto> events) {
}
