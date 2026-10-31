package com.mentorloop.tutor.domain;

import com.mentorloop.shared.text.Text;

import java.util.List;

/**
 * While a learner has a graded quiz open, the tutor may only hint. This is enforced on the server in three places,
 * and does not rely on the model following instructions:
 * <ol>
 *   <li>retrieval drops every item-specific chunk (worked examples), see {@link #retrievalExcludesItemChunks};</li>
 *   <li>the model is asked for a hint, not an answer;</li>
 *   <li>{@link #screen} rejects any reply that contains a revealing string (the right option text, accepted
 *       answer or numeric value) of an item in the open quiz, and the learner gets a generic hint instead.</li>
 * </ol>
 * Answer keys are never in the index in the first place.
 */
public final class QuizModeGuard {

    public record Verdict(boolean allowed, String text) {
    }

    private QuizModeGuard() {
    }

    public static boolean retrievalExcludesItemChunks(boolean quizOpen) {
        return quizOpen;
    }

    public static Verdict screen(String reply, List<String> revealing, String fallbackHint) {
        for (String secret : revealing) {
            if (secret != null && !secret.isBlank() && Text.containsPhrase(reply, secret)) {
                return new Verdict(false, fallbackHint);
            }
        }
        return new Verdict(true, reply);
    }
}
