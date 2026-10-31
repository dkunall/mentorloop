package com.mentorloop.shared.config;

import com.mentorloop.outbox.application.Outbox;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(MentorLoopProperties.class)
class CoreConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Graded attempts, keyed by learner, so one learner's mastery updates are consumed in order on one partition. */
    @Bean
    NewTopic attemptTopic() {
        return TopicBuilder.name(Outbox.ATTEMPT_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic learnerEventsTopic() {
        return TopicBuilder.name(Outbox.LEARNER_EVENTS).partitions(3).replicas(1).build();
    }
}
