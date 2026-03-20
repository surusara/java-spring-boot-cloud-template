package com.example.financialstream.circuit;

public interface StreamLifecycleController {
    void stopStream();
    void startStream();
    boolean isStoppedByBreaker();
}
