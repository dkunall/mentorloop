package com.mentorloop.course.adapter;

import com.mentorloop.course.application.CourseService;
import com.mentorloop.course.application.CourseService.ConceptIn;
import com.mentorloop.course.application.CourseViews;
import com.mentorloop.shared.error.ApiException;
import com.mentorloop.shared.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
class CourseController {

    record CourseIn(String title) {
    }

    record ConceptsIn(List<ConceptIn> concepts) {
    }

    record ModuleIn(String title) {
    }

    record LessonIn(String concept, String title, String body, String videoUrl) {
    }

    record EnrollIn(String cohort) {
    }

    private final CourseService courses;

    CourseController(CourseService courses) {
        this.courses = courses;
    }

    @PostMapping("/v1/courses")
    @ResponseStatus(HttpStatus.CREATED)
    CourseViews.Course create(Caller caller, @RequestBody CourseIn in) {
        return courses.create(caller, in.title());
    }

    @PostMapping("/v1/courses/{courseId}/concepts")
    @ResponseStatus(HttpStatus.CREATED)
    List<CourseViews.Concept> concepts(Caller caller, @PathVariable UUID courseId, @RequestBody ConceptsIn in) {
        return courses.defineConcepts(caller, courseId, in.concepts());
    }

    @PostMapping("/v1/courses/{courseId}/modules")
    @ResponseStatus(HttpStatus.CREATED)
    CourseViews.Module module(Caller caller, @PathVariable UUID courseId, @RequestBody ModuleIn in) {
        return courses.addModule(caller, courseId, in.title());
    }

    @PostMapping("/v1/modules/{moduleId}/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    CourseViews.Lesson lesson(Caller caller, @PathVariable UUID moduleId, @RequestBody LessonIn in) {
        if (in.concept() == null) {
            throw ApiException.invalid("concept is required");
        }
        return courses.addLesson(caller, moduleId, in.concept(), in.title(), in.body(), in.videoUrl());
    }

    @PostMapping("/v1/courses/{courseId}:publish")
    CourseViews.Course publish(Caller caller, @PathVariable UUID courseId) {
        return courses.publish(caller, courseId);
    }

    @PostMapping("/v1/courses/{courseId}/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    CourseViews.Enrollment enroll(Caller caller, @PathVariable UUID courseId, @RequestBody(required = false) EnrollIn in) {
        return courses.enroll(caller, courseId, in == null ? null : in.cohort());
    }

    @GetMapping("/v1/courses/{courseId}")
    CourseViews.Outline outline(Caller caller, @PathVariable UUID courseId) {
        return courses.outline(caller, courseId);
    }
}
