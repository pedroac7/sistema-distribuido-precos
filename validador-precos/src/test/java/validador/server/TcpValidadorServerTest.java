package validador.server;

import org.junit.jupiter.api.Test;
import validador.model.PrecoPayload;
import validador.model.ValidationResult;
import validador.service.ValidadorService;
import validador.service.ValidadorUnavailableException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpValidadorServerTest {
    private static final String VALID_REQUEST = "VALIDAR|BTC|65000.0|1712250000\n";

    @Test
    void executoresTcpPossuemCapacidadesSeparadas() throws Exception {
        assertEquals(32, privateStaticInt("CONNECTION_WORKER_POOL_SIZE"));
        assertEquals(64, privateStaticInt("CONNECTION_QUEUE_CAPACITY"));
        assertEquals(8, privateStaticInt("VALIDATION_WORKER_POOL_SIZE"));
        assertEquals(40, privateStaticInt("VALIDATION_QUEUE_CAPACITY"));
        assertEquals(1500, privateStaticInt("VALIDATION_RESULT_TIMEOUT_MS"));
    }

    @Test
    void dezesseisConexoesPersistentesSaoAtendidasSemMonopolioDosValidadores() {
        CountDownLatch releaseConnections = new CountDownLatch(1);
        TcpValidadorServer server = new TcpValidadorServer(0, new FastValidadorService());
        try {
            List<StubSocket> sockets = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                StubSocket socket = new StubSocket(new BlockingAfterRequestInputStream(VALID_REQUEST, releaseConnections));
                sockets.add(socket);
                server.acceptConnection(socket);
            }

            for (StubSocket socket : sockets) {
                assertEquals(List.of("OK|VALIDO"), waitForResponses(socket, 1));
                assertTrue(!socket.closed, "conexao persistente deveria continuar aberta aguardando nova linha");
            }
        } finally {
            releaseConnections.countDown();
            server.shutdownWorkers();
        }
    }

    @Test
    void processaCemRequisicoesSequenciaisNaMesmaConexao() {
        TcpValidadorServer server = new TcpValidadorServer(0, new FastValidadorService());
        try {
            StringBuilder requests = new StringBuilder();
            IntStream.range(0, 100)
                    .forEach(i -> requests.append("VALIDAR|BTC|65000.0|1712250")
                            .append(String.format("%03d", i))
                            .append('\n'));
            StubSocket socket = new StubSocket(requests.toString());

            server.handleClient(socket);

            List<String> responses = responseLines(socket);
            assertEquals(100, responses.size());
            assertTrue(responses.stream().allMatch("OK|VALIDO"::equals));
        } finally {
            server.shutdownWorkers();
        }
    }

    @Test
    void validacoesSimultaneasNuncaUltrapassamOito() throws Exception {
        BlockingValidadorService service = new BlockingValidadorService();
        TcpValidadorServer server = new TcpValidadorServer(0, service);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                StubSocket socket = new StubSocket(VALID_REQUEST);
                futures.add(executor.submit(() -> server.handleClient(socket)));
            }

            assertTrue(service.awaitStarted(8));
            assertEquals(8, service.maxActive());
            service.release();
            for (Future<?> future : futures) {
                future.get(1, TimeUnit.SECONDS);
            }
            assertEquals(8, service.maxActive());
        } finally {
            service.release();
            executor.shutdownNow();
            server.shutdownWorkers();
        }
    }

    @Test
    void validacaoSobrecarregadaResponde503EMesmaConexaoContinuaUsavel() throws Exception {
        BlockingValidadorService service = new BlockingValidadorService();
        TcpValidadorServer server = new TcpValidadorServer(0, service);
        ExecutorService executor = Executors.newFixedThreadPool(49);
        try {
            for (int i = 0; i < 48; i++) {
                StubSocket socket = new StubSocket(VALID_REQUEST);
                executor.submit(() -> server.handleClient(socket));
            }

            ThreadPoolExecutor validationExecutor = validationExecutor(server);
            assertTrue(waitUntil(() -> validationExecutor.getActiveCount() == 8
                    && validationExecutor.getQueue().size() == 40));

            InteractiveSocket overloadedSocket = InteractiveSocket.create();
            Future<?> overloadedHandler = executor.submit(() -> server.handleClient(overloadedSocket));
            overloadedSocket.sendRequest("VALIDAR|BTC|65000.0|1712250000");

            assertEquals(List.of("ERRO|503|SOBRECARGA"), waitForResponses(overloadedSocket, 1));

            service.release();
            assertTrue(waitUntil(() -> validationExecutor.getActiveCount() == 0
                    && validationExecutor.getQueue().isEmpty()));
            overloadedSocket.sendRequest("VALIDAR|ETH|3500.0|1712250001");

            assertEquals(List.of("ERRO|503|SOBRECARGA", "OK|VALIDO"), waitForResponses(overloadedSocket, 2));
            overloadedSocket.closeInput();
            overloadedHandler.get(1, TimeUnit.SECONDS);
        } finally {
            service.release();
            executor.shutdownNow();
            server.shutdownWorkers();
        }
    }

    @Test
    void variasConexoesModeradasNaoGeramTimeoutPorExcederConexoesPersistentes() {
        CountDownLatch releaseConnections = new CountDownLatch(1);
        TcpValidadorServer server = new TcpValidadorServer(0, new FastValidadorService());
        try {
            List<StubSocket> sockets = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                StubSocket socket = new StubSocket(new BlockingAfterRequestInputStream(VALID_REQUEST, releaseConnections));
                sockets.add(socket);
                server.acceptConnection(socket);
            }

            for (StubSocket socket : sockets) {
                assertEquals(List.of("OK|VALIDO"), waitForResponses(socket, 1));
            }
        } finally {
            releaseConnections.countDown();
            server.shutdownWorkers();
        }
    }

    @Test
    void respostasDeConexoesDiferentesNaoSaoMisturadasEOrdemDaConexaoEhPreservada() throws Exception {
        TcpValidadorServer server = new TcpValidadorServer(0, new IdentifyingValidadorService());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            StubSocket first = new StubSocket("""
                    VALIDAR|A1|1.0|1
                    VALIDAR|A2|1.0|2
                    """);
            StubSocket second = new StubSocket("""
                    VALIDAR|B1|1.0|1
                    VALIDAR|B2|1.0|2
                    """);

            Future<?> firstHandler = executor.submit(() -> server.handleClient(first));
            Future<?> secondHandler = executor.submit(() -> server.handleClient(second));

            firstHandler.get(1, TimeUnit.SECONDS);
            secondHandler.get(1, TimeUnit.SECONDS);

            assertEquals(List.of("ERRO|REQ_A1", "ERRO|REQ_A2"), responseLines(first));
            assertEquals(List.of("ERRO|REQ_B1", "ERRO|REQ_B2"), responseLines(second));
        } finally {
            executor.shutdownNow();
            server.shutdownWorkers();
        }
    }

    @Test
    void requisicaoInvalidaNaoFechaConexaoAntesDaValidaSeguinte() {
        TcpValidadorServer server = new TcpValidadorServer(0, new FastValidadorService());
        try {
            StubSocket socket = new StubSocket("""
                    INVALIDA
                    VALIDAR|BTC|65000.0|1712250000
                    """);

            server.handleClient(socket);

            assertEquals(List.of("ERRO|FORMATO_INVALIDO", "OK|VALIDO"), responseLines(socket));
        } finally {
            server.shutdownWorkers();
        }
    }

    @Test
    void encerraLimpoQuandoClienteFechaConexao() {
        TcpValidadorServer server = new TcpValidadorServer(0, new FastValidadorService());
        try {
            StubSocket socket = new StubSocket("");

            server.handleClient(socket);

            assertEquals(List.of(), responseLines(socket));
            assertTrue(socket.closed);
        } finally {
            server.shutdownWorkers();
        }
    }

    @Test
    void encerraPorTimeoutDeInatividadeSemTravar() {
        TcpValidadorServer server = new TcpValidadorServer(0, new FastValidadorService());
        try {
            StubSocket socket = new StubSocket(new TimeoutInputStream());

            assertTimeoutPreemptively(Duration.ofMillis(200), () -> server.handleClient(socket));

            assertEquals(List.of(), responseLines(socket));
            assertTrue(socket.closed);
        } finally {
            server.shutdownWorkers();
        }
    }

    @Test
    void rejeitaConexaoSobrecarregadaComErro503EFechaSocket() {
        TcpValidadorServer server = new TcpValidadorServer(0, new FastValidadorService());
        try {
            StubSocket socket = new StubSocket("");

            server.rejectConnection(socket);

            assertEquals(List.of("ERRO|503|SOBRECARGA"), responseLines(socket));
            assertTrue(socket.closed);

            StubSocket validSocket = new StubSocket(VALID_REQUEST);
            server.handleClient(validSocket);

            assertEquals(List.of("OK|VALIDO"), responseLines(validSocket));
            assertTrue(validSocket.closed);
        } finally {
            server.shutdownWorkers();
        }
    }

    @Test
    void shutdownEncerraExecutoresESocketsAtivos() throws Exception {
        CountDownLatch releaseConnection = new CountDownLatch(1);
        TcpValidadorServer server = new TcpValidadorServer(0, new FastValidadorService());
        StubSocket socket = new StubSocket(new BlockingAfterRequestInputStream(VALID_REQUEST, releaseConnection));

        server.acceptConnection(socket);
        assertEquals(List.of("OK|VALIDO"), waitForResponses(socket, 1));

        server.shutdownWorkers();
        releaseConnection.countDown();

        assertTrue(socket.closed);
        assertTrue(executor(server, "connectionWorkerPool").isShutdown());
        assertTrue(validationExecutor(server).isShutdown());
    }

    @Test
    void falhaTemporariaDaValidacaoNaoMataLoopDaConexao() {
        TcpValidadorServer server = new TcpValidadorServer(0, new TemporarilyUnavailableValidadorService());
        try {
            StubSocket socket = new StubSocket("""
                    VALIDAR|BTC|65000.0|1712250000
                    VALIDAR|BTC|65000.0|1712250001
                    """);

            server.handleClient(socket);

            assertEquals(List.of("ERRO|503|VALIDACAO_INTERROMPIDA", "OK|VALIDO"), responseLines(socket));
        } finally {
            server.shutdownWorkers();
        }
    }

    private static int privateStaticInt(String fieldName) throws Exception {
        Field field = TcpValidadorServer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static ExecutorService executor(TcpValidadorServer server, String fieldName) throws Exception {
        Field field = TcpValidadorServer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ExecutorService) field.get(server);
    }

    private static ThreadPoolExecutor validationExecutor(TcpValidadorServer server) throws Exception {
        Field field = TcpValidadorServer.class.getDeclaredField("validationExecutor");
        field.setAccessible(true);
        return ((ValidationExecutor) field.get(server)).workerPool();
    }

    private static List<String> waitForResponses(StubSocket socket, int expectedCount) {
        assertTrue(waitUntil(() -> responseLines(socket).size() >= expectedCount));
        return responseLines(socket);
    }

    private static boolean waitUntil(Condition condition) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadlineNanos) {
            try {
                if (condition.met()) {
                    return true;
                }
                Thread.sleep(10);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static List<String> responseLines(StubSocket socket) {
        return new BufferedReader(new StringReader(socket.responseText())).lines().toList();
    }

    private static final class FastValidadorService extends ValidadorService {
        @Override
        public ValidationResult validar(PrecoPayload preco) {
            if (preco == null) {
                return ValidationResult.invalid("JSON_INVALIDO");
            }
            if (preco.ativo() == null || preco.ativo().isBlank()) {
                return ValidationResult.invalid("ATIVO_OBRIGATORIO");
            }
            if (preco.valor() <= 0) {
                return ValidationResult.invalid("VALOR_INVALIDO");
            }
            if (preco.timestamp() <= 0) {
                return ValidationResult.invalid("TIMESTAMP_INVALIDO");
            }
            return ValidationResult.accepted();
        }
    }

    private static final class IdentifyingValidadorService extends ValidadorService {
        @Override
        public ValidationResult validar(PrecoPayload preco) {
            return ValidationResult.invalid("REQ_" + preco.ativo());
        }
    }

    private static final class TemporarilyUnavailableValidadorService extends ValidadorService {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ValidationResult validar(PrecoPayload preco) {
            if (calls.incrementAndGet() == 1) {
                throw new ValidadorUnavailableException("VALIDACAO_INTERROMPIDA", new InterruptedException());
            }
            return ValidationResult.accepted();
        }
    }

    private static final class BlockingValidadorService extends ValidadorService {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch firstEightStarted = new CountDownLatch(8);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();

        @Override
        public ValidationResult validar(PrecoPayload preco) {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            firstEightStarted.countDown();
            try {
                release.await();
                return ValidationResult.accepted();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ValidadorUnavailableException("VALIDACAO_INTERROMPIDA", e);
            } finally {
                active.decrementAndGet();
            }
        }

        private boolean awaitStarted(int count) throws InterruptedException {
            if (count != 8) {
                throw new IllegalArgumentException("este teste espera exatamente os oito workers");
            }
            return firstEightStarted.await(1, TimeUnit.SECONDS);
        }

        private int maxActive() {
            return maxActive.get();
        }

        private void release() {
            release.countDown();
        }
    }

    private static class StubSocket extends Socket {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private boolean closed;

        private StubSocket(String requestText) {
            this(new ByteArrayInputStream(requestText.getBytes(StandardCharsets.UTF_8)));
        }

        private StubSocket(InputStream input) {
            this.input = input;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public ByteArrayOutputStream getOutputStream() {
            return output;
        }

        @Override
        public void setSoTimeout(int timeout) throws SocketException {
        }

        @Override
        public void setTcpNoDelay(boolean on) throws SocketException {
        }

        @Override
        public synchronized void close() throws IOException {
            closed = true;
            input.close();
        }

        private String responseText() {
            synchronized (output) {
                return output.toString(StandardCharsets.UTF_8);
            }
        }
    }

    private static final class InteractiveSocket extends StubSocket {
        private final PipedOutputStream requestOutput;

        private InteractiveSocket(PipedInputStream input, PipedOutputStream requestOutput) {
            super(input);
            this.requestOutput = requestOutput;
        }

        private static InteractiveSocket create() throws IOException {
            PipedInputStream input = new PipedInputStream();
            PipedOutputStream output = new PipedOutputStream(input);
            return new InteractiveSocket(input, output);
        }

        private void sendRequest(String request) throws IOException {
            requestOutput.write((request + "\n").getBytes(StandardCharsets.UTF_8));
            requestOutput.flush();
        }

        private void closeInput() throws IOException {
            requestOutput.close();
        }
    }

    private static final class BlockingAfterRequestInputStream extends InputStream {
        private final byte[] requestBytes;
        private final CountDownLatch release;
        private int offset;

        private BlockingAfterRequestInputStream(String request, CountDownLatch release) {
            this.requestBytes = request.getBytes(StandardCharsets.UTF_8);
            this.release = release;
        }

        @Override
        public int read() throws IOException {
            if (offset < requestBytes.length) {
                return requestBytes[offset++];
            }
            try {
                release.await();
                return -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("INTERRUPTADO", e);
            }
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) throws IOException {
            if (offset < requestBytes.length) {
                int bytesToCopy = Math.min(length, requestBytes.length - offset);
                System.arraycopy(requestBytes, offset, target, targetOffset, bytesToCopy);
                offset += bytesToCopy;
                return bytesToCopy;
            }
            return read();
        }

        @Override
        public void close() {
            release.countDown();
        }
    }

    private static final class TimeoutInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new SocketTimeoutException("idle");
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean met() throws Exception;
    }
}
