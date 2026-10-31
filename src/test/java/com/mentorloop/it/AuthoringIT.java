package com.mentorloop.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.mentorloop.assessment.application.QuizService;
import com.mentorloop.support.Api;
import com.mentorloop.support.ItBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringIT extends ItBase {

    @Autowired
    QuizService quizService;

    @Test
    void generatedItemsAreDraftsUntilOneReviewerApprovesThem() throws Exception {
        World w = world();
        JsonNode drafts = instructor().post("/v1/courses/" + w.courseId() + "/items:generate",
                Map.of("concept", "stacks", "count", 2)).expect(201).body();
        assertThat(drafts.size()).isEqualTo(2);
        JsonNode first = drafts.get(0).path("item");
        assertThat(first.path("status").asText()).isEqualTo("DRAFT");
        assertThat(first.path("stem").asText()).startsWith("Fill in the blank:").contains("_____");
        String id = first.path("id").asText();

        Learner l = learner(w);
        attempt(l, id, "d1", text("stack")).expect(404);
        l.api().get("/v1/items/" + id).expect(404);
        l.api().post("/v1/courses/" + w.courseId() + "/items:generate", Map.of("concept", "stacks")).expect(403);

        List<Api.Response> votes = AttemptsIT.race(8, () -> instructor().post("/v1/items/" + id + ":approve", Map.of()));
        assertThat(votes.stream().filter(r -> r.status() == 200)).hasSize(1);
        assertThat(votes.stream().filter(r -> r.status() == 409)).hasSize(7);
        instructor().post("/v1/items/" + id + ":reject", Map.of()).expect(409);
        JsonNode view = l.api().get("/v1/items/" + id).expect(200).body();
        assertThat(view.has("key")).isFalse();
        assertThat(view.toString()).doesNotContain("\"answer\"");
    }

    @Test
    void generationFailsCleanlyWhenTheModelIsDown() {
        World w = world();
        llm.down(true);
        instructor().post("/v1/courses/" + w.courseId() + "/items:generate", Map.of("concept", "stacks"))
                .expect(503);
        llm.say("not json at all");
        llm.down(false);
        instructor().post("/v1/courses/" + w.courseId() + "/items:generate", Map.of("concept", "stacks"))
                .expect(502);
        assertThat(count("select count(*) from item where course_id = ?::uuid and status = 'DRAFT'", w.courseId()))
                .isZero();
    }

    @Test
    void calibrationMovesDifficultyTowardsWhatLearnersFound() {
        World w = world();
        for (int i = 0; i < 4; i++) {
            Learner l = learner(w);
            answerRight(l, w, "stack-size");
            answerWrong(l, w, "stack-undo");
            if (i == 0) {
                answerRight(l, w, "stack-undo");
            }
            answerWrong(l, w, "stack-order");
        }
        JsonNode run = instructor().post("/v1/courses/" + w.courseId() + "/calibration:run", Map.of()).expect(200)
                .body();
        assertThat(run.path("itemsUpdated").asInt()).isEqualTo(3);
        double size = difficulty(w.item("stack-size"));
        double undo = difficulty(w.item("stack-undo"));
        double order = difficulty(w.item("stack-order"));
        assertThat(size).isLessThan(undo);
        assertThat(undo).isLessThanOrEqualTo(order);
        assertThat(count("select max(version) from item_calibration where item_id = ?::uuid",
                w.item("stack-size"))).isGreaterThanOrEqualTo(1);
    }

    private double difficulty(String itemId) {
        return db.sql("select difficulty from item where id = ?::uuid").param(itemId).query(Double.class).single();
    }

    @Test
    void aQuizIsScoredOnSubmitAndAnOverdueOneIsClosedByTheJob() {
        World w = world();
        Learner l = learner(w);
        JsonNode quiz = l.api().post("/v1/courses/" + w.courseId() + "/quizzes", Map.of()).expect(201).body();
        String quizId = quiz.path("id").asText();
        assertThat(l.api().post("/v1/courses/" + w.courseId() + "/quizzes", Map.of()).expect(201).text("id"))
                .isEqualTo(quizId);
        assertThat(quiz.toString()).doesNotContain("explanation");
        JsonNode r = attempt(l, w.item("stack-order"), "qz1", choice(0), quizId).expect(200).body();
        assertThat(r.path("explanation").isNull()).isTrue();
        attempt(l, w.item("stack-size"), "qz2", number(5), quizId).expect(200);
        attempt(l, w.item("queue-order"), "qz3", choice(1), quizId).expect(409);
        JsonNode done = l.api().post("/v1/quizzes/" + quizId + ":submit", Map.of()).expect(200).body();
        assertThat(done.path("status").asText()).isEqualTo("SUBMITTED");
        int asked = done.path("questions").size();
        assertThat(done.path("score").asDouble()).isEqualTo(1.0 / asked);
        attempt(l, w.item("stack-undo"), "qz4", text("stack"), quizId).expect(409);
        as(unique("nosy")).get("/v1/quizzes/" + quizId).expect(404);

        Learner late = learner(w);
        String q2 = late.api().post("/v1/courses/" + w.courseId() + "/quizzes", Map.of()).expect(201).text("id");
        attempt(late, w.item("stack-order"), "lt1", choice(0), q2).expect(200);
        db.sql("update quiz set closes_at = now() - interval '1 minute' where id = ?::uuid").param(q2).update();
        assertThat(quizService.expireOverdue()).isGreaterThanOrEqualTo(1);
        JsonNode expired = late.api().get("/v1/quizzes/" + q2).expect(200).body();
        assertThat(expired.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(expired.path("score").isNumber()).isTrue();
        attempt(late, w.item("stack-size"), "lt2", number(2), q2).expect(409);
        assertThat(UUID.fromString(q2)).isNotNull();
    }
}
