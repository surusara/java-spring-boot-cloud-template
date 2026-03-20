package com.example.financialstream.service;

import com.example.financialstream.model.InputEvent;

public interface CsfleCryptoService {
    InputEvent normalizeAfterSerde(InputEvent inputEvent);
}
