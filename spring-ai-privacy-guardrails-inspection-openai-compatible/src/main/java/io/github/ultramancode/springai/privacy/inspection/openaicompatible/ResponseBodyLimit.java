package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.io.InputDecorator;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/** Bounds JSON response reads, including HTTP error bodies parsed by the SDK. */
final class ResponseBodyLimit extends InputDecorator {
    private final int maxBytes;

    ResponseBodyLimit(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public InputStream decorate(IOContext context, InputStream input) {
        return new FilterInputStream(input) {
            private long remaining = maxBytes;

            @Override
            public int read() throws IOException {
                int value = in.read();
                if (value != -1) {
                    checkLimit(1);
                }
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                int bytesRead = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
                if (bytesRead > 0) {
                    checkLimit(bytesRead);
                }
                return bytesRead;
            }

            private void checkLimit(int bytesRead) {
                remaining -= bytesRead;
                if (remaining < 0) {
                    throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
                }
            }
        };
    }

    @Override
    public InputStream decorate(IOContext context, byte[] input, int offset, int length) {
        // Returning null tells Jackson to use the original input.
        return null;
    }

    @Override
    public Reader decorate(IOContext context, Reader input) {
        return null;
    }
}
