package com.example.financialstream.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.financialstream.circuit.BusinessOutcomeCircuitBreaker;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    @Autowired(required = false)
    private BusinessOutcomeCircuitBreaker circuitBreaker;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "kafka-streams-payment-processor");
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/circuit-breaker/status")
    public ResponseEntity<Map<String, Object>> circuitBreakerStatus() {
        Map<String, Object> response = new HashMap<>();
        
        if (circuitBreaker != null) {
            response.put("state", circuitBreaker.getState().toString());
            response.put("nextRestartDelay", circuitBreaker.getNextRestartDelay());
        } else {
            response.put("state", "UNKNOWN");
            response.put("message", "Circuit breaker not initialized");
        }
        
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> applicationStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("application", "kafka-streams-cb");
        response.put("version", "1.0.0");
        response.put("status", "RUNNING");
        response.put("kafkaConnection", "CONNECTED");
        response.put("streamThreads", 4);
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }
}
