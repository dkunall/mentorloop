package com.mentorloop.assessment.application;

import com.mentorloop.assessment.domain.RaschCalibrator;
import com.mentorloop.course.application.CourseService;
import com.mentorloop.shared.config.MentorLoopProperties;
import com.mentorloop.shared.security.Caller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Re-estimates item difficulty from learners' first answers with a Rasch model. Only items with at least
 * {@code minResponses} answers are updated; each update is kept as a new version.
 */
@Service
public class CalibrationService {

    public record Run(UUID courseId, int responses, int itemsUpdated, int itemsBelowThreshold, int iterations) {
    }

    private final AttemptStore attempts;
    private final ItemStore items;
    private final CourseService courses;
    private final MentorLoopProperties.Calibration cfg;
    private final Clock clock;
    private final RaschCalibrator rasch = new RaschCalibrator();

    CalibrationService(AttemptStore attempts, ItemStore items, CourseService courses, MentorLoopProperties props,
                       Clock clock) {
        this.attempts = attempts;
        this.items = items;
        this.courses = courses;
        this.cfg = props.calibration();
        this.clock = clock;
    }

    @Transactional
    public Run run(Caller caller, UUID courseId) {
        courses.requireOwned(caller, courseId);
        return calibrate(courseId);
    }

    @Transactional
    public List<Run> runAll() {
        return attempts.coursesWithAttempts().stream().map(this::calibrate).toList();
    }

    Run calibrate(UUID courseId) {
        // Two runs for one course (instructor button and the scheduled job) would both write version n+1.
        courses.lock(courseId);
        List<RaschCalibrator.Response> responses = attempts.firstResponses(courseId);
        if (responses.isEmpty()) {
            return new Run(courseId, 0, 0, 0, 0);
        }
        RaschCalibrator.Fit fit = rasch.fit(responses, 200);
        Map<UUID, Set<String>> learnersPerItem = new HashMap<>();
        responses.forEach(r -> learnersPerItem.computeIfAbsent(r.item(), k -> new HashSet<>()).add(r.learner()));
        Instant now = clock.instant();
        int updated = 0;
        int below = 0;
        for (Item item : items.byIds(fit.difficulty().keySet())) {
            int n = fit.responses().getOrDefault(item.id(), 0);
            if (n < cfg.minResponses()) {
                below++;
                continue;
            }
            items.setDifficulty(item.id(), fit.difficulty().get(item.id()), item.difficultyVersion() + 1, n,
                    learnersPerItem.get(item.id()).size(), now);
            updated++;
        }
        return new Run(courseId, responses.size(), updated, below, fit.iterations());
    }
}
