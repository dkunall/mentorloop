package com.mentorloop.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.mentorloop.notification.application.Notifier;
import com.mentorloop.support.ItBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewsIT extends ItBase {

    @Autowired
    Notifier notifier;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void dueReviewsComeFirstAndEarnOneReminderPerSlot() {
        World w = world();
        Learner l = learner(w);
        answerRight(l, w, "stack-order");
        long seq = answerWrong(l, w, "stack-size").path("seq").asLong();
        awaitApplied(l, w, seq);
        assertThat(progress(l, w).path("reviewsDue").asInt()).isZero();

        // Ten simulated minutes is one review "day" here; jump the cards past it rather than waiting.
        db.sql("update review_card set due_at = now() - interval '1 minute' where learner = ?").param(l.subject())
                .update();
        redis.delete("ml:due:" + w.courseId() + ":" + l.subject());
        assertThat(progress(l, w).path("reviewsDue").asInt()).isEqualTo(2);
        JsonNode next = l.api().get("/v1/courses/" + w.courseId() + "/next").expect(200).body();
        assertThat(next.path("kind").asText()).isEqualTo("REVIEW");

        notifier.remind();
        eventually(() -> mailsTo(l.subject()).stream().anyMatch(m -> m.path("Subject").asText().startsWith("Reviews due")));
        JsonNode mail = mailsTo(l.subject()).getFirst();
        assertThat(mailText(mail)).contains("2 reviews are due");
        notifier.remind();
        assertThat(count("select count(*) from reminder_sent where learner = ?", l.subject())).isEqualTo(1);

        // Answering a due review reschedules it, and the cached due list is dropped on commit.
        seq = answerRight(l, w, "stack-order").path("seq").asLong();
        awaitApplied(l, w, seq);
        eventually(() -> progress(l, w).path("reviewsDue").asInt() == 1);
    }
}
