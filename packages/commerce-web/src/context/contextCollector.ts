// SPDX-License-Identifier: Apache-2.0
import type { DeviceType, EventContext, Platform } from "../events/types";
import { sanitizeUrl } from "./sanitizeUrl";

/**
 * Collects the ambient context every event carries, so a merchant never has to
 * pass `url` or `device` by hand. Everything here is derived from standard
 * browser APIs — no fingerprinting, and no attempt to identify the visitor.
 *
 * `ip` and `country` are deliberately absent: the Event API derives those from
 * the request itself and discards anything a client claims, because a value the
 * browser supplies is trivially spoofed.
 */
export function collectContext(): EventContext {
  const context: EventContext = { platform: detectPlatform() };

  if (typeof location !== "undefined") {
    context.url = sanitizeUrl(location.href);
    context.path = location.pathname;
  }
  if (typeof document !== "undefined" && document.referrer) {
    context.referrer = sanitizeUrl(document.referrer);
  }
  if (typeof navigator !== "undefined") {
    if (navigator.userAgent) {
      context.userAgent = navigator.userAgent;
      context.device = detectDevice(navigator.userAgent);
    }
    if (navigator.language) {
      context.locale = navigator.language;
    }
  }
  if (typeof screen !== "undefined") {
    if (screen.width) context.screenWidth = screen.width;
    if (screen.height) context.screenHeight = screen.height;
  }
  const timezone = detectTimezone();
  if (timezone) context.timezone = timezone;

  return context;
}

function detectPlatform(): Platform {
  if (typeof window === "undefined") return "server";
  return "web";
}

export function detectDevice(userAgent: string): DeviceType {
  const ua = userAgent.toLowerCase();
  if (ua.includes("ipad") || ua.includes("tablet")) return "tablet";
  if (ua.includes("mobi") || ua.includes("iphone") || ua.includes("android")) return "mobile";
  if (ua.length === 0) return "unknown";
  return "desktop";
}

function detectTimezone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone;
  } catch {
    return undefined;
  }
}
