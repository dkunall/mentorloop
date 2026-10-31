package com.mentorloop.llm.application;

import java.util.List;

/**
 * A model call, kept structured so the deterministic provider can work from the parts and the HTTP provider can
 * render them with {@link Prompts}.
 *
 * @param context numbered from 1 in the order given; citations refer to these numbers
 */
public record LlmRequest(Mode mode, String question, List<Context> context) {

    public enum Mode {
        /** Answer from the context only, citing it. */
        ANSWER,
        /** A graded quiz is open: nudge, never answer. */
        HINT,
        /** Write practice questions from the context, as JSON. */
        GENERATE_ITEMS
    }

    public record Context(String label, String text) {
    }

    public LlmRequest {
        context = List.copyOf(context);
    }
}
