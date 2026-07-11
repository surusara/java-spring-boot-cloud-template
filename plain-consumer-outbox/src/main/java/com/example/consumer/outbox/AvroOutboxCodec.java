package com.example.consumer.outbox;

import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Encodes an Avro {@link SpecificRecord} to plain binary for storage in the outbox table, and
 * decodes it back. This is deliberately <b>schema-registry-free framing</b>: the DB row is not
 * coupled to a Schema Registry id. The relay decodes back to the SpecificRecord and hands it to
 * the {@code KafkaAvroSerializer}, which then registers/looks up the subject and applies the
 * Confluent wire format when publishing.
 */
@Component
public class AvroOutboxCodec {

    public byte[] encode(SpecificRecord record) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            SpecificDatumWriter<SpecificRecord> writer = new SpecificDatumWriter<>(record.getSchema());
            writer.write(record, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to Avro-encode outbox payload", ex);
        }
    }

    public <T extends SpecificRecord> T decode(byte[] bytes, Class<T> type) {
        try {
            T instance = type.getDeclaredConstructor().newInstance();
            SpecificDatumReader<T> reader = new SpecificDatumReader<>(instance.getSchema());
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
            return reader.read(null, decoder);
        } catch (ReflectiveOperationException | IOException ex) {
            throw new IllegalStateException("Failed to Avro-decode outbox payload for " + type.getSimpleName(), ex);
        }
    }
}
