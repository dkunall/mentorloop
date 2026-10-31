package com.mentorloop.course.domain;

import com.mentorloop.shared.domain.DomainRuleException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConceptGraphTest {

    private final UUID arrays = id(1);
    private final UUID lists = id(2);
    private final UUID stacks = id(3);
    private final UUID queues = id(4);
    private final UUID trees = id(5);

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    private static ConceptGraph.Edge e(UUID from, UUID to) {
        return new ConceptGraph.Edge(from, to);
    }

    @Test
    void ordersPrerequisitesFirstAndKeepsDeclaredOrderForTies() {
        // Declared out of order on purpose: trees first.
        var g = new ConceptGraph(List.of(trees, queues, stacks, lists, arrays),
                List.of(e(arrays, lists), e(lists, stacks), e(lists, queues), e(stacks, trees), e(queues, trees)));
        assertThat(g.order()).containsExactly(arrays, lists, queues, stacks, trees);
    }

    @Test
    void unlocksOnlyWhenEveryDirectPrerequisiteIsMastered() {
        var g = new ConceptGraph(List.of(arrays, lists, stacks, queues, trees),
                List.of(e(arrays, lists), e(lists, stacks), e(lists, queues), e(stacks, trees), e(queues, trees)));
        assertThat(g.unlocked(arrays, Set.of())).isTrue();
        assertThat(g.unlocked(trees, Set.of(arrays, lists, stacks))).isFalse();
        assertThat(g.unlocked(trees, Set.of(stacks, queues))).isTrue();
        assertThat(g.ancestors(trees)).containsExactlyInAnyOrder(stacks, queues, lists, arrays);
    }

    @Test
    void rejectsCyclesSelfLoopsAndUnknownConcepts() {
        assertThatThrownBy(() -> new ConceptGraph(List.of(arrays, lists, stacks),
                List.of(e(arrays, lists), e(lists, stacks), e(stacks, arrays))))
                .isInstanceOf(DomainRuleException.class).hasMessageContaining("cycle through 3");
        assertThatThrownBy(() -> new ConceptGraph(List.of(arrays), List.of(e(arrays, arrays))))
                .isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> new ConceptGraph(List.of(arrays), List.of(e(arrays, lists))))
                .isInstanceOf(DomainRuleException.class).hasMessageContaining("not in the course");
    }
}
