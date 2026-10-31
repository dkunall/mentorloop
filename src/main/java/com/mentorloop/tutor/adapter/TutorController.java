package com.mentorloop.tutor.adapter;

import com.mentorloop.tutor.application.TranscriptStore;
import com.mentorloop.tutor.application.TutorService;
import com.mentorloop.shared.security.Caller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
class TutorController {

    record QuestionIn(String question) {
    }

    private final TutorService tutor;

    TutorController(TutorService tutor) {
        this.tutor = tutor;
    }

    @PostMapping("/v1/courses/{courseId}/tutor/questions")
    TutorService.Answer ask(Caller caller, @PathVariable UUID courseId, @RequestBody QuestionIn in) {
        return tutor.ask(caller, courseId, in.question());
    }

    @GetMapping("/v1/courses/{courseId}/tutor/messages")
    List<TranscriptStore.Message> history(Caller caller, @PathVariable UUID courseId) {
        return tutor.history(caller, courseId);
    }
}
