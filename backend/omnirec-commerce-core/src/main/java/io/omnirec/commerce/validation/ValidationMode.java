// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.validation;

/** How the validator treats an event name it does not know. */
public enum ValidationMode {
    /** Reject events that are not in the catalog or the tenant's tracking plan. */
    STRICT,
    /** Accept unknown events, flagged as unplanned. Known events are still checked in full. */
    PERMISSIVE
}
