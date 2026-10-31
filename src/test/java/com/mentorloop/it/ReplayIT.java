package com.mentorloop.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.mentorloop.outbox.application.Outbox;
import com.mentorloop.support.ItBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka delivers at least once and, across a rebalance, possibly late. The adaptive consumer must end in the same
 * place whatever order and however many times it hears a learner's attempts.
 */
class ReplayIT extends ItBase {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Test
    void duplicatedAndReversedEventsGiveTheSameMasteryAndSchedule() throws Exception {
        World w = world();
        Learner l = learner(w);
        String[] script = {"stack-order", "stack-size", "stack-undo", "stack-order", "stack-size"};
        boolean[] right = {false, true, false, true, true};
        long seq = 0;
        for (int i = 0; i < script.length; i++) {
            seq = (right[i] ? answerRight(l, w, script[i]) : answerWrong(l, w, script[i])).path("seq").asLong();
        }
        awaitApplied(l, w, seq);
        List<Map<String, Object>> mastery = mastery(l);
        List<Map<String, Object>> cards = cards(l);
        assertThat(mastery).hasSize(1);
        assertThat(cards).hasSize(3);

        // Forget everything the consumer derived, then make it hear the same history shuffled and twice over.
        for (String t : List.of("practice_event", "learner_progress", "mastery", "review_card")) {
            db.sql("delete from " + t + " where learner = ?").param(l.subject()).update();
        }
        List<String> payloads = new ArrayList<>(db.sql("select payload::text from outbox where type = 'AttemptRecorded'"
                + " and payload->'data'->>'learner' = ? order by id").param(l.subject()).query(String.class).list());
        assertThat(payloads).hasSize(script.length);
        List<String> replay = new ArrayList<>(payloads);
        Collections.reverse(replay);
        replay.addAll(payloads);
        replay.add(payloads.get(2));
        for (String p : replay) {
            kafka.send(Outbox.ATTEMPT_EVENTS, l.subject(), p).get();
        }
        awaitApplied(l, w, seq);
        eventually(() -> count("select count(*) from practice_event where learner = ? and applied_at is null",
                l.subject()) == 0);
        assertThat(mastery(l)).isEqualTo(mastery);
        assertThat(cards(l)).isEqualTo(cards);
        assertThat(count("select count(*) from practice_event where learner = ?", l.subject())).isEqualTo(script.length);
    }

    private List<Map<String, Object>> mastery(Learner l) {
        return db.sql("select concept_id, p_known, attempts, correct, mastered_at is not null as mastered from mastery"
                + " where learner = ? order by concept_id").param(l.subject()).query().listOfRows();
    }

    private List<Map<String, Object>> cards(Learner l) {
        return db.sql("select item_id, repetitions, interval_days, ease, lapses, due_at from review_card"
                + " where learner = ? order by item_id").param(l.subject()).query().listOfRows();
    }

    @Test
    void aManualGradeThatArrivesBeforeTheAttemptItGradesStillCountsOnce() throws Exception {
        World w = world();
        Learner l = learner(w);
        JsonNode r = attempt(l, w.item("queue-explain"), "e1", text("earliest first, always")).expect(200).body();
        instructor().post("/v1/attempts/" + r.path("attemptId").asText() + ":grade", Map.of("correct", true))
                .expect(200);
        eventually(() -> concept(progress(l, w), "queues").path("correct").asInt() == 1);

        String recorded = envelope(l, "AttemptRecorded");
        String regraded = envelope(l, "AttemptRegraded");
        for (String t : List.of("practice_event", "learner_progress", "mastery", "review_card")) {
            db.sql("delete from " + t + " where learner = ?").param(l.subject()).update();
        }
        db.sql("delete from processed_event where event_id = (select event_id from outbox where type = 'AttemptRegraded'"
                + " and payload->'data'->>'learner' = ?)").param(l.subject()).update();

        kafka.send(Outbox.ATTEMPT_EVENTS, l.subject(), regraded).get();
        kafka.send(Outbox.ATTEMPT_EVENTS, l.subject(), recorded).get();
        kafka.send(Outbox.ATTEMPT_EVENTS, l.subject(), regraded).get();
        awaitApplied(l, w, r.path("seq").asLong());
        JsonNode q = concept(progress(l, w), "queues");
        assertThat(q.path("attempts").asInt()).isEqualTo(1);
        assertThat(q.path("correct").asInt()).isEqualTo(1);
    }

    private String envelope(Learner l, String type) {
        return db.sql("select payload::text from outbox where type = ? and payload->'data'->>'learner' = ?")
                .params(type, l.subject()).query(String.class).single();
    }
}
