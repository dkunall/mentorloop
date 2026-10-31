package com.mentorloop.adaptive.domain;

import com.mentorloop.shared.domain.DomainRuleException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A course's prerequisite graph. Edges point from prerequisite to dependent. Construction rejects unknown
 * concepts, self loops and cycles, so every graph that exists has a topological order.
 */
public final class ConceptGraph {

    public record Edge(UUID prerequisite, UUID concept) {
    }

    private final List<UUID> concepts;
    private final Map<UUID, Set<UUID>> prerequisites = new HashMap<>();
    private final List<UUID> order;

    public ConceptGraph(List<UUID> concepts, Collection<Edge> edges) {
        this.concepts = List.copyOf(new LinkedHashSet<>(concepts));
        if (this.concepts.size() != concepts.size()) {
            throw new DomainRuleException("duplicate-concept", "A concept is listed twice");
        }
        for (UUID c : this.concepts) {
            prerequisites.put(c, new LinkedHashSet<>());
        }
        for (Edge e : edges) {
            if (!prerequisites.containsKey(e.prerequisite()) || !prerequisites.containsKey(e.concept())) {
                throw new DomainRuleException("unknown-concept", "An edge names a concept that is not in the course");
            }
            if (e.prerequisite().equals(e.concept())) {
                throw new DomainRuleException("prerequisite-cycle", "A concept cannot be its own prerequisite");
            }
            prerequisites.get(e.concept()).add(e.prerequisite());
        }
        this.order = topologicalOrder();
    }

    /** Kahn's algorithm. Ties keep the order the concepts were declared in, so the curriculum order is stable. */
    private List<UUID> topologicalOrder() {
        Map<UUID, Integer> indegree = new LinkedHashMap<>();
        Map<UUID, List<UUID>> dependents = new HashMap<>();
        for (UUID c : concepts) {
            indegree.put(c, prerequisites.get(c).size());
            for (UUID p : prerequisites.get(c)) {
                dependents.computeIfAbsent(p, k -> new ArrayList<>()).add(c);
            }
        }
        Map<UUID, Integer> position = new HashMap<>();
        for (int i = 0; i < concepts.size(); i++) {
            position.put(concepts.get(i), i);
        }
        var ready = new java.util.PriorityQueue<UUID>((a, b) -> Integer.compare(position.get(a), position.get(b)));
        indegree.forEach((c, d) -> {
            if (d == 0) {
                ready.add(c);
            }
        });
        List<UUID> out = new ArrayList<>(concepts.size());
        while (!ready.isEmpty()) {
            UUID c = ready.poll();
            out.add(c);
            for (UUID d : dependents.getOrDefault(c, List.of())) {
                if (indegree.merge(d, -1, Integer::sum) == 0) {
                    ready.add(d);
                }
            }
        }
        if (out.size() != concepts.size()) {
            throw new DomainRuleException("prerequisite-cycle", "The prerequisite graph has a cycle through "
                    + cycleMembers(out).size() + " concepts");
        }
        return List.copyOf(out);
    }

    private List<UUID> cycleMembers(List<UUID> sorted) {
        Set<UUID> done = Set.copyOf(sorted);
        return concepts.stream().filter(c -> !done.contains(c)).toList();
    }

    public List<UUID> order() {
        return order;
    }

    public Set<UUID> prerequisitesOf(UUID concept) {
        Set<UUID> p = prerequisites.get(concept);
        if (p == null) {
            throw new IllegalArgumentException("unknown concept");
        }
        return Set.copyOf(p);
    }

    /** A concept is unlocked when every direct prerequisite is mastered. */
    public boolean unlocked(UUID concept, Set<UUID> mastered) {
        return mastered.containsAll(prerequisitesOf(concept));
    }

    /** All transitive prerequisites, nearest first. */
    public List<UUID> ancestors(UUID concept) {
        List<UUID> out = new ArrayList<>();
        Set<UUID> seen = new java.util.HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>(prerequisitesOf(concept));
        while (!queue.isEmpty()) {
            UUID c = queue.poll();
            if (seen.add(c)) {
                out.add(c);
                queue.addAll(prerequisites.get(c));
            }
        }
        return out;
    }

    public boolean contains(UUID concept) {
        return prerequisites.containsKey(concept);
    }
}
