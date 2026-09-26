// SPDX-License-Identifier: Apache-2.0
import { compact, type EventEmitter } from "../core/emitter";

/**
 * Sessions are started automatically by the client (see IdentityManager's
 * policy); these methods exist for merchants who need to force a boundary —
 * for example a kiosk handing the terminal to the next shopper.
 */
export class SessionTracker {
  constructor(private readonly emitter: EventEmitter) {}

  started(properties: Record<string, unknown> = {}): void {
    this.emitter.emit("session_started", {}, compact(properties));
  }

  ended(properties: Record<string, unknown> = {}): void {
    this.emitter.emit("session_ended", {}, compact(properties));
  }
}
