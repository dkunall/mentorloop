package com.mentorloop.llm.adapter;

import com.mentorloop.llm.application.LlmPort;
import com.mentorloop.shared.config.MentorLoopProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    LlmPort llm(MentorLoopProperties props) {
        var p = props.llm();
        return switch (p.provider()) {
            case "scripted" -> {
                log.info("LLM provider: scripted (deterministic, no network)");
                yield new ScriptedLlm();
            }
            case "openai" -> {
                log.info("LLM provider: openai-compatible at {} model {}", p.baseUrl(), p.model());
                yield new OpenAiCompatibleLlm(p.baseUrl(), p.model(), p.apiKey(), p.timeout());
            }
            default -> throw new IllegalStateException("mentorloop.llm.provider must be scripted or openai");
        };
    }
}
