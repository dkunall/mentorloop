package com.mentorloop.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.mentorloop.support.ItBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TutorIT extends ItBase {

    private static JsonNode ask(Learner l, World w, String q) {
        return l.api().post("/v1/courses/" + w.courseId() + "/tutor/questions", Map.of("question", q)).expect(200).body();
    }

    @Test
    void answersComeFromTheLessonsWithCitationsThatPointAtThem() {
        World w = world();
        Learner l = learner(w);
        JsonNode a = ask(l, w, "What order does a queue keep items in?");
        assertThat(a.path("status").asText()).isEqualTo("GROUNDED");
        assertThat(a.path("mode").asText()).isEqualTo("ANSWER");
        assertThat(a.path("answer").asText()).contains("first in, first out").contains("[");
        assertThat(a.path("citations").size()).isPositive();
        assertThat(a.path("citations").findValuesAsText("lessonId")).contains(w.lessons().get("queues"));
        assertThat(a.path("model").asText()).isEqualTo("scripted/scripted-extractive-v1");

        JsonNode none = ask(l, w, "Who won the cricket world cup in 1983?");
        assertThat(none.path("status").asText()).isEqualTo("REFUSED");
        assertThat(none.path("citations").size()).isZero();

        JsonNode history = l.api().get("/v1/courses/" + w.courseId() + "/tutor/messages").expect(200).body();
        assertThat(history.size()).isEqualTo(2);
        learner(w).api().get("/v1/courses/" + w.courseId() + "/tutor/messages").expect(200).body()
                .forEach(m -> assertThat(m.path("learner").asText()).isNotEqualTo(l.subject()));
    }

    @Test
    void duringAQuizTheTutorOnlyHintsAndNeverRepeatsAnAnswer() {
        World w = world();
        Learner l = learner(w);
        JsonNode quiz = l.api().post("/v1/courses/" + w.courseId() + "/quizzes", Map.of()).expect(201).body();
        assertThat(quiz.path("questions").findValuesAsText("itemId")).contains(w.item("stack-order"));

        JsonNode hint = ask(l, w, "Which order does a stack remove elements in?");
        assertThat(hint.path("mode").asText()).isEqualTo("HINT");
        assertThat(hint.path("answer").asText().toLowerCase()).doesNotContain("last in, first out");

        llm.say("Easy: it is Last in, first out [1].");
        JsonNode blocked = ask(l, w, "Which order does a stack remove elements in?");
        assertThat(blocked.path("status").asText()).isEqualTo("GUARD_BLOCKED");
        assertThat(blocked.path("answer").asText()).doesNotContainIgnoringCase("last in, first out");
        assertThat(blocked.path("citations").size()).isZero();

        llm.reset();
        l.api().post("/v1/quizzes/" + quiz.path("id").asText() + ":submit", Map.of()).expect(200);
        JsonNode after = ask(l, w, "Which order does a stack remove elements in?");
        assertThat(after.path("mode").asText()).isEqualTo("ANSWER");
    }

    @Test
    void whenTheModelIsDownLearnersGetSearchResultsNotAnError() {
        World w = world();
        Learner l = learner(w);
        llm.down(true);
        JsonNode a = ask(l, w, "What is the call stack?");
        assertThat(a.path("status").asText()).isEqualTo("DEGRADED_SEARCH");
        assertThat(a.path("citations").size()).isPositive();
        assertThat(a.path("answer").asText()).containsIgnoringCase("call stack");
    }

    @Test
    void eachLearnerHasAPerMinuteBudget() {
        World w = world();
        Learner l = learner(w);
        for (int i = 0; i < TUTOR_PER_MINUTE; i++) {
            ask(l, w, "What is a stack?");
        }
        var limited = l.api().post("/v1/courses/" + w.courseId() + "/tutor/questions",
                Map.of("question", "What is a stack?")).expect(429);
        assertThat(limited.header("Retry-After")).isNotBlank();
        ask(learner(w), w, "What is a stack?");
    }

    @Test
    void outsidersCannotUseTheTutor() {
        World w = world();
        as(unique("outsider")).post("/v1/courses/" + w.courseId() + "/tutor/questions", Map.of("question", "hi"))
                .expect(404);
        learner(w).api().post("/v1/courses/" + w.courseId() + "/tutor/questions", Map.of("question", " ")).expect(400);
    }
}
