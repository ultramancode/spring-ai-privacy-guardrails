package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancels the subscription before collecting more than the configured response budget. */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final int maxResponseBytes;
    private final ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> bodyFuture = new CompletableFuture<>();
    private Flow.Subscription subscription;

    BoundedBodySubscriber(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return bodyFuture;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
        if (subscription != null) {
            value.cancel();
            return;
        }
        subscription = value;
        value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (bodyFuture.isDone()) {
            return;
        }
        long incomingBytes = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
        if (incomingBytes > maxResponseBytes - (long) responseBuffer.size()) {
            subscription.cancel();
            bodyFuture.completeExceptionally(new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED));
            return;
        }
        for (ByteBuffer buffer : buffers) {
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            responseBuffer.writeBytes(data);
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
        bodyFuture.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
        bodyFuture.complete(responseBuffer.toByteArray());
    }
}
