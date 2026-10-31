package com.mentorloop.llm.application;

/**
 * The only way the app talks to a language model. Two implementations: a deterministic scripted provider (the
 * default, used by tests, CI and the demo; no network, no key) and an OpenAI-compatible HTTP client, which can
 * point at a gateway such as TokenGate.
 */
public interface LlmPort {

    /** @throws LlmUnavailableException when the provider cannot answer; callers degrade instead of failing */
    LlmReply complete(LlmRequest request);
}
