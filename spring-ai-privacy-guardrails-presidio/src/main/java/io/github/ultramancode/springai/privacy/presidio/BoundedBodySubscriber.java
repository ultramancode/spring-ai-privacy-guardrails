package io.github.ultramancode.springai.privacy.presidio;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Collects an HTTP body with backpressure and cancels before exceeding a hard byte limit. */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final int maxBytes;
    private final ByteArrayOutputStream bodyBuffer;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private int receivedBytes;

    BoundedBodySubscriber(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
        this.bodyBuffer = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
    }

    static HttpResponse.BodyHandler<byte[]> handler(int maxBytes) {
        return ignored -> new BoundedBodySubscriber(maxBytes);
    }

    static boolean isBodyLimitExceeded(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure;
             current != null && visited.add(current);
             current = current.getCause()) {
            if (current instanceof BodyLimitExceededException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return this.body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        if (this.subscription != null) {
            subscription.cancel();
            return;
        }
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (this.body.isDone()) {
            return;
        }
        long additional = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
        if (additional > this.maxBytes - (long) this.receivedBytes) {
            this.subscription.cancel();
            this.body.completeExceptionally(new BodyLimitExceededException());
            return;
        }
        for (ByteBuffer buffer : buffers) {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            this.bodyBuffer.writeBytes(bytes);
            this.receivedBytes += bytes.length;
        }
        this.subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
        this.body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
        this.body.complete(this.bodyBuffer.toByteArray());
    }

    /** Internal body-limit signal with stack-trace creation disabled. */
    private static final class BodyLimitExceededException extends RuntimeException {

        private BodyLimitExceededException() {
            super(null, null, false, false);
        }
    }
}
