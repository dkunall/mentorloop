package com.mentorloop.adaptive.domain;

import com.mentorloop.adaptive.domain.NextActivityRecommender.ConceptView;
import com.mentorloop.adaptive.domain.NextActivityRecommender.DueReview;
import com.mentorloop.adaptive.domain.NextActivityRecommender.ItemView;
import com.mentorloop.adaptive.domain.NextActivityRecommender.Kind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NextActivityRecommenderTest {

    private final NextActivityRecommender rec = new NextActivityRecommender();
    private final UUID a = new UUID(1, 1);
    private final UUID b = new UUID(1, 2);
    private final UUID c = new UUID(1, 3);
    private final UUID lessonB = new UUID(2, 2);

    private ConceptView view(UUID id, ConceptState s, double p, int attempts, boolean read) {
        return new ConceptView(id, s, p, attempts, read, Optional.of(new UUID(2, id.getLeastSignificantBits())));
    }

    private static ItemView item(int n, UUID concept, double difficulty, boolean right) {
        return new ItemView(new UUID(3, n), concept, difficulty, right);
    }

    @Test
    void dueReviewsComeFirstOldestFirst() {
        var due = List.of(new DueReview(new UUID(3, 9), a, Instant.parse("2026-10-30T10:00:00Z")),
                new DueReview(new UUID(3, 8), a, Instant.parse("2026-10-29T10:00:00Z")));
        var act = rec.next(List.of(view(a, ConceptState.REVIEW_DUE, 0.97, 5, true),
                view(b, ConceptState.LEARNING, 0.3, 1, true)), due, Map.of(b, List.of(item(1, b, 0, false))));
        assertThat(act.kind()).isEqualTo(Kind.REVIEW);
        assertThat(act.itemId()).isEqualTo(new UUID(3, 8));
    }

    @Test
    void finishesTheWeakestStartedConceptBeforeOpeningANewOne() {
        var act = rec.next(List.of(view(a, ConceptState.LEARNING, 0.8, 3, true),
                        view(b, ConceptState.LEARNING, 0.4, 2, true), view(c, ConceptState.UNLOCKED, 0.2, 0, false)),
                List.of(), Map.of(a, List.of(item(1, a, 0, false)), b, List.of(item(2, b, 0, false))));
        assertThat(act.kind()).isEqualTo(Kind.PRACTICE);
        assertThat(act.conceptId()).isEqualTo(b);
    }

    @Test
    void opensTheNextUnlockedConceptWithItsLessonThenPractice() {
        var curriculum = List.of(view(a, ConceptState.MASTERED, 0.97, 6, true),
                new ConceptView(b, ConceptState.UNLOCKED, 0.2, 0, false, Optional.of(lessonB)),
                view(c, ConceptState.LOCKED, 0.2, 0, false));
        var items = Map.of(b, List.of(item(1, b, 0, false)));
        assertThat(rec.next(curriculum, List.of(), items).lessonId()).isEqualTo(lessonB);

        var read = List.of(curriculum.get(0), new ConceptView(b, ConceptState.UNLOCKED, 0.2, 0, true,
                Optional.of(lessonB)), curriculum.get(2));
        var act = rec.next(read, List.of(), items);
        assertThat(act.kind()).isEqualTo(Kind.PRACTICE);
        assertThat(act.itemId()).isEqualTo(new UUID(3, 1));
    }

    @Test
    void picksTheUnsolvedItemNearestTheTargetDifficulty() {
        // p_known 0.5 -> ability 0; target difficulty = 0 - logit(0.7) = -0.847.
        var items = Map.of(a, List.of(item(1, a, 1.5, false), item(2, a, -0.9, true), item(3, a, -0.5, false),
                item(4, a, -2.5, false)));
        var act = rec.next(List.of(view(a, ConceptState.LEARNING, 0.5, 2, true)), List.of(), items);
        assertThat(act.itemId()).isEqualTo(new UUID(3, 3));
    }

    @Test
    void saysDoneWhenEverythingIsMasteredAndSkipsConceptsWithoutItems() {
        assertThat(rec.next(List.of(view(a, ConceptState.MASTERED, 0.97, 6, true)), List.of(), Map.of()).kind())
                .isEqualTo(Kind.DONE);
        assertThat(rec.next(List.of(view(a, ConceptState.UNLOCKED, 0.2, 0, true)), List.of(), Map.of()).kind())
                .isEqualTo(Kind.NOTHING_AVAILABLE);
    }
}
