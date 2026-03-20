package com.example.financialstream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Exclude Spring Boot's Kafka auto-config. This project manually configures:
//   - StreamsBuilderFactoryBean ("paymentsStreamsBuilder") in KafkaStreamsConfig.java
//   - ProducerFactory / KafkaTemplate in KafkaProducerConfig.java
// Without this exclusion, auto-config may create additional ConsumerFactory/ProducerFactory
// beans that conflict with the manual configuration.
// Note: The phantom "defaultKafkaStreamsBuilder" only appears if @EnableKafkaStreams is used
// or a bean named "defaultKafkaStreamsConfig" exists — neither applies here, but the exclude
// is still defensive best practice.
@SpringBootApplication(exclude = KafkaAutoConfiguration.class)
@EnableScheduling
public class FinancialStreamApplication {

    // Confluent Cloud DNS note (Java 21):
    // JVM default networkaddress.cache.ttl=30 (SecurityManager deprecated, not active).
    // DNS refreshes every 30s out of the box — no Security.setProperty() needed.
    // If running on Java <17 with a SecurityManager, set these in main() before SpringApplication.run():
    //   Security.setProperty("networkaddress.cache.ttl", "30");
    //   Security.setProperty("networkaddress.cache.negative.ttl", "0");

    public static void main(String[] args) {
        SpringApplication.run(FinancialStreamApplication.class, args);
    }
}
