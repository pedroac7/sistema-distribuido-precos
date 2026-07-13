package validador.server;

import validador.model.PrecoPayload;
import validador.model.ValidationResult;
import validador.service.ValidadorService;
import validador.service.ValidadorUnavailableException;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

final class ValidationExecutor {
    static final int VALIDATION_WORKER_POOL_SIZE = 15;
    static final int VALIDATION_QUEUE_CAPACITY = 40;
    static final int VALIDATION_RESULT_TIMEOUT_MS = 2000;

    private final ThreadPoolExecutor workerPool;

    ValidationExecutor(String threadNamePrefix) {
        this(threadNamePrefix, VALIDATION_WORKER_POOL_SIZE);
    }

    ValidationExecutor(String threadNamePrefix, int workerPoolSize) {
        this(threadNamePrefix, workerPoolSize, VALIDATION_QUEUE_CAPACITY);
    }

    ValidationExecutor(String threadNamePrefix, int workerPoolSize, int queueCapacity) {
        this.workerPool = new ThreadPoolExecutor(
                workerPoolSize,
                workerPoolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedThreadFactory(threadNamePrefix),
                new ThreadPoolExecutor.AbortPolicy());
    }

    ValidationResult validate(ValidadorService validadorService, PrecoPayload payload)
            throws ValidationExecutionUnavailableException {
        Future<ValidationResult> validation;
        try {
            validation = workerPool.submit(() -> validadorService.validar(payload));
        } catch (RejectedExecutionException e) {
            throw new ValidationExecutionUnavailableException("SOBRECARGA", e);
        }

        try {
            return validation.get(VALIDATION_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            validation.cancel(true);
            throw new ValidationExecutionUnavailableException("TIMEOUT_VALIDACAO", e);
        } catch (InterruptedException e) {
            validation.cancel(true);
            Thread.currentThread().interrupt();
            throw new ValidationExecutionUnavailableException("VALIDACAO_INTERROMPIDA", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ValidadorUnavailableException) {
                throw new ValidationExecutionUnavailableException(cause.getMessage(), cause);
            }
            throw new RuntimeException(cause);
        }
    }

    void shutdownNow() {
        workerPool.shutdownNow();
    }

    ThreadPoolExecutor workerPool() {
        return workerPool;
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
    }
}
