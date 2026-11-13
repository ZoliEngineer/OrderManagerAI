package com.juzo.ai.ordermanager.advisor.web;

import com.juzo.ai.ordermanager.advisor.agent.AdvisorAgent;
import com.juzo.ai.ordermanager.advisor.agent.AgentEventSink;
import com.juzo.ai.ordermanager.advisor.agent.Conversation;
import com.juzo.ai.ordermanager.advisor.agent.ConversationStore;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Chat API. Each turn streams its events as Server-Sent Events. The browser reads them with fetch (not
 * EventSource) so the bearer token can travel in the Authorization header rather than in the URL.
 */
@RestController
@RequestMapping("/api/conversations")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(10).toMillis();

    private final ConversationStore store;
    private final AdvisorAgent agent;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(ConversationStore store, AdvisorAgent agent) {
        this.store = store;
        this.agent = agent;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> create(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("conversationId", store.create(jwt.getSubject()).id());
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable UUID id, @Valid @RequestBody ChatRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        Conversation conversation = acquire(id, jwt);
        return stream(conversation, sink -> agent.chat(conversation, request.text().strip(),
                request.accountId() == null || request.accountId().isBlank() ? null : request.accountId().toLowerCase(),
                jwt.getTokenValue(), sink));
    }

    @PostMapping(value = "/{id}/approvals", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter decide(@PathVariable UUID id, @Valid @RequestBody ApprovalRequest request,
                             @AuthenticationPrincipal Jwt jwt) {
        Conversation conversation = acquire(id, jwt);
        return stream(conversation, sink -> agent.resume(conversation, request.decisions(), jwt.getTokenValue(), sink));
    }

    private Conversation acquire(UUID id, Jwt jwt) {
        Conversation conversation = store.get(id, jwt.getSubject());
        if (!conversation.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The advisor is still answering the previous message");
        }
        return conversation;
    }

    private SseEmitter stream(Conversation conversation, Consumer<AgentEventSink> turn) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        SseSink sink = new SseSink(emitter);
        try {
            executor.submit(() -> {
                try {
                    turn.accept(sink);
                } catch (RuntimeException e) {
                    log.error("Advisor turn failed for conversation {}", conversation.id(), e);
                    sink.emit(AgentEventSink.ERROR, Map.of("message", "Unexpected error: " + e.getMessage()));
                    sink.emit(AgentEventSink.DONE, Map.of("awaitingApproval", false));
                } finally {
                    conversation.release();
                    emitter.complete();
                }
            });
        } catch (RuntimeException e) {
            conversation.release();
            throw e;
        }
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * If the browser disconnects, the turn still runs to completion so the conversation history stays
     * consistent; further events are simply dropped.
     */
    private static final class SseSink implements AgentEventSink {
        private final SseEmitter emitter;
        private volatile boolean open = true;

        SseSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public synchronized void emit(String type, Object data) {
            if (!open) return;
            try {
                emitter.send(SseEmitter.event().name(type).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                open = false;
            }
        }
    }
}
