package com.mentorloop.adaptive.domain;

/**
 * SuperMemo-2 (Wozniak, 1990). Intervals 1, 6, then the previous interval times the ease factor; a failed recall
 * starts the item over without touching the ease beyond the usual quality penalty.
 */
public final class Sm2Scheduler implements ReviewScheduler {

    @Override
    public ReviewCard next(ReviewCard card, int quality) {
        if (quality < 0 || quality > 5) {
            throw new IllegalArgumentException("quality must be 0..5");
        }
        int q5 = 5 - quality;
        double ease = Math.max(ReviewCard.MIN_EASE, card.ease() + 0.1 - q5 * (0.08 + q5 * 0.02));
        if (quality < 3) {
            int lapses = card.repetitions() > 0 ? card.lapses() + 1 : card.lapses();
            return new ReviewCard(0, 1, ease, lapses);
        }
        int interval = switch (card.repetitions()) {
            case 0 -> 1;
            case 1 -> 6;
            // The interval grows by the ease the card had going into this review.
            default -> (int) Math.round(card.intervalDays() * card.ease());
        };
        return new ReviewCard(card.repetitions() + 1, interval, ease, card.lapses());
    }
}
