// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.identity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link IdentityLinkStore}. Correct and thread-safe, but it dies
 * with the JVM and isn't shared between instances — fine for tests and
 * single-node development, not for a deployment. Use the Redis store there.
 */
public class InMemoryIdentityLinkStore implements IdentityLinkStore {

    /** tenant -> anonymousId -> links, newest last. */
    private final Map<String, Map<String, List<IdentityLink>>> byAnonymousId = new ConcurrentHashMap<>();
    /** tenant -> userId -> anonymous ids, insertion-ordered. */
    private final Map<String, Map<String, Set<String>>> byUserId = new ConcurrentHashMap<>();

    @Override
    public void link(IdentityLink link) {
        String tenant = tenantKey(link.tenantId());

        byAnonymousId
                .computeIfAbsent(tenant, t -> new ConcurrentHashMap<>())
                .compute(link.anonymousId(), (anonymousId, existing) -> {
                    List<IdentityLink> links = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
                    // Idempotent: re-linking the same pair refreshes nothing and adds nothing.
                    boolean alreadyLinked = links.stream().anyMatch(l -> l.userId().equals(link.userId()));
                    if (!alreadyLinked) {
                        links.add(link);
                    }
                    return List.copyOf(links);
                });

        byUserId
                .computeIfAbsent(tenant, t -> new ConcurrentHashMap<>())
                .compute(link.userId(), (userId, existing) -> {
                    Set<String> anonymousIds = existing == null ? new LinkedHashSet<>() : new LinkedHashSet<>(existing);
                    anonymousIds.add(link.anonymousId());
                    return Set.copyOf(anonymousIds);
                });
    }

    @Override
    public Optional<String> resolveUserId(String tenantId, String anonymousId) {
        if (anonymousId == null) return Optional.empty();
        return Optional.ofNullable(byAnonymousId.get(tenantKey(tenantId)))
                .map(links -> links.get(anonymousId))
                .filter(links -> !links.isEmpty())
                .map(links -> links.stream()
                        .max(Comparator.comparing(IdentityLink::linkedAt))
                        .orElseThrow()
                        .userId());
    }

    @Override
    public List<String> anonymousIdsFor(String tenantId, String userId) {
        if (userId == null) return List.of();
        return Optional.ofNullable(byUserId.get(tenantKey(tenantId)))
                .map(users -> users.get(userId))
                .map(List::copyOf)
                .orElseGet(List::of);
    }

    @Override
    public void forget(String tenantId, String userId, java.util.Collection<String> anonymousIds) {
        String tenant = tenantKey(tenantId);
        Map<String, List<IdentityLink>> anonymous = byAnonymousId.getOrDefault(tenant, Map.of());
        Map<String, Set<String>> users = byUserId.getOrDefault(tenant, Map.of());
        Set<String> devices = new LinkedHashSet<>(anonymousIds);
        devices.addAll(users.getOrDefault(userId, Set.of()));
        users.remove(userId);
        for (String anonymousId : devices) {
            List<IdentityLink> links = anonymous.remove(anonymousId);
            if (links == null) continue;
            // The device's other users lose it too: it belongs to the erased customer now.
            for (IdentityLink link : links) {
                users.computeIfPresent(link.userId(), (u, ids) -> {
                    Set<String> rest = new LinkedHashSet<>(ids);
                    rest.remove(anonymousId);
                    return rest.isEmpty() ? null : Set.copyOf(rest);
                });
            }
        }
    }

    /** ConcurrentHashMap forbids null keys, and a single-tenant deployment legitimately has no tenant id. */
    private static String tenantKey(String tenantId) {
        return tenantId == null ? "__default__" : tenantId;
    }
}
