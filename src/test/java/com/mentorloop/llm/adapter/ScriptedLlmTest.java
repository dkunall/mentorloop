package com.mentorloop.llm.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentorloop.llm.application.LlmRequest;
import com.mentorloop.llm.application.LlmRequest.Context;
import com.mentorloop.llm.application.LlmRequest.Mode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptedLlmTest {

    private final ScriptedLlm llm = new ScriptedLlm();
    private final List<Context> ctx = List.of(
            new Context("Stacks", "A **stack** is a last in, first out collection. Push adds an element on top."),
            new Context("Queues", "A **queue** is a first in, first out collection. Enqueue adds at the back."));

    @Test
    void answersWithCitedSentencesFromTheContextOnly() {
        String reply = llm.complete(new LlmRequest(Mode.ANSWER, "What does push do on a stack?", ctx)).text();
        assertThat(reply).isEqualTo("A stack is a last in, first out collection [1]. Push adds an element on top [1].");
        assertThat(llm.complete(new LlmRequest(Mode.ANSWER, "Explain photosynthesis", ctx)).text())
                .isEqualTo("I can't find that in the course material.");
    }

    @Test
    void hintsQuoteNothingFromTheContext() {
        String hint = llm.complete(new LlmRequest(Mode.HINT, "Is a stack LIFO?", ctx)).text();
        assertThat(hint).contains("\"Stacks\" [1]").doesNotContain("last in").doesNotContain("first out");
    }

    @Test
    void generatesClozeItemsFromBoldTermsAsJson() throws Exception {
        String json = llm.complete(new LlmRequest(Mode.GENERATE_ITEMS, "5", ctx)).text();
        var items = new ObjectMapper().readTree(json);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("answer").asText()).isEqualTo("stack");
        assertThat(items.get(0).path("stem").asText()).contains("A _____ is a last in").doesNotContain("**");
        assertThat(items.get(1).path("source").asInt()).isEqualTo(2);
    }

    @Test
    void isDeterministic() {
        var r = new LlmRequest(Mode.ANSWER, "first in first out", ctx);
        assertThat(llm.complete(r)).isEqualTo(llm.complete(r));
    }
}
