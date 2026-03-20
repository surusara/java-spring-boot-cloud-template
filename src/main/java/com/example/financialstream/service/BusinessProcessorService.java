package com.example.financialstream.service;

import com.example.financialstream.model.InputEvent;
import com.example.financialstream.model.ProcessingResult;

public interface BusinessProcessorService {
    ProcessingResult process(String streamName,
                             String topic,
                             int partition,
                             long offset,
                             String key,
                             InputEvent event);
}
