package com.mentorloop.assessment.domain;

import com.mentorloop.shared.domain.DomainRuleException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class GradersTest {

    private final Graders graders = Graders.standard();

    private Grade mcq(Set<Integer> key, Integer... picks) {
        return graders.grade(ItemType.MCQ, new AnswerKey.Mcq(key), new Answer(List.of(picks), null, null), 4);
    }

    @Test
    void singleAnswerMcqIsAllOrNothing() {
        assertThat(mcq(Set.of(2), 2)).isEqualTo(Grade.right());
        assertThat(mcq(Set.of(2), 1)).isEqualTo(Grade.wrong());
    }

    @Test
    void multiAnswerMcqGivesPartialCreditButOnlyAnExactMatchIsCorrect() {
        Grade half = mcq(Set.of(0, 2), 0);
        assertThat(half.correct()).isFalse();
        assertThat(half.score()).isCloseTo(0.5, within(1e-12));
        assertThat(mcq(Set.of(0, 2), 0, 1).score()).isZero();
        assertThat(mcq(Set.of(0, 2), 2, 0).correct()).isTrue();
    }

    @Test
    void mcqRejectsOutOfRangeOrRepeatedChoices() {
        assertThatThrownBy(() -> mcq(Set.of(1), 4)).isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> mcq(Set.of(1), 1, 1)).isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> mcq(Set.of(1))).isInstanceOf(DomainRuleException.class);
    }

    @Test
    void numericUsesTheTolerance() {
        var key = new AnswerKey.Numeric(3.0, 0.05);
        assertThat(graders.grade(ItemType.NUMERIC, key, new Answer(null, 3.04, null), 0).correct()).isTrue();
        assertThat(graders.grade(ItemType.NUMERIC, key, new Answer(null, 3.06, null), 0).correct()).isFalse();
        var exact = new AnswerKey.Numeric(0.3, 0);
        assertThat(graders.grade(ItemType.NUMERIC, exact, new Answer(null, 0.1 + 0.2, null), 0).correct()).isTrue();
    }

    @Test
    void shortAnswerMatchesAfterNormalisingAndSendsKeywordMatchesToAPerson() {
        var key = new AnswerKey.ShortAnswer(List.of("last in, first out", "LIFO"), List.of("last", "first"));
        assertThat(grade(key, "  Last-in First-out. ")).isEqualTo(Grade.right());
        assertThat(grade(key, "lifo")).isEqualTo(Grade.right());
        assertThat(grade(key, "the last one in is the first one out")).isEqualTo(Grade.needsReview());
        assertThat(grade(key, "first in first out")).isEqualTo(Grade.wrong());
        assertThat(grade(key, "a queue")).isEqualTo(Grade.wrong());
        assertThat(grade(new AnswerKey.ShortAnswer(List.of("O(n log n)"), List.of()), "o(n log n)"))
                .isEqualTo(Grade.right());
    }

    @Test
    void normalisingKeepsDecimalPointsButDropsSentencePunctuation() {
        assertThat(ShortAnswerGrader.normalise("It is 3.5.")).isEqualTo("it is 3.5");
        assertThat(ShortAnswerGrader.normalise("Héllo,   WORLD!")).isEqualTo("héllo world");
    }

    @Test
    void keysListWhatWouldGiveTheAnswerAway() {
        assertThat(new AnswerKey.Mcq(Set.of(1)).revealingStrings(List.of("queue", "stack", "heap")))
                .containsExactly("stack");
        assertThat(new AnswerKey.Numeric(7, 0).revealingStrings(List.of())).containsExactly("7");
        assertThatThrownBy(() -> new AnswerKey.ShortAnswer(List.of(" "), null))
                .isInstanceOf(DomainRuleException.class);
    }

    private Grade grade(AnswerKey key, String text) {
        return graders.grade(ItemType.SHORT_ANSWER, key, new Answer(null, null, text), 0);
    }
}
