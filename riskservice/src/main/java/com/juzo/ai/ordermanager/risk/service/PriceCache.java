package com.juzo.ai.ordermanager.risk.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class PriceCache {

    private static final Logger log = LoggerFactory.getLogger(PriceCache.class);
    private static final String LISTENER_ID = "priceCacheListener";
    // How far back to look per partition when seeding the cache at startup.
    // With 10 tickers and frequent ticks, 500 messages per partition is ample.
    private static final int LOOKBACK_PER_PARTITION = 500;

    private final Map<String, BigDecimal> prices = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final String topic;

    public PriceCache(ConsumerFactory<String, String> consumerFactory,
                      KafkaListenerEndpointRegistry listenerRegistry,
                      @Value("${app.kafka.topic.marketdata}") String topic) {
        this.consumerFactory = consumerFactory;
        this.listenerRegistry = listenerRegistry;
        this.topic = topic;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        loadLatestPrices();
        listenerRegistry.getListenerContainer(LISTENER_ID).start();
    }

    private void loadLatestPrices() {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("price-cache-seed", null)) {
            List<TopicPartition> partitions = assignPartitions(consumer);
            Map<TopicPartition, Long> targetOffsets = seekToNearEnd(consumer, partitions);
            drainToCache(consumer, targetOffsets);
            log.info("Price cache seeded with {} tickers from topic {}", prices.size(), topic);
        } catch (Exception e) {
            log.error("Failed to seed price cache from topic {}", topic, e);
        }
    }

    private List<TopicPartition> assignPartitions(Consumer<String, String> consumer) {
        List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .toList();
        consumer.assign(partitions);
        return partitions;
    }

    private Map<TopicPartition, Long> seekToNearEnd(Consumer<String, String> consumer,
                                                     List<TopicPartition> partitions) {
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
        Map<TopicPartition, Long> targetOffsets = new HashMap<>();
        for (TopicPartition tp : partitions) {
            long endOffset = endOffsets.get(tp);
            if (endOffset > 0) {
                consumer.seek(tp, Math.max(0, endOffset - LOOKBACK_PER_PARTITION));
                targetOffsets.put(tp, endOffset);
            }
        }
        return targetOffsets;
    }

    private void drainToCache(Consumer<String, String> consumer, Map<TopicPartition, Long> targetOffsets) {
        while (!targetOffsets.isEmpty()) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(3));
            if (records.isEmpty()) break;
            for (ConsumerRecord<String, String> record : records) {
                updatePrice(record.value());
                TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                if (record.offset() + 1 >= targetOffsets.getOrDefault(tp, Long.MAX_VALUE)) {
                    targetOffsets.remove(tp);
                }
            }
        }
    }

    @KafkaListener(id = LISTENER_ID, topics = "${app.kafka.topic.marketdata}", autoStartup = "false")
    public void onMessage(ConsumerRecord<String, String> record) {
        updatePrice(record.value());
    }

    private void updatePrice(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            String ticker = node.get("ticker").asText();
            BigDecimal price = new BigDecimal(node.get("price").asText());
            prices.put(ticker, price);
            log.debug("Price updated: {}={}", ticker, price);
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("Failed to parse price message: {}", json, e);
        }
    }

    public Optional<BigDecimal> get(String ticker) {
        Optional<BigDecimal> result = Optional.ofNullable(prices.get(ticker));
        if (result.isPresent()) {
            log.debug("Price cache hit for ticker={}, value={}", ticker, result.get());
        } else {
            log.warn("Price not found in cache for ticker={}", ticker);
        }
        return result;
    }
}
