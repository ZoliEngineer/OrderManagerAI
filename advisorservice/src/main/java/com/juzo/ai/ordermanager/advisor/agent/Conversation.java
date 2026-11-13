package com.juzo.ai.ordermanager.advisor.agent;

import com.anthropic.models.messages.MessageParam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory chat state. Mutated only by the single turn that holds {@link #tryAcquire()}. */
public class Conversation {

    private final UUID id;
    private final String ownerSubject;
    private final List<MessageParam> history = new ArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile Instant lastActive;
    private PendingApproval pending;
    /** tool_use ids of the last assistant message that never got a result (e.g. the turn hit max_tokens). */
    private List<String> danglingToolUseIds = List.of();

    public Conversation(UUID id, String ownerSubject, Instant now) {
        this.id = id;
        this.ownerSubject = ownerSubject;
        this.lastActive = now;
    }

    public UUID id() { return id; }

    public String ownerSubject() { return ownerSubject; }

    public List<MessageParam> history() { return history; }

    public Instant lastActive() { return lastActive; }

    public void touch(Instant now) { this.lastActive = now; }

    public PendingApproval pending() { return pending; }

    public void setPending(PendingApproval pending) { this.pending = pending; }

    public List<String> danglingToolUseIds() { return danglingToolUseIds; }

    public void setDanglingToolUseIds(List<String> ids) { this.danglingToolUseIds = List.copyOf(ids); }

    /** One turn at a time per conversation; a second concurrent request gets HTTP 409. */
    public boolean tryAcquire() { return busy.compareAndSet(false, true); }

    public void release() { busy.set(false); }
}
