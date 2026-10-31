package com.mentorloop.assessment.domain;

import com.mentorloop.shared.domain.DomainRuleException;

public final class NumericGrader implements GradingStrategy {

    @Override
    public ItemType type() {
        return ItemType.NUMERIC;
    }

    @Override
    public Grade grade(AnswerKey key, Answer answer, int optionCount) {
        var k = (AnswerKey.Numeric) key;
        if (answer.number() == null || !Double.isFinite(answer.number())) {
            throw new DomainRuleException("invalid-answer", "A numeric item needs a finite number");
        }
        // A hair of slack so 0.1 + 0.2 style float noise does not fail an exact key.
        return Math.abs(answer.number() - k.value()) <= k.tolerance() + 1e-9 ? Grade.right() : Grade.wrong();
    }
}
