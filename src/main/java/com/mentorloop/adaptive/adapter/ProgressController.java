package com.mentorloop.adaptive.adapter;

import com.mentorloop.adaptive.application.ProgressService;
import com.mentorloop.shared.security.Caller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
class ProgressController {

    private final ProgressService progress;

    ProgressController(ProgressService progress) {
        this.progress = progress;
    }

    @GetMapping("/v1/courses/{courseId}/progress")
    ProgressService.Progress progress(Caller caller, @PathVariable UUID courseId,
                                      @RequestParam(required = false) String learner) {
        return progress.progress(caller, courseId, learner);
    }

    @GetMapping("/v1/courses/{courseId}/next")
    ProgressService.Next next(Caller caller, @PathVariable UUID courseId) {
        return progress.next(caller, courseId);
    }

    @PostMapping("/v1/lessons/{lessonId}:complete")
    Map<String, Boolean> complete(Caller caller, @PathVariable UUID lessonId) {
        return Map.of("newlyCompleted", progress.completeLesson(caller, lessonId));
    }

    @GetMapping("/v1/courses/{courseId}/dashboard")
    ProgressService.Dashboard dashboard(Caller caller, @PathVariable UUID courseId) {
        return progress.dashboard(caller, courseId);
    }

    /** Public: whoever is shown a certificate can check it. */
    @GetMapping("/v1/certificates/{code}")
    ProgressService.CertificateView certificate(@PathVariable String code) {
        return progress.certificate(code);
    }
}
