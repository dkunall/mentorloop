package com.mentorloop.assessment.adapter;

import com.mentorloop.assessment.application.AttemptService;
import com.mentorloop.assessment.application.AttemptStore;
import com.mentorloop.assessment.application.CalibrationService;
import com.mentorloop.assessment.application.Item;
import com.mentorloop.assessment.application.ItemService;
import com.mentorloop.assessment.application.QuizService;
import com.mentorloop.assessment.domain.Answer;
import com.mentorloop.shared.error.ApiException;
import com.mentorloop.shared.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
class AssessmentController {

    record GenerateIn(String concept, Integer count) {
    }

    record AttemptIn(String clientAttemptId, Answer answer, Long latencyMs, UUID quizId) {
    }

    record ManualGradeIn(Boolean correct, Double score) {
    }

    /** Learners see items without keys. */
    record LearnerItem(UUID id, String type, String stem, List<String> options) {
    }

    private final ItemService items;
    private final AttemptService attempts;
    private final QuizService quizzes;
    private final CalibrationService calibration;

    AssessmentController(ItemService items, AttemptService attempts, QuizService quizzes, CalibrationService calibration) {
        this.items = items;
        this.attempts = attempts;
        this.quizzes = quizzes;
        this.calibration = calibration;
    }

    @PostMapping("/v1/courses/{courseId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    ItemService.Authored create(Caller caller, @PathVariable UUID courseId, @RequestBody ItemService.ItemIn in) {
        return items.create(caller, courseId, in);
    }

    @PostMapping("/v1/courses/{courseId}/items:generate")
    @ResponseStatus(HttpStatus.CREATED)
    List<ItemService.Authored> generate(Caller caller, @PathVariable UUID courseId, @RequestBody GenerateIn in) {
        if (in.concept() == null) {
            throw ApiException.invalid("concept is required");
        }
        return items.generate(caller, courseId, in.concept(), in.count() == null ? 3 : in.count());
    }

    @GetMapping("/v1/courses/{courseId}/items")
    List<ItemService.Authored> list(Caller caller, @PathVariable UUID courseId,
                                    @RequestParam(required = false) String status) {
        return items.list(caller, courseId, status);
    }

    @PostMapping("/v1/items/{itemId}:approve")
    Item approve(Caller caller, @PathVariable UUID itemId) {
        return items.review(caller, itemId, true);
    }

    @PostMapping("/v1/items/{itemId}:reject")
    Item reject(Caller caller, @PathVariable UUID itemId) {
        return items.review(caller, itemId, false);
    }

    @PostMapping("/v1/items/{itemId}:retire")
    Item retire(Caller caller, @PathVariable UUID itemId) {
        return items.retire(caller, itemId);
    }

    @GetMapping("/v1/items/{itemId}")
    LearnerItem item(Caller caller, @PathVariable UUID itemId) {
        Item i = items.learnerView(caller, itemId);
        return new LearnerItem(i.id(), i.type().name(), i.stem(), i.options());
    }

    @PostMapping("/v1/items/{itemId}/attempts")
    AttemptService.Result attempt(Caller caller, @PathVariable UUID itemId, @RequestBody AttemptIn in) {
        return attempts.submit(caller, itemId, in.clientAttemptId(), in.answer(), in.latencyMs(), in.quizId());
    }

    @GetMapping("/v1/courses/{courseId}/attempts:needing-review")
    List<AttemptStore.Attempt> reviewQueue(Caller caller, @PathVariable UUID courseId) {
        return attempts.reviewQueue(caller, courseId);
    }

    @PostMapping("/v1/attempts/{attemptId}:grade")
    AttemptService.Result grade(Caller caller, @PathVariable UUID attemptId, @RequestBody ManualGradeIn in) {
        return attempts.gradeManually(caller, attemptId, in.correct(), in.score());
    }

    @PostMapping("/v1/courses/{courseId}/quizzes")
    @ResponseStatus(HttpStatus.CREATED)
    QuizService.QuizView startQuiz(Caller caller, @PathVariable UUID courseId) {
        return quizzes.start(caller, courseId);
    }

    @PostMapping("/v1/quizzes/{quizId}:submit")
    QuizService.QuizView submitQuiz(Caller caller, @PathVariable UUID quizId) {
        return quizzes.submit(caller, quizId);
    }

    @GetMapping("/v1/quizzes/{quizId}")
    QuizService.QuizView quiz(Caller caller, @PathVariable UUID quizId) {
        return quizzes.get(caller, quizId);
    }

    @PostMapping("/v1/courses/{courseId}/calibration:run")
    CalibrationService.Run calibrate(Caller caller, @PathVariable UUID courseId) {
        return calibration.run(caller, courseId);
    }
}
