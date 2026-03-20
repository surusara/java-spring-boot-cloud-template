package com.example.financialstream.service;

import com.example.financialstream.model.InputEvent;
import org.springframework.stereotype.Service;

@Service
public class NoopCsfleCryptoService implements CsfleCryptoService {

    @Override
    public InputEvent normalizeAfterSerde(InputEvent inputEvent) {
        return inputEvent;
    }
}
