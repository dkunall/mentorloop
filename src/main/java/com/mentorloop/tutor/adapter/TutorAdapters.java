package com.mentorloop.tutor.adapter;

import com.mentorloop.course.application.CourseViews;
import com.mentorloop.course.application.LessonIndexer;
import com.mentorloop.tutor.application.TutorIndex;
import com.mentorloop.tutor.application.TutorService;
import com.mentorloop.tutor.domain.HashingEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Configuration(proxyBeanMethods = false)
class TutorAdapters {

    @Bean
    HashingEmbedder hashingEmbedder() {
        return new HashingEmbedder();
    }

    /** Course content flows into the tutor index through the course module's port. */
    @Component
    static class LessonIndexing implements LessonIndexer {

        private final TutorIndex index;

        LessonIndexing(TutorIndex index) {
            this.index = index;
        }

        @Override
        public void index(UUID courseId, CourseViews.Lesson lesson) {
            index.indexLesson(courseId, lesson.id(), lesson.title(), lesson.body());
        }
    }

    @Component
    @ConditionalOnProperty(name = "mentorloop.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class TranscriptRetention {

        private static final Logger log = LoggerFactory.getLogger(TranscriptRetention.class);
        private final TutorService tutor;

        TranscriptRetention(TutorService tutor) {
            this.tutor = tutor;
        }

        @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
        void purge() {
            int n = tutor.purgeTranscripts();
            if (n > 0) {
                log.info("deleted {} tutor messages past retention", n);
            }
        }
    }
}
