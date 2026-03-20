package com.example.financialstream.controller;

import com.example.financialstream.runtime.RuntimeDiscoveryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes the runtime discovery data on the business port (8080)
 * for dev and test environments ONLY.
 *
 * In preprod/prod this controller does not exist —
 * the data is only available via /actuator/runtime on management port 9090.
 *
 * Activate by setting: SPRING_PROFILES_ACTIVE=dev (or test)
 */
@RestController
@RequestMapping("/health")
@Profile({"dev", "test"})
public class RuntimeDiscoveryController {

    private final RuntimeDiscoveryService runtimeDiscoveryService;

    public RuntimeDiscoveryController(RuntimeDiscoveryService runtimeDiscoveryService) {
        this.runtimeDiscoveryService = runtimeDiscoveryService;
    }

    @GetMapping("/runtime")
    public ResponseEntity<Map<String, Object>> runtime() {
        return ResponseEntity.ok(runtimeDiscoveryService.discover());
    }
}
