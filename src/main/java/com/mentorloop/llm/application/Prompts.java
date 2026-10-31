package com.mentorloop.llm.application;

/** System and user messages for providers that take free text. */
public final class Prompts {

    private Prompts() {
    }

    public static String system(LlmRequest.Mode mode) {
        return switch (mode) {
            case ANSWER -> """
                    You are a tutor for one course. Answer only from the numbered course excerpts you are given. \
                    After every sentence, cite the excerpt it comes from as [n]. If the excerpts do not contain \
                    the answer, say you cannot find it in the course material. Do not use outside knowledge.""";
            case HINT -> """
                    You are a tutor and the learner is taking a graded quiz. Do not state or confirm any answer, \
                    option or value. Give one short hint that points to the relevant excerpt, cited as [n], and \
                    ask a guiding question.""";
            case GENERATE_ITEMS -> """
                    Write short-answer practice questions answerable from the numbered excerpts alone. Reply with \
                    a JSON array only; each element is {"stem": string, "answer": string, "source": n}. The \
                    answer must appear word for word in excerpt n.""";
        };
    }

    public static String user(LlmRequest request) {
        StringBuilder sb = new StringBuilder("Course excerpts:\n");
        for (int i = 0; i < request.context().size(); i++) {
            var c = request.context().get(i);
            sb.append('[').append(i + 1).append("] (").append(c.label()).append(")\n").append(c.text()).append("\n\n");
        }
        if (request.mode() == LlmRequest.Mode.GENERATE_ITEMS) {
            sb.append("Number of questions wanted: ").append(request.question());
        } else {
            sb.append("Learner question: ").append(request.question());
        }
        return sb.toString();
    }
}
