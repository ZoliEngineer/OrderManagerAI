package com.juzo.ai.ordermanager.advisor.agent;

import com.juzo.ai.ordermanager.advisor.config.AdvisorProperties;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Conversations live in memory only (lost on restart) and are evicted after a period of inactivity. */
@Component
public class ConversationStore {

    private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();
    private final Duration idleTtl;
    private final Clock clock;

    @Autowired
    public ConversationStore(AdvisorProperties props) {
        this(props.conversationIdleTtl(), Clock.systemUTC());
    }

    ConversationStore(Duration idleTtl, Clock clock) {
        this.idleTtl = idleTtl;
        this.clock = clock;
    }

    public Conversation create(String subject) {
        evictIdle();
        Conversation conversation = new Conversation(UUID.randomUUID(), subject, clock.instant());
        conversations.put(conversation.id(), conversation);
        return conversation;
    }

    /** Unknown ids and other users' conversations are indistinguishable to the caller (404). */
    public Conversation get(UUID id, String subject) {
        Conversation conversation = conversations.get(id);
        if (conversation == null || !conversation.ownerSubject().equals(subject)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        conversation.touch(clock.instant());
        return conversation;
    }

    private void evictIdle() {
        var cutoff = clock.instant().minus(idleTtl);
        conversations.values().removeIf(c -> c.lastActive().isBefore(cutoff));
    }
}
