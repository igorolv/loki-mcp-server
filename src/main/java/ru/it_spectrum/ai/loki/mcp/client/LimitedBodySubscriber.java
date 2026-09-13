package ru.it_spectrum.ai.loki.mcp.client;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static ru.it_spectrum.ai.loki.mcp.model.ErrorCode.UPSTREAM_RESPONSE_TOO_LARGE;

/**
 * Cancels upstream before copying a chunk that would exceed the byte budget.
 */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedBodySubscriber(int limit) {
        this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription subscription) {
        if (this.subscription != null || body.isDone()) {
            subscription.cancel();
            return;
        }
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> items) {
        if (body.isDone()) return;
        long incoming = items.stream().mapToLong(ByteBuffer::remaining).sum();
        if (incoming > limit - bytes.size()) {
            fail(TransportErrors.error(UPSTREAM_RESPONSE_TOO_LARGE));
            return;
        }
        for (ByteBuffer item : items) {
            byte[] chunk = new byte[item.remaining()];
            item.get(chunk);
            bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }

    @Override
    public synchronized void onError(Throwable error) {
        fail(error);
    }

    @Override
    public synchronized void onComplete() {
        body.complete(bytes.toByteArray());
    }

    synchronized void fail(Throwable error) {
        body.completeExceptionally(error);
        if (subscription != null) subscription.cancel();
        bytes.reset();
    }
}
