package io.github.ultramancode.springai.privacy.springai;

import java.io.Writer;
import java.util.Objects;

/** Accumulates transformed JSON without allowing the configured output bound to be exceeded. */
final class PrivacyJsonBoundedWriter extends Writer {

    private final StringBuilder buffer;

    PrivacyJsonBoundedWriter(int initialCapacity) {
        this.buffer = new StringBuilder(Math.min(
                initialCapacity,
                PrivacyJsonPayloadTransformer.MAX_TRANSFORMED_PAYLOAD_CHARACTERS
        ));
    }

    @Override
    public void write(int character) {
        requireAdditional(1);
        this.buffer.append((char) character);
    }

    @Override
    public void write(char[] characters, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, characters.length);
        requireAdditional(length);
        this.buffer.append(characters, offset, length);
    }

    @Override
    public void write(String value, int offset, int length) {
        Objects.requireNonNull(value, "value must not be null");
        Objects.checkFromIndexSize(offset, length, value.length());
        requireAdditional(length);
        this.buffer.append(value, offset, offset + length);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    @Override
    public String toString() {
        return this.buffer.toString();
    }

    private void requireAdditional(int additional) {
        if ((long) this.buffer.length() + additional
                > PrivacyJsonPayloadTransformer.MAX_TRANSFORMED_PAYLOAD_CHARACTERS) {
            throw new PrivacyJsonDocumentProcessor.OutputLimitExceeded();
        }
    }
}
