package com.mentorloop.adaptive.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Picks the one thing a learner should do next. In priority order:
 * <ol>
 *   <li>a due review, oldest first (forgetting is the most expensive thing to postpone);</li>
 *   <li>a concept already being learned, lowest p_known first (finish what was started);</li>
 *   <li>the next unlocked concept in curriculum order: its lesson if not read yet, else practice.</li>
 * </ol>
 * Practice items are chosen so the learner should get about {@link #TARGET_SUCCESS} of them right: the item whose
 * difficulty is closest to {@code logit(p_known) - logit(target)} on the Rasch scale. That mapping from p_known to
 * ability is a heuristic, not a fitted model.
 */
public final class NextActivityRecommender {

    public static final double TARGET_SUCCESS = 0.7;

    public enum Kind { REVIEW, LESSON, PRACTICE, DONE, NOTHING_AVAILABLE }

    public record ConceptView(UUID id, ConceptState state, double pKnown, int attempts, boolean lessonRead,
                              Optional<UUID> lessonId) {
    }

    public record ItemView(UUID id, UUID conceptId, double difficulty, boolean answeredCorrectly) {
    }

    public record DueReview(UUID itemId, UUID conceptId, Instant dueAt) {
    }

    public record Activity(Kind kind, UUID conceptId, UUID itemId, UUID lessonId, String reason) {
    }

    /**
     * @param curriculum concepts in topological order
     * @param items      live items by concept
     */
    public Activity next(List<ConceptView> curriculum, List<DueReview> due, Map<UUID, List<ItemView>> items) {
        Optional<DueReview> review = due.stream().min(Comparator.comparing(DueReview::dueAt)
                .thenComparing(DueReview::itemId));
        if (review.isPresent()) {
            DueReview r = review.get();
            return new Activity(Kind.REVIEW, r.conceptId(), r.itemId(), null,
                    "review due since " + r.dueAt() + "; reviews come first so they are not forgotten");
        }
        if (!curriculum.isEmpty() && curriculum.stream().allMatch(c -> c.state().countsAsMastered())) {
            return new Activity(Kind.DONE, null, null, null, "every concept in the course is mastered");
        }
        Optional<ConceptView> learning = curriculum.stream()
                .filter(c -> c.state() == ConceptState.LEARNING && hasItems(items, c))
                .min(Comparator.comparingDouble(ConceptView::pKnown));
        if (learning.isPresent()) {
            return practice(learning.get(), items, "still learning it (p_known "
                    + String.format(java.util.Locale.ROOT, "%.2f", learning.get().pKnown()) + ")");
        }
        for (ConceptView c : curriculum) {
            if (c.state() != ConceptState.UNLOCKED) {
                continue;
            }
            if (!c.lessonRead() && c.lessonId().isPresent()) {
                return new Activity(Kind.LESSON, c.id(), null, c.lessonId().get(),
                        "next unlocked concept; start with its lesson");
            }
            if (hasItems(items, c)) {
                return practice(c, items, "next unlocked concept");
            }
        }
        return new Activity(Kind.NOTHING_AVAILABLE, null, null, null,
                "no unlocked concept has live items yet");
    }

    private static boolean hasItems(Map<UUID, List<ItemView>> items, ConceptView c) {
        return !items.getOrDefault(c.id(), List.of()).isEmpty();
    }

    private static Activity practice(ConceptView c, Map<UUID, List<ItemView>> items, String reason) {
        double target = logit(c.pKnown()) - logit(TARGET_SUCCESS);
        List<ItemView> pool = items.get(c.id());
        List<ItemView> fresh = pool.stream().filter(i -> !i.answeredCorrectly()).toList();
        List<ItemView> candidates = fresh.isEmpty() ? pool : fresh;
        ItemView pick = candidates.stream()
                .min(Comparator.comparingDouble((ItemView i) -> Math.abs(i.difficulty() - target))
                        .thenComparing(ItemView::id))
                .orElseThrow();
        return new Activity(Kind.PRACTICE, c.id(), pick.id(), null, reason);
    }

    static double logit(double p) {
        double q = Math.max(0.01, Math.min(0.99, p));
        return Math.log(q / (1 - q));
    }
}
