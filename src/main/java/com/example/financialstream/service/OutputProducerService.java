package com.example.financialstream.service;

import com.example.financialstream.model.OutputEvent;

public interface OutputProducerService {
    void send(OutputEvent event);
}
