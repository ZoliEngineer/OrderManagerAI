package com.juzo.ai.ordermanager.config;

//import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
//import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${app.kafka.topic.marketdata}")
    private String marketDataTopic;

  // @Bean
  //  public NewTopic marketDataTopic() {
  //      return TopicBuilder.name(marketDataTopic)
   //             .partitions(3)
   //             .replicas(3)
   //             .build();
   // }
}
