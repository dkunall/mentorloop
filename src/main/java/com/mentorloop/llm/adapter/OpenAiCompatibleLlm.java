package com.mentorloop.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.mentorloop.llm.application.LlmPort;
import com.mentorloop.llm.application.LlmReply;
import com.mentorloop.llm.application.LlmRequest;
import com.mentorloop.llm.application.LlmUnavailableException;
import com.mentorloop.llm.application.Prompts;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls {@code POST {baseUrl}/v1/chat/completions} on any OpenAI-compatible server (a hosted API, a local model
 * server, or a TokenGate gateway). Temperature 0. The API key, when set, goes only into the Authorization header
 * and is never logged. Any transport error, non-2xx status or unparseable body becomes
 * {@link LlmUnavailableException}, so the tutor can fall back to plain search results.
 */
public final class OpenAiCompatibleLlm implements LlmPort {

    private final RestClient http;
    private final String model;

    public OpenAiCompatibleLlm(URI baseUrl, String model, String apiKey, Duration timeout) {
        // HTTP/1.1: some gateways and test servers mishandle the h2c upgrade the JDK client attempts on plain http.
        var jdk = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(timeout);
        var builder = RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(factory);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.http = builder.build();
        this.model = model;
    }

    @Override
    public LlmReply complete(LlmRequest request) {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", Prompts.system(request.mode())),
                        Map.of("role", "user", "content", Prompts.user(request))));
        JsonNode reply;
        try {
            reply = http.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new LlmUnavailableException("model call failed: " + e.getClass().getSimpleName(), e);
        }
        JsonNode content = reply == null ? null : reply.path("choices").path(0).path("message").path("content");
        if (content == null || !content.isTextual() || content.asText().isBlank()) {
            throw new LlmUnavailableException("model reply had no message content", null);
        }
        String served = reply.path("model").asText(model);
        return new LlmReply(content.asText(), "openai-compatible", served);
    }
}
