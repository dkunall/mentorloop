package com.mentorloop.support;

import com.mentorloop.llm.adapter.ScriptedLlm;
import com.mentorloop.llm.application.LlmPort;
import com.mentorloop.llm.application.LlmReply;
import com.mentorloop.llm.application.LlmRequest;
import com.mentorloop.llm.application.LlmUnavailableException;

import java.util.concurrent.atomic.AtomicReference;

/** The scripted model, plus switches a test can flip: make it fail, or make it say something specific. */
public final class TestLlm implements LlmPort {

    private final ScriptedLlm scripted = new ScriptedLlm();
    private final AtomicReference<String> forced = new AtomicReference<>();
    private volatile boolean down;

    @Override
    public LlmReply complete(LlmRequest request) {
        if (down) {
            throw new LlmUnavailableException("test switched the model off", null);
        }
        String f = forced.get();
        return f == null ? scripted.complete(request) : new LlmReply(f, "test", "forced");
    }

    public void down(boolean d) {
        down = d;
    }

    public void say(String reply) {
        forced.set(reply);
    }

    public void reset() {
        down = false;
        forced.set(null);
    }
}
