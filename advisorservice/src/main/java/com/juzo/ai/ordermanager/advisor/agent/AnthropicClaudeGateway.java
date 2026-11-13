package com.juzo.ai.ordermanager.advisor.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.InputJsonDelta;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ToolUnion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.juzo.ai.ordermanager.advisor.config.AdvisorProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params.build())) {
            return accumulate(stream.stream(), listener);
        }
    }

    /**
     * Folds a stream into a {@link Message} and forwards text/thinking deltas to the listener.
     *
     * <p>The SDK's {@link MessageAccumulator} requires at least one {@code input_json_delta} for every
     * {@code tool_use}/{@code server_tool_use} block. The API does not always send one: with dynamic-filtering web
     * search the model calls {@code web_search} programmatically, and that {@code server_tool_use} block carries its
     * complete input in {@code content_block_start} and then stops straight away, which made the accumulator throw
     * "Missing input JSON for index N". For such blocks we replay the start input as a single synthetic delta just
     * before the stop event.
     */
    static Message accumulate(Stream<RawMessageStreamEvent> events, StreamListener listener) {
        MessageAccumulator accumulator = MessageAccumulator.create();
        // Tool blocks that have started but not yet received an input_json_delta, with the input from their start event.
        Map<Long, JsonValue> awaitingInput = new HashMap<>();
        events.forEach(event -> {
            event.contentBlockStart().ifPresent(start -> {
                var block = start.contentBlock();
                block.toolUse().ifPresent(t -> awaitingInput.put(start.index(), t._input()));
                block.serverToolUse().ifPresent(t -> awaitingInput.put(start.index(), t._input()));
            });
            event.contentBlockDelta().ifPresent(d -> {
                if (d.delta().inputJson().isPresent()) {
                    awaitingInput.remove(d.index());
                }
            });
            event.contentBlockStop().ifPresent(stop -> {
                JsonValue startInput = awaitingInput.remove(stop.index());
                if (startInput != null) {
                    accumulator.accumulate(RawMessageStreamEvent.ofContentBlockDelta(
                            RawContentBlockDeltaEvent.builder()
                                    .index(stop.index())
                                    .delta(InputJsonDelta.builder().partialJson(toJson(startInput)).build())
                                    .build()));
                }
            });

            accumulator.accumulate(event);
            event.contentBlockDelta().ifPresent(d -> {
                d.delta().text().ifPresent(t -> listener.onTextDelta(t.text()));
                d.delta().thinking().ifPresent(t -> listener.onThinkingDelta(t.thinking()));
            });
        });
        return accumulator.message();
    }

    /** An absent or null input becomes an empty string, which the accumulator reads as an empty object. */
    private static String toJson(JsonValue input) {
        if (input.isMissing() || input.isNull()) {
            return "";
        }
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(input);
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
