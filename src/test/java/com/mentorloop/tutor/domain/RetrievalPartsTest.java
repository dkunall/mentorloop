package com.mentorloop.tutor.domain;

import com.mentorloop.shared.text.Text;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RetrievalPartsTest {

    @Test
    void chunksNeverCrossHeadingsAndKeepTheHeadingAsLabel() {
        String md = """
                # Stacks
                A **stack** is a last in, first out collection.

                Push adds on top. Pop removes from the top.

                # Queues
                A **queue** is first in, first out.
                """;
        var pieces = new Chunker(40, 80).split(md);
        assertThat(pieces).extracting(Chunker.Piece::heading).containsExactly("Stacks", "Queues");
        assertThat(pieces.get(0).text()).contains("Push adds on top");
        assertThat(pieces.get(1).text()).doesNotContain("stack");
    }

    @Test
    void longParagraphsAreSplitAtSentences() {
        String sentence = "Every node in the tree has at most two children and a value. ";
        var pieces = new Chunker(20, 30).split(sentence.repeat(8));
        assertThat(pieces).hasSizeGreaterThan(2);
        assertThat(pieces).allSatisfy(p -> assertThat(Text.words(p.text()).size()).isLessThanOrEqualTo(30));
    }

    @Test
    void embeddingsAreDeterministicNormalisedAndCloserForRelatedText() {
        var e = new HashingEmbedder();
        float[] stack = e.embed("A stack pops the most recently pushed element");
        assertThat(e.embed("A stack pops the most recently pushed element")).containsExactly(stack);
        double norm = 0;
        for (float f : stack) {
            norm += f * f;
        }
        assertThat(norm).isCloseTo(1.0, within(1e-5));
        double related = HashingEmbedder.cosine(stack, e.embed("what do stacks pop?"));
        double unrelated = HashingEmbedder.cosine(stack, e.embed("binary heaps keep the minimum at the root"));
        assertThat(related).isGreaterThan(unrelated + 0.1);
        assertThat(e.embed("the of is")).containsOnly(0f);
    }

    @Test
    void rankFusionRewardsAgreementBetweenLists() {
        var fused = RankFusion.fuse(List.of(List.of("a", "b", "c"), List.of("c", "b", "d")), 3);
        // c: 1/61 + 1/63 edges out b: 2/62; a single first place (a) loses to two good placings.
        assertThat(fused).containsExactly("c", "b", "a");
    }

    @Test
    void citationsOutsideTheContextAreStrippedAndCoverageCounted() {
        var c = Citations.check("Stacks are LIFO [1]. Queues are FIFO [7]. Heaps are trees.", 2);
        assertThat(c.cited()).containsExactly(1);
        assertThat(c.removed()).isEqualTo(1);
        assertThat(c.text()).isEqualTo("Stacks are LIFO [1]. Queues are FIFO. Heaps are trees.");
        assertThat(c.coverage()).isCloseTo(1 / 3.0, within(1e-9));
        assertThat(Citations.check("No idea.", 3).grounded()).isFalse();
    }

    @Test
    void quizGuardBlocksRepliesContainingAnAnswerAsAWholePhrase() {
        var secrets = List.of("last in first out", "7");
        assertThat(QuizModeGuard.screen("Think about which end you remove from [1].", secrets, "hint").allowed())
                .isTrue();
        var blocked = QuizModeGuard.screen("It is Last-In, First-Out [1].", secrets, "safe hint");
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.text()).isEqualTo("safe hint");
        assertThat(QuizModeGuard.screen("There are 7 nodes.", secrets, "h").allowed()).isFalse();
        assertThat(QuizModeGuard.screen("There are 17 nodes.", secrets, "h").allowed()).isTrue();
    }

    @Test
    void termsDropStopWordsAndPlurals() {
        assertThat(Text.terms("What are the queues and the stacks?")).containsExactly("queue", "stack");
        assertThat(Text.terms("Binary searches")).containsExactly("binary", "search");
    }
}
