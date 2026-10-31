package com.mentorloop.notification.application;

import com.mentorloop.adaptive.application.AdaptiveEvents;
import com.mentorloop.adaptive.application.ProgressStore;
import com.mentorloop.course.application.CourseService;
import com.mentorloop.outbox.application.EventEnvelope;
import com.mentorloop.outbox.application.ProcessedEvents;
import com.mentorloop.shared.config.MentorLoopProperties;
import com.mentorloop.shared.mail.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Certificate mail and review reminders. The dedup row and the send share a transaction, and the row is written
 * first: if SMTP fails the transaction rolls back and the event is retried, so a learner may, rarely, get a mail
 * twice (SMTP accepted it but the commit failed). It never silently gets none.
 */
@Service
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);
    private static final String CONSUMER = "notifier";

    private final ProcessedEvents processed;
    private final ProgressStore progress;
    private final CourseService courses;
    private final Mailer mailer;
    private final ReminderLog reminders;
    private final MentorLoopProperties props;
    private final Clock clock;

    Notifier(ProcessedEvents processed, ProgressStore progress, CourseService courses, Mailer mailer, ReminderLog reminders,
             MentorLoopProperties props, Clock clock) {
        this.processed = processed;
        this.progress = progress;
        this.courses = courses;
        this.mailer = mailer;
        this.reminders = reminders;
        this.props = props;
        this.clock = clock;
    }

    /** @return true if a mail went out */
    @Transactional
    public boolean handle(EventEnvelope e) {
        if (!AdaptiveEvents.CERTIFICATE_ISSUED.equals(e.type())) {
            return false;
        }
        if (!processed.markProcessed(CONSUMER, e.eventId())) {
            return false;
        }
        String email = (String) e.data().get("email");
        if (email == null) {
            return false;
        }
        String code = (String) e.data().get("code");
        String title = (String) e.data().get("title");
        String body = "You mastered every concept in " + title + ".\n\nCertificate code: " + code
                + "\nAnyone can check it at GET /v1/certificates/" + code + "\n";
        String text = "MentorLoop certificate\n\nCourse: " + title + "\nLearner: " + e.data().get("learner")
                + "\nIssued: " + e.data().get("issuedAt") + "\nCode: " + code + "\n";
        mailer.send(List.of(email), "Certificate: " + title, body,
                new Mailer.Attachment("certificate-" + code + ".txt", "text/plain",
                        text.getBytes(StandardCharsets.UTF_8)));
        return true;
    }

    /**
     * One reminder per learner and course per reminder slot ({@code mentorloop.reviews.reminder-every}).
     *
     * @return reminders sent
     */
    @Transactional
    public int remind() {
        Instant now = clock.instant();
        long slot = now.toEpochMilli() / props.reviews().reminderEvery().toMillis();
        int sent = 0;
        for (ProgressStore.DueLearner d : progress.learnersWithDueReviews(now, 200)) {
            var enrollment = courses.enrollment(d.courseId(), d.learner());
            var course = courses.course(d.courseId());
            if (enrollment.isEmpty() || course.isEmpty()) {
                continue;
            }
            if (!reminders.claim(d.courseId(), d.learner(), slot, now)) {
                continue;
            }
            mailer.send(List.of(enrollment.get().email()), "Reviews due in " + course.get().title(),
                    d.due() + " review" + (d.due() == 1 ? " is" : "s are") + " due in " + course.get().title()
                            + ". A few minutes now keeps them from fading.\n", null);
            sent++;
        }
        if (sent > 0) {
            log.info("sent {} review reminders", sent);
        }
        return sent;
    }
}
