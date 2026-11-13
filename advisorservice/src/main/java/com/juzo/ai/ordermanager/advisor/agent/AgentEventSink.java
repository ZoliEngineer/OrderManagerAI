package com.juzo.ai.ordermanager.advisor.agent;

/** Receives the events of one advisor turn (streamed to the browser as Server-Sent Events). */
public interface AgentEventSink {

    String TEXT_DELTA = "text_delta";
    String THINKING = "thinking";
    String TOOL_ACTIVITY = "tool_activity";
    String APPROVAL_REQUIRED = "approval_required";
    String ERROR = "error";
    String DONE = "done";

    void emit(String type, Object data);
}
