package com.mentorloop.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.mentorloop.support.ItBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LearningFlowIT extends ItBase {

    @Test
    void aLearnerMastersStacksThenQueuesAndGetsAVerifiableCertificate() {
        World w = world();
        Learner l = learner(w);

        JsonNode start = progress(l, w);
        assertThat(concept(start, "stacks").path("state").asText()).isEqualTo("UNLOCKED");
        assertThat(concept(start, "queues").path("state").asText()).isEqualTo("LOCKED");
        JsonNode next = l.api().get("/v1/courses/" + w.courseId() + "/next").expect(200).body();
        assertThat(next.path("kind").asText()).isEqualTo("LESSON");
        assertThat(next.path("concept").asText()).isEqualTo("stacks");
        l.api().post("/v1/lessons/" + w.lessons().get("stacks") + ":complete", Map.of()).expect(200);
        assertThat(l.api().get("/v1/courses/" + w.courseId() + "/next").expect(200).body().path("kind").asText())
                .isEqualTo("PRACTICE");

        JsonNode wrong = answerWrong(l, w, "stack-order");
        assertThat(wrong.path("correct").asBoolean()).isFalse();
        assertThat(wrong.path("explanation").asText()).contains("pops the element pushed most recently");
        answerRight(l, w, "stack-order");
        answerRight(l, w, "stack-size");
        long seq = answerRight(l, w, "stack-undo").path("seq").asLong();
        awaitApplied(l, w, seq);
        JsonNode mid = progress(l, w);
        assertThat(concept(mid, "stacks").path("state").asText()).isEqualTo("MASTERED");
        assertThat(concept(mid, "stacks").path("attempts").asInt()).isEqualTo(4);
        assertThat(concept(mid, "queues").path("state").asText()).isIn("UNLOCKED", "LEARNING");
        assertThat(mid.path("certificateCode").isNull()).isTrue();

        answerRight(l, w, "queue-order");
        answerRight(l, w, "queue-count");
        seq = answerRight(l, w, "queue-explain").path("seq").asLong();
        awaitApplied(l, w, seq);
        eventually(() -> !progress(l, w).path("certificateCode").isNull());
        String code = progress(l, w).path("certificateCode").asText();
        assertThat(code).matches("ML-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}");

        JsonNode cert = anon().get("/v1/certificates/" + code).expect(200).body();
        assertThat(cert.path("learner").asText()).isEqualTo(l.subject());
        anon().get("/v1/certificates/ML-AAAA-AAAA-AAAA").expect(404);

        eventually(() -> mailsTo(l.subject()).stream().anyMatch(m -> m.path("Subject").asText().startsWith("Certificate")));
        JsonNode mail = mailsTo(l.subject()).stream()
                .filter(m -> m.path("Subject").asText().startsWith("Certificate")).findFirst().orElseThrow();
        assertThat(mailText(mail)).contains(code);
        assertThat(mail.path("Attachments").asInt()).isEqualTo(1);
    }

    @Test
    void learnersSeeOnlyTheirOwnProgressAndInstructorsOnlyTheirOwnCourses() {
        World w = world();
        Learner a = learner(w);
        Learner b = learner(w);
        a.api().get("/v1/courses/" + w.courseId() + "/progress?learner=" + b.subject()).expect(403);
        instructor().get("/v1/courses/" + w.courseId() + "/progress?learner=" + b.subject()).expect(200);
        as(OTHER_INSTRUCTOR).get("/v1/courses/" + w.courseId() + "/progress?learner=" + b.subject()).expect(403);
        as(OTHER_INSTRUCTOR).get("/v1/courses/" + w.courseId() + "/dashboard").expect(403);
        a.api().get("/v1/courses/" + w.courseId() + "/dashboard").expect(403);
        as(unique("stranger")).get("/v1/courses/" + w.courseId() + "/progress").expect(404);
        anon().get("/v1/courses/" + w.courseId() + "/progress").expect(401);
    }

    @Test
    void theDashboardCountsWhatLearnersDid() {
        World w = world();
        Learner a = learner(w);
        Learner b = learner(w);
        answerRight(a, w, "stack-order");
        long seq = answerWrong(b, w, "stack-order").path("seq").asLong();
        awaitApplied(b, w, seq);
        awaitApplied(a, w, 1);
        JsonNode d = instructor().get("/v1/courses/" + w.courseId() + "/dashboard").expect(200).body();
        assertThat(d.path("enrolled").asInt()).isEqualTo(2);
        JsonNode row = null;
        for (JsonNode i : d.path("items")) {
            if (i.path("itemId").asText().equals(w.item("stack-order"))) {
                row = i;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("attempts").asInt()).isEqualTo(2);
        assertThat(row.path("correctRate").asDouble()).isEqualTo(0.5);
    }
}
