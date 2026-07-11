package com.example.consumer.listener;

import com.example.consumer.avro.PaymentInput;
import com.example.consumer.service.PaymentProcessingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentListener {

    private final PaymentProcessingService processingService;

    public PaymentListener(PaymentProcessingService processingService) {
        this.processingService = processingService;
    }

    @KafkaListener(
            id = "payments-consumer",
            topics = "${app.consumer.topic}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(PaymentInput input) {
        processingService.process(input);
    }
}
