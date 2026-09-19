import type { CommerceData, EventType } from "../events/types";

export interface EmitOptions {
  /**
   * Use this eventId instead of a random one. Set for events with a natural
   * business key (a purchase's orderId), so the same fact reported twice —
   * by a retry, or by both the browser and the backend SDK — deduplicates
   * instead of double-counting.
   */
  eventId?: string;
}

/**
 * The single seam every tracker writes through. Trackers know event names and
 * argument shapes; they know nothing about identity, batching, validation, or
 * transport. That's what keeps them trivially unit-testable — a fake emitter is
 * one object literal.
 *
 * Returns the eventId of the queued event, or undefined if it was refused
 * (invalid, or the client was destroyed).
 */
export interface EventEmitter {
  emit(
    eventType: EventType,
    commerce?: CommerceData,
    properties?: Record<string, unknown>,
    options?: EmitOptions
  ): string | undefined;
}

/** Drops undefined entries so they don't serialise as explicit nulls on the wire. */
export function compact<T extends Record<string, unknown>>(input: T): Partial<T> {
  const output: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(input)) {
    if (value !== undefined) output[key] = value;
  }
  return output as Partial<T>;
}

/**
 * Deterministic eventId for a business fact. Deliberately a readable string
 * rather than a hash, so the backend SDK (Java) produces byte-identical ids
 * with no shared hashing code: `evt:purchase_completed:order_123`.
 */
export function businessEventId(eventType: EventType, businessKey: string): string {
  return `evt:${eventType}:${businessKey}`;
}
