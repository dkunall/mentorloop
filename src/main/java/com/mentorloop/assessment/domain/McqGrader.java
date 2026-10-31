package com.mentorloop.assessment.domain;

import com.mentorloop.shared.domain.DomainRuleException;

import java.util.HashSet;
import java.util.Set;

/**
 * Single-answer MCQs are right or wrong. Multi-answer MCQs give partial credit, (right picks - wrong picks) /
 * correct options, floored at 0, but only an exact match counts as "correct" for mastery.
 */
public final class McqGrader implements GradingStrategy {

    @Override
    public ItemType type() {
        return ItemType.MCQ;
    }

    @Override
    public Grade grade(AnswerKey key, Answer answer, int optionCount) {
        var k = (AnswerKey.Mcq) key;
        Set<Integer> picked = new HashSet<>(answer.choices());
        if (picked.isEmpty() || picked.size() != answer.choices().size()
                || picked.stream().anyMatch(i -> i < 0 || i >= optionCount)) {
            throw new DomainRuleException("invalid-answer", "Choices must be distinct option indexes");
        }
        if (picked.equals(k.correctOptions())) {
            return Grade.right();
        }
        long right = picked.stream().filter(k.correctOptions()::contains).count();
        long wrong = picked.size() - right;
        double score = k.correctOptions().size() == 1 ? 0.0
                : Math.max(0.0, (right - wrong) / (double) k.correctOptions().size());
        return new Grade(Grade.Outcome.GRADED, false, score);
    }
}
