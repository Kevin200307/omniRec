// SPDX-License-Identifier: Apache-2.0
import { cookies } from "next/headers";
import {
  createOmnirecServer,
  identityFromCookies,
  type OmnirecServer,
  type OmnirecServerConfig,
  type ServerIdentity,
} from "@omnirec/commerce-node";

export { identityFromCookies, type OmnirecServer, type ServerIdentity } from "@omnirec/commerce-node";

/**
 * The visitor's identity in a route handler, server action or server
 * component, read from the cookies the browser SDK sets. Pass a userId when the
 * request is authenticated.
 */
export function currentIdentity(userId?: string | null): ServerIdentity {
  const jar = cookies();
  const identity = identityFromCookies({
    omnirec_anonymous_id: jar.get("omnirec_anonymous_id")?.value,
    omnirec_session_id: jar.get("omnirec_session_id")?.value,
  });
  return { ...identity, userId: userId ?? null };
}

let shared: OmnirecServer | undefined;

/**
 * One sender per server process. Events are flushed on a short interval; in a
 * serverless route, `await omnirecServer().flush()` before returning.
 *
 *     // app/api/orders/route.ts
 *     import { omnirecServer, currentIdentity } from "@omnirec/commerce-next/server";
 *
 *     omnirecServer().track("purchase_completed", { order }, { identity: currentIdentity(user.id) });
 */
export function omnirecServer(config?: OmnirecServerConfig): OmnirecServer {
  if (!shared) {
    const endpoint = config?.endpoint ?? process.env.OMNIREC_ENDPOINT;
    if (!endpoint) {
      throw new Error("omnirecServer(): pass { endpoint } or set OMNIREC_ENDPOINT");
    }
    shared = createOmnirecServer({ ...config, endpoint, apiKey: config?.apiKey ?? process.env.OMNIREC_API_KEY });
  }
  return shared;
}

/** For tests: forget the shared sender. */
export function resetOmnirecServer(): void {
  shared = undefined;
}
