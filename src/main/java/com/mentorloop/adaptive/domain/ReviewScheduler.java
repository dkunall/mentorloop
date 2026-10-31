package com.mentorloop.adaptive.domain;

/** Strategy for when an item should come back. */
public interface ReviewScheduler {

    /**
     * @param quality 0..5 recall quality: 5 perfect, 4 right, 3 right but with effort, 2 and below wrong
     */
    ReviewCard next(ReviewCard card, int quality);

    /** Maps a graded attempt to SM-2 quality. Wrong answers are 1; a slow right answer is 3; otherwise 4. */
    static int quality(boolean correct, long latencyMillis, long slowMillis) {
        if (!correct) {
            return 1;
        }
        return latencyMillis > slowMillis ? 3 : 4;
    }
}
