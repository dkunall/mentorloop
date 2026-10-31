package com.mentorloop.tutor.application;

import com.mentorloop.course.application.CourseService;
import com.mentorloop.llm.application.LlmPort;
import com.mentorloop.llm.application.LlmReply;
import com.mentorloop.llm.application.LlmRequest;
import com.mentorloop.llm.application.LlmUnavailableException;
import com.mentorloop.shared.config.MentorLoopProperties;
import com.mentorloop.shared.error.ApiException;
import com.mentorloop.shared.security.Caller;
import com.mentorloop.tutor.domain.Citations;
import com.mentorloop.tutor.domain.QuizModeGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The grounded tutor. Every answer is built from retrieved course chunks and must cite them; replies without a
 * valid citation are not shown, and the learner gets the matching passages instead.
 */
@Service
public class TutorService {

    public enum Status { GROUNDED, DEGRADED_SEARCH, REFUSED, GUARD_BLOCKED }

    public record Citation(int n, String label, UUID lessonId, boolean workedExample) {
    }

    public record Answer(long messageId, String answer, LlmRequest.Mode mode, Status status, List<Citation> citations,
                         double coverage, String model) {
    }

    public static final String NOT_IN_COURSE = "I can't find that in the course material.";
    static final String QUIZ_HINT = "You have a quiz open, so I can only point you to the material: re-read the "
            + "lesson for this concept and try to explain the idea in your own words first.";

    private static final Logger log = LoggerFactory.getLogger(TutorService.class);

    private final CourseService courses;
    private final Retriever retriever;
    private final LlmPort llm;
    private final QuizStatus quizzes;
    private final RateLimiter limiter;
    private final TranscriptStore transcripts;
    private final MentorLoopProperties.Tutor cfg;
    private final Clock clock;
    private final Timer timer;
    private final MeterRegistry meters;

    TutorService(CourseService courses, Retriever retriever, LlmPort llm, QuizStatus quizzes, RateLimiter limiter,
                 TranscriptStore transcripts, MentorLoopProperties props, Clock clock, MeterRegistry meters) {
        this.courses = courses;
        this.retriever = retriever;
        this.llm = llm;
        this.quizzes = quizzes;
        this.limiter = limiter;
        this.transcripts = transcripts;
        this.cfg = props.tutor();
        this.clock = clock;
        this.meters = meters;
        this.timer = Timer.builder("mentorloop.tutor.answer").publishPercentileHistogram().register(meters);
    }

    public Answer ask(Caller caller, UUID courseId, String question) {
        courses.requireEnrolled(caller, courseId);
        if (question == null || question.isBlank()) {
            throw ApiException.invalid("question is required");
        }
        if (question.length() > cfg.maxQuestionChars()) {
            throw ApiException.invalid("question is at most " + cfg.maxQuestionChars() + " characters");
        }
        if (!limiter.allow("tutor:" + caller.subject(), cfg.perMinute(), Duration.ofMinutes(1))) {
            meters.counter("mentorloop.tutor.rate_limited").increment();
            throw ApiException.tooMany("tutor-rate-limited",
                    "At most " + cfg.perMinute() + " tutor questions a minute", 60 - clock.instant().getEpochSecond() % 60);
        }
        long t0 = System.nanoTime();
        Optional<QuizStatus.OpenQuiz> quiz = quizzes.openQuiz(courseId, caller.subject());
        boolean quizOpen = quiz.isPresent();
        LlmRequest.Mode mode = quizOpen ? LlmRequest.Mode.HINT : LlmRequest.Mode.ANSWER;
        var hits = retriever.retrieve(courseId, question, cfg.topK(),
                QuizModeGuard.retrievalExcludesItemChunks(quizOpen));
        List<Citation> sources = new ArrayList<>();
        List<LlmRequest.Context> context = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            sources.add(new Citation(i + 1, h.label(), h.lessonId(), h.itemId() != null));
            context.add(new LlmRequest.Context(h.label(), h.text()));
        }
        String text;
        Status status;
        List<Citation> shown = List.of();
        double coverage = 0;
        String model = "none";
        if (hits.isEmpty()) {
            text = NOT_IN_COURSE;
            status = Status.REFUSED;
        } else {
            try {
                LlmReply reply = llm.complete(new LlmRequest(mode, question, context));
                model = reply.provider() + "/" + reply.model();
                Citations c = Citations.check(reply.text(), context.size());
                if (!c.grounded()) {
                    if (reply.text().strip().startsWith("I can't find")) {
                        text = NOT_IN_COURSE;
                        status = Status.REFUSED;
                    } else {
                        text = searchResults(hits);
                        status = Status.DEGRADED_SEARCH;
                        shown = sources;
                    }
                } else {
                    text = c.text();
                    coverage = c.coverage();
                    shown = sources.stream().filter(s -> c.cited().contains(s.n())).toList();
                    status = Status.GROUNDED;
                }
            } catch (LlmUnavailableException e) {
                log.warn("tutor model unavailable, answering with search results: {}", e.getMessage());
                meters.counter("mentorloop.tutor.degraded").increment();
                text = searchResults(hits);
                status = Status.DEGRADED_SEARCH;
                shown = sources;
            }
            if (quizOpen && status != Status.REFUSED) {
                var verdict = QuizModeGuard.screen(text, quiz.get().revealing(), QUIZ_HINT);
                if (!verdict.allowed()) {
                    meters.counter("mentorloop.tutor.guard_blocked").increment();
                    text = verdict.text();
                    status = Status.GUARD_BLOCKED;
                    shown = List.of();
                    coverage = 0;
                }
            }
        }
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        timer.record(Duration.ofNanos(System.nanoTime() - t0));
        meters.counter("mentorloop.tutor.answers", "status", status.name()).increment();
        long id = transcripts.save(new TranscriptStore.Message(0, courseId, caller.subject(), question, text,
                mode.name(), status.name(), shown, coverage, model, ms, clock.instant()));
        return new Answer(id, text, mode, status, shown, coverage, model);
    }

    public List<TranscriptStore.Message> history(Caller caller, UUID courseId) {
        courses.requireEnrolled(caller, courseId);
        return transcripts.recent(courseId, caller.subject(), 50);
    }

    public int purgeTranscripts() {
        Instant cutoff = clock.instant().minus(cfg.retention());
        return transcripts.deleteOlderThan(cutoff);
    }

    /** The fallback when the model is down or its reply can't be trusted: the passages themselves, cited. */
    static String searchResults(List<ChunkStore.Hit> hits) {
        StringBuilder sb = new StringBuilder("The tutor can't answer right now. These course passages match your question:");
        for (int i = 0; i < hits.size(); i++) {
            String t = hits.get(i).text();
            String snippet = t.length() > 240 ? t.substring(0, t.lastIndexOf(' ', 240) > 0 ? t.lastIndexOf(' ', 240) : 240) + "…" : t;
            sb.append("\n[").append(i + 1).append("] ").append(hits.get(i).label()).append(": ").append(snippet);
        }
        return sb.toString();
    }
}
