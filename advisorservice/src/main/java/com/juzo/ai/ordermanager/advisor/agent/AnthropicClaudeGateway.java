package com.juzo.ai.ordermanager.advisor.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ToolUnion;
import com.juzo.ai.ordermanager.advisor.config.AdvisorProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AnthropicClaudeGateway implements ClaudeGateway {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final AdvisorProperties props;
    private final TextBlockParam systemBlock;

    public AnthropicClaudeGateway(AnthropicClient client, AdvisorProperties props, SystemPrompt systemPrompt) {
        this.client = client;
        this.props = props;
        // Frozen system prompt with a cache breakpoint: tools + system are cached across every turn.
        this.systemBlock = TextBlockParam.builder()
                .text(systemPrompt.text())
                .cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build())
                .build();
    }

    @Override
    public Message send(List<MessageParam> history, List<ToolUnion> tools, StreamListener listener) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(props.model())
                .maxTokens(props.maxTokens())
                .systemOfTextBlockParams(List.of(systemBlock))
                .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(props.effort())).build())
                .tools(tools)
                .messages(history);
        if (props.refusalFallback()) {
            // If a safety classifier declines the request, the API re-runs it on a fallback model it picks itself.
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        MessageAccumulator accumulator = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params.build())) {
            stream.stream().forEach(event -> {
                accumulator.accumulate(event);
                event.contentBlockDelta().ifPresent(d -> {
                    d.delta().text().ifPresent(t -> listener.onTextDelta(t.text()));
                    d.delta().thinking().ifPresent(t -> listener.onThinkingDelta(t.thinking()));
                });
            });
        }
        return accumulator.message();
    }
}
