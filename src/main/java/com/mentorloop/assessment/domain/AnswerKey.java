package com.mentorloop.assessment.domain;

import com.mentorloop.shared.domain.DomainRuleException;

import java.util.List;
import java.util.Set;

/**
 * What grading compares against. Stored apart from the item and read only by grading; the tutor's index never
 * sees it (an ArchUnit rule keeps the tutor module from depending on this package).
 */
public sealed interface AnswerKey {

    ItemType type();

    /** Strings whose appearance in a tutor reply would give the answer away during a quiz. */
    List<String> revealingStrings(List<String> options);

    record Mcq(Set<Integer> correctOptions) implements AnswerKey {
        public Mcq {
            if (correctOptions == null || correctOptions.isEmpty()) {
                throw new DomainRuleException("invalid-key", "An MCQ needs at least one correct option");
            }
            correctOptions = Set.copyOf(correctOptions);
        }

        @Override
        public ItemType type() {
            return ItemType.MCQ;
        }

        @Override
        public List<String> revealingStrings(List<String> options) {
            return correctOptions.stream().sorted().map(options::get).toList();
        }
    }

    record Numeric(double value, double tolerance) implements AnswerKey {
        public Numeric {
            if (!Double.isFinite(value) || !(tolerance >= 0)) {
                throw new DomainRuleException("invalid-key", "A numeric key needs a finite value and tolerance >= 0");
            }
        }

        @Override
        public ItemType type() {
            return ItemType.NUMERIC;
        }

        @Override
        public List<String> revealingStrings(List<String> options) {
            return List.of(ShortAnswerGrader.formatNumber(value));
        }
    }

    /**
     * @param accepted answers that are right after normalisation (case, punctuation, whitespace)
     * @param keywords if set, an answer that is not in {@code accepted} but contains every keyword is sent to an
     *                 instructor instead of being marked wrong
     */
    record ShortAnswer(List<String> accepted, List<String> keywords) implements AnswerKey {
        public ShortAnswer {
            if (accepted == null || accepted.isEmpty() || accepted.stream().anyMatch(a -> a.isBlank())) {
                throw new DomainRuleException("invalid-key", "A short answer needs at least one accepted answer");
            }
            accepted = List.copyOf(accepted);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }

        @Override
        public ItemType type() {
            return ItemType.SHORT_ANSWER;
        }

        @Override
        public List<String> revealingStrings(List<String> options) {
            return accepted;
        }
    }
}
