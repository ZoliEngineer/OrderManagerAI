package com.juzo.ai.ordermanager.advisor.agent;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@Component
public class SystemPrompt {

    private final String text;

    public SystemPrompt() {
        try {
            this.text = new ClassPathResource("prompts/advisor-system-prompt.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String text() {
        return text;
    }
}
