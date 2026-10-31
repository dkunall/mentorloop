package com.mentorloop.assessment.domain;

import com.mentorloop.shared.domain.DomainRuleException;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Exact match after normalisation is right. Otherwise, if the key has keywords and the answer contains all of
 * them, a person decides (NEEDS_REVIEW); else it is wrong. No fuzzy matching: a near miss that a typo-tolerant
 * matcher would accept goes to an instructor rather than being guessed at.
 */
public final class ShortAnswerGrader implements GradingStrategy {

    public static final int MAX_CHARS = 500;

    @Override
    public ItemType type() {
        return ItemType.SHORT_ANSWER;
    }

    @Override
    public Grade grade(AnswerKey key, Answer answer, int optionCount) {
        var k = (AnswerKey.ShortAnswer) key;
        if (answer.text() == null || answer.text().isBlank() || answer.text().length() > MAX_CHARS) {
            throw new DomainRuleException("invalid-answer", "A short answer needs 1.." + MAX_CHARS + " characters");
        }
        String given = normalise(answer.text());
        if (k.accepted().stream().map(ShortAnswerGrader::normalise).anyMatch(given::equals)) {
            return Grade.right();
        }
        if (!k.keywords().isEmpty() && k.keywords().stream().map(ShortAnswerGrader::normalise)
                .allMatch(w -> (" " + given + " ").contains(" " + w + " "))) {
            return Grade.needsReview();
        }
        return Grade.wrong();
    }

    public static String normalise(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        n = n.replaceAll("(?<=\\p{L})-(?=\\p{L})", " ");
        n = n.replaceAll("[^\\p{L}\\p{N}.+\\-]+", " ").replaceAll("(?<!\\d)\\.|\\.(?!\\d)", " ");
        return n.trim().replaceAll("\\s+", " ");
    }

    static String formatNumber(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }
}
