package com.juzo.ai.ordermanager.order.config;

//import org.apache.kafka.clients.admin.NewTopic;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
//import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

  // @Bean
  //  public NewTopic marketDataTopic() {
  //      return TopicBuilder.name(marketDataTopic)
   //             .partitions(3)
   //             .replicas(3)
   //             .build();
   // }
}
