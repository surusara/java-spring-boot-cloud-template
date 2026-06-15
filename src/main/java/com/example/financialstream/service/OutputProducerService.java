package com.example.financialstream.service;

import com.example.financialstream.model.OutputEvent;

/**
 * Standalone transactional publisher to the output topic.
 *
 * <p><b>Do not call from inside the Kafka Streams processor.</b> The processor must
 * forward downstream records via {@code ProcessorContext.forward(...)} so they are
 * sent through Streams' own producer. Using this service from the processor creates a
 * second, independent Kafka transaction (one for Streams' input-offset commit, one
 * for this producer's output write) which re-introduces duplicates on every
 * rebalance.
 *
 * <p>Kept for callers outside the Streams data path (e.g. an outbox poller or a
 * manual replay tool) that need a transactional producer.
 *
 * @deprecated for use from the Streams processor. Use {@code context.forward(...)}
 *             + {@code KStream.to(outputTopic, ...)} instead.
 */
@Deprecated(forRemoval = false)
public interface OutputProducerService {
    void send(OutputEvent event);
}
