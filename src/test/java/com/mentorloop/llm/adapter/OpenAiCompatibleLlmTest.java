package com.mentorloop.llm.adapter;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.mentorloop.llm.application.LlmRequest;
import com.mentorloop.llm.application.LlmUnavailableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The HTTP adapter against a WireMock stand-in for an OpenAI-compatible server. No real model, no real key. */
class OpenAiCompatibleLlmTest {

    private static final String FAKE_KEY = "test-key-not-a-secret";
    private static WireMockServer server;

    private final LlmRequest request = new LlmRequest(LlmRequest.Mode.ANSWER, "What is a stack?",
            List.of(new LlmRequest.Context("Stacks", "A stack is last in, first out.")));

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    private OpenAiCompatibleLlm client(String key, Duration timeout) {
        return new OpenAiCompatibleLlm(URI.create(server.baseUrl()), "tutor-small", key, timeout);
    }

    @Test
    void sendsTheChatRequestAndReadsTheFirstChoice() {
        server.stubFor(post("/v1/chat/completions").willReturn(okJson("""
                {"model":"tutor-small-0613","choices":[{"message":{"role":"assistant",
                "content":"A stack is LIFO [1]."}}]}""")));
        var reply = client(FAKE_KEY, Duration.ofSeconds(2)).complete(request);
        assertThat(reply.text()).isEqualTo("A stack is LIFO [1].");
        assertThat(reply.model()).isEqualTo("tutor-small-0613");
        server.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + FAKE_KEY))
                .withRequestBody(matchingJsonPath("$.model", equalTo("tutor-small")))
                .withRequestBody(matchingJsonPath("$.temperature", equalTo("0")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", containing("[1] (Stacks)")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", containing("Learner question: What is a stack?"))));
    }

    @Test
    void sendsNoAuthorizationHeaderWithoutAKey() {
        server.stubFor(post("/v1/chat/completions").willReturn(okJson("""
                {"choices":[{"message":{"content":"ok [1]"}}]}""")));
        client("", Duration.ofSeconds(2)).complete(request);
        server.verify(postRequestedFor(urlEqualTo("/v1/chat/completions")).withoutHeader("Authorization"));
    }

    @Test
    void serverErrorsEmptyRepliesAndTimeoutsBecomeUnavailable() {
        server.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> client(FAKE_KEY, Duration.ofSeconds(2)).complete(request))
                .isInstanceOf(LlmUnavailableException.class).hasMessageNotContaining(FAKE_KEY);

        server.stubFor(post("/v1/chat/completions").willReturn(okJson("{\"choices\":[]}")));
        assertThatThrownBy(() -> client(FAKE_KEY, Duration.ofSeconds(2)).complete(request))
                .isInstanceOf(LlmUnavailableException.class);

        server.stubFor(post("/v1/chat/completions").willReturn(okJson("{\"choices\":[{\"message\":{\"content\":\"late\"}}]}")
                .withFixedDelay(1500)));
        assertThatThrownBy(() -> client(FAKE_KEY, Duration.ofMillis(300)).complete(request))
                .isInstanceOf(LlmUnavailableException.class);
    }
}
