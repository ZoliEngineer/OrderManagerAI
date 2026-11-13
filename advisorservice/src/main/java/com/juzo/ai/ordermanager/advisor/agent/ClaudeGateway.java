package com.juzo.ai.ordermanager.advisor.agent;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUnion;

import java.util.List;

/** One streamed Claude request. Kept as an interface so the agent loop can be tested without the API. */
public interface ClaudeGateway {

    interface StreamListener {
        void onTextDelta(String text);

        void onThinkingDelta(String thinking);
    }

    Message send(List<MessageParam> history, List<ToolUnion> tools, StreamListener listener);
}
