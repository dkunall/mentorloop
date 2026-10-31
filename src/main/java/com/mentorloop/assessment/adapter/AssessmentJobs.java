package com.mentorloop.assessment.adapter;

import com.mentorloop.assessment.application.CalibrationService;
import com.mentorloop.assessment.application.QuizService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mentorloop.jobs.enabled", havingValue = "true", matchIfMissing = true)
class AssessmentJobs {

    private static final Logger log = LoggerFactory.getLogger(AssessmentJobs.class);

    private final QuizService quizzes;
    private final CalibrationService calibration;

    AssessmentJobs(QuizService quizzes, CalibrationService calibration) {
        this.quizzes = quizzes;
        this.calibration = calibration;
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT10S")
    void expireQuizzes() {
        int n = quizzes.expireOverdue();
        if (n > 0) {
            log.info("expired {} quizzes past their time limit", n);
        }
    }

    @Scheduled(fixedDelayString = "${mentorloop.calibration.every}", initialDelayString = "${mentorloop.calibration.every}")
    void calibrate() {
        for (var run : calibration.runAll()) {
            if (run.itemsUpdated() > 0) {
                log.info("calibrated course {}: {} items updated from {} responses", run.courseId(), run.itemsUpdated(),
                        run.responses());
            }
        }
    }
}
