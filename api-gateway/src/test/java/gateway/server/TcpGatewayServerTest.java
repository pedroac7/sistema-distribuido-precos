package gateway.server;

import gateway.model.GatewayResult;
import gateway.model.PrecoPayload;
import gateway.service.GatewayService;
import org.junit.jupiter.api.Test;

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

class TcpGatewayServerTest {
    private static final String VALID_REQUEST = "PRECO|BTC|65000.0|1712250000\n";

    @Test
    void executoresTcpPossuemCapacidadesSeparadas() throws Exception {
        assertEquals(128, privateStaticInt("CONNECTION_WORKER_POOL_SIZE"));
        assertEquals(256, privateStaticInt("CONNECTION_QUEUE_CAPACITY"));
        assertEquals(100, privateStaticInt("REQUEST_WORKER_POOL_SIZE"));
        assertEquals(200, privateStaticInt("REQUEST_QUEUE_CAPACITY"));
    }

    @Test
    void setentaConexoesPersistentesRecebemRespostaSemInanicaoAposSexagesimaQuarta() {
        CountDownLatch releaseConnections = new CountDownLatch(1);
        AtomicInteger chamadas = new AtomicInteger();
        TcpGatewayServer server = new TcpGatewayServer(0, gatewayService(chamadas));
        try {
            List<StubSocket> sockets = new ArrayList<>();
            for (int i = 0; i < 70; i++) {
                StubSocket socket = new StubSocket(new BlockingAfterRequestInputStream(VALID_REQUEST, releaseConnections));
                sockets.add(socket);
                server.acceptConnection(socket);
            }

            for (StubSocket socket : sockets) {
                assertEquals(List.of("OK|ARMAZENADO|replicas=2"), waitForResponses(socket, 1));
                assertTrue(!socket.closed, "conexao persistente deveria continuar aberta aguardando nova linha");
            }
            assertEquals(70, chamadas.get());
        } finally {
            releaseConnections.countDown();
            server.shutdownWorkers();
        }
    }

    @Test
    void cemConexoesConseguemIniciarProcessamento() throws Exception {
        CountDownLatch releaseConnections = new CountDownLatch(1);
        CountingGatewayService service = new CountingGatewayService(100);
        TcpGatewayServer server = new TcpGatewayServer(0, service);
        try {
            for (int i = 0; i < 100; i++) {
                server.acceptConnection(new StubSocket(new BlockingAfterRequestInputStream(VALID_REQUEST, releaseConnections)));
            }

            assertTrue(service.awaitStarted());
            assertEquals(100, service.calls());
        } finally {
            releaseConnections.countDown();
            server.shutdownWorkers();
        }
    }

    @Test
    void processaCemRequisicoesSequenciaisNaMesmaConexao() {
        AtomicInteger chamadas = new AtomicInteger();
        TcpGatewayServer server = new TcpGatewayServer(0, gatewayService(chamadas));
        try {
            StringBuilder requests = new StringBuilder();
            IntStream.range(0, 100)
                    .forEach(i -> requests.append("PRECO|BTC|65000.0|1712250")
                            .append(String.format("%03d", i))
                            .append('\n'));
            StubSocket socket = new StubSocket(requests.toString());

            server.handleClient(socket);

            List<String> responses = responseLines(socket);
            assertEquals(100, responses.size());
            assertTrue(responses.stream().allMatch("OK|ARMAZENADO|replicas=2"::equals));
            assertEquals(100, chamadas.get());
        } finally {
            server.shutdownWorkers();
        }
    }

    @Test
    void ordemPorConexaoEhPreservadaERespostasEntreConexoesNaoSeMisturam() throws Exception {
        TcpGatewayServer server = new TcpGatewayServer(0, new IdentifyingGatewayService());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            StubSocket first = new StubSocket("""
                    PRECO|A1|1.0|1
                    PRECO|A2|1.0|2
                    """);
            StubSocket second = new StubSocket("""
                    PRECO|B1|1.0|1
                    PRECO|B2|1.0|2
                    """);

            Future<?> firstHandler = executor.submit(() -> server.handleClient(first));
            Future<?> secondHandler = executor.submit(() -> server.handleClient(second));

            firstHandler.get(1, TimeUnit.SECONDS);
            secondHandler.get(1, TimeUnit.SECONDS);

            assertEquals(List.of("ERRO|400|REQ_A1", "ERRO|400|REQ_A2"), responseLines(first));
            assertEquals(List.of("ERRO|400|REQ_B1", "ERRO|400|REQ_B2"), responseLines(second));
        } finally {
            executor.shutdownNow();
            server.shutdownWorkers();
        }
    }

    @Test
    void conexoesDiferentesExecutamRequisicoesConcorrentemente() throws Exception {
        BlockingGatewayService service = new BlockingGatewayService(2);
        TcpGatewayServer server = new TcpGatewayServer(0, service);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            StubSocket first = new StubSocket(VALID_REQUEST);
            StubSocket second = new StubSocket(VALID_REQUEST);

            Future<?> firstHandler = executor.submit(() -> server.handleClient(first));
            Future<?> secondHandler = executor.submit(() -> server.handleClient(second));

            assertTrue(service.awaitStarted());
            assertEquals(2, service.maxActive());
            service.release();
            firstHandler.get(1, TimeUnit.SECONDS);
            secondHandler.get(1, TimeUnit.SECONDS);
        } finally {
            service.release();
            executor.shutdownNow();
            server.shutdownWorkers();
        }
    }

    @Test
    void umaConexaoNaoExecutaDuasRequisicoesSimultaneamente() throws Exception {
        BlockingGatewayService service = new BlockingGatewayService(1);
        TcpGatewayServer server = new TcpGatewayServer(0, service);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            StubSocket socket = new StubSocket("""
                    PRECO|BTC|65000.0|1712250000
                    PRECO|ETH|3500.0|1712250001
                    """);

            Future<?> handler = executor.submit(() -> server.handleClient(socket));

            assertTrue(service.awaitStarted());
            assertEquals(1, service.calls());
            assertEquals(1, service.maxActive());
            service.release();
            handler.get(1, TimeUnit.SECONDS);

            assertEquals(2, service.calls());
            assertEquals(1, service.maxActive());
        } finally {
            service.release();
            executor.shutdownNow();
            server.shutdownWorkers();
        }
    }

    @Test
    void saturacaoDoExecutorDeRequisicoesRetorna503EConexaoContinuaUtilizavel() throws Exception {
        TcpGatewayServer server = new TcpGatewayServer(0, gatewayService(new AtomicInteger()));
        CountDownLatch releaseRequests = new CountDownLatch(1);
        try {
            ThreadPoolExecutor requestExecutor = executor(server, "requestWorkerPool");
            for (int i = 0; i < 300; i++) {
                requestExecutor.submit(() -> await(releaseRequests));
            }
            assertTrue(waitUntil(() -> requestExecutor.getActiveCount() == 100
                    && requestExecutor.getQueue().size() == 200));

            InteractiveSocket socket = InteractiveSocket.create();
            ExecutorService connectionExecutor = Executors.newSingleThreadExecutor();
            try {
                Future<?> handler = connectionExecutor.submit(() -> server.handleClient(socket));
                socket.sendRequest("PRECO|BTC|65000.0|1712250000");

                assertEquals(List.of("ERRO|503|SOBRECARGA"), waitForResponses(socket, 1));

                releaseRequests.countDown();
                assertTrue(waitUntil(() -> requestExecutor.getActiveCount() == 0
                        && requestExecutor.getQueue().isEmpty()));
                socket.sendRequest("PRECO|ETH|3500.0|1712250001");

                assertEquals(List.of("ERRO|503|SOBRECARGA", "OK|ARMAZENADO|replicas=2"),
                        waitForResponses(socket, 2));
                socket.closeInput();
                handler.get(1, TimeUnit.SECONDS);
            } finally {
                connectionExecutor.shutdownNow();
            }
        } finally {
            releaseRequests.countDown();
            server.shutdownWorkers();
        }
    }

    @Test
    void saturacaoDoExecutorDeConexoesRejeitaFechaSocketEServidorContinuaFuncionando() throws Exception {
        CountDownLatch releaseConnections = new CountDownLatch(1);
        TcpGatewayServer server = new TcpGatewayServer(0, gatewayService(new AtomicInteger()));
        try {
            ThreadPoolExecutor connectionExecutor = executor(server, "connectionWorkerPool");
            for (int i = 0; i < 384; i++) {
                connectionExecutor.submit(() -> await(releaseConnections));
            }
            assertTrue(waitUntil(() -> connectionExecutor.getActiveCount() == 128
                    && connectionExecutor.getQueue().size() == 256));

            StubSocket rejected = new StubSocket("");
            server.acceptConnection(rejected);

            assertEquals(List.of("ERRO|503|SOBRECARGA"), responseLines(rejected));
            assertTrue(rejected.closed);

            releaseConnections.countDown();
            assertTrue(waitUntil(() -> connectionExecutor.getActiveCount() == 0
                    && connectionExecutor.getQueue().isEmpty()));

            StubSocket valid = new StubSocket(VALID_REQUEST);
            server.acceptConnection(valid);

            assertEquals(List.of("OK|ARMAZENADO|replicas=2"), waitForResponses(valid, 1));
        } finally {
            releaseConnections.countDown();
            server.shutdownWorkers();
        }
    }

    @Test
    void eofETimeoutEncerramSomenteConexaoCorrespondente() {
        TcpGatewayServer server = new TcpGatewayServer(0, gatewayService(new AtomicInteger()));
        try {
            StubSocket eofSocket = new StubSocket("");
            StubSocket timeoutSocket = new StubSocket(new TimeoutInputStream());
            StubSocket validSocket = new StubSocket(VALID_REQUEST);

            server.handleClient(eofSocket);
            assertTimeoutPreemptively(Duration.ofMillis(200), () -> server.handleClient(timeoutSocket));
            server.handleClient(validSocket);

            assertTrue(eofSocket.closed);
            assertTrue(timeoutSocket.closed);
            assertEquals(List.of("OK|ARMAZENADO|replicas=2"), responseLines(validSocket));
            assertTrue(validSocket.closed);
        } finally {
            server.shutdownWorkers();
        }
    }

    @Test
    void shutdownFechaSocketsAtivosEExecutores() throws Exception {
        CountDownLatch releaseConnection = new CountDownLatch(1);
        TcpGatewayServer server = new TcpGatewayServer(0, gatewayService(new AtomicInteger()));
        StubSocket socket = new StubSocket(new BlockingAfterRequestInputStream(VALID_REQUEST, releaseConnection));

        server.acceptConnection(socket);
        assertEquals(List.of("OK|ARMAZENADO|replicas=2"), waitForResponses(socket, 1));

        server.shutdownWorkers();
        releaseConnection.countDown();

        assertTrue(socket.closed);
        assertTrue(executor(server, "connectionWorkerPool").isShutdown());
        assertTrue(executor(server, "requestWorkerPool").isShutdown());
    }

    @Test
    void requisicaoInvalidaNaoFechaConexaoAntesDaValidaSeguinte() {
        AtomicInteger chamadas = new AtomicInteger();
        TcpGatewayServer server = new TcpGatewayServer(0, gatewayService(chamadas));
        try {
            StubSocket socket = new StubSocket("""
                    INVALIDA
                    PRECO|BTC|65000.0|1712250000
                    """);

            server.handleClient(socket);

            assertEquals(List.of(
                    "ERRO|400|FORMATO_INVALIDO",
                    "OK|ARMAZENADO|replicas=2"), responseLines(socket));
            assertEquals(1, chamadas.get());
        } finally {
            server.shutdownWorkers();
        }
    }

    private static GatewayService gatewayService(AtomicInteger chamadas) {
        return new GatewayService(0, null, null, null) {
            @Override
            public GatewayResult process(PrecoPayload preco) {
                chamadas.incrementAndGet();
                return GatewayResult.success(2);
            }
        };
    }

    private static int privateStaticInt(String fieldName) throws Exception {
        Field field = TcpGatewayServer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static ThreadPoolExecutor executor(TcpGatewayServer server, String fieldName) throws Exception {
        Field field = TcpGatewayServer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(server);
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> responseLines(StubSocket socket) {
        return new BufferedReader(new StringReader(socket.responseText())).lines().toList();
    }

    private static final class CountingGatewayService extends GatewayService {
        private final CountDownLatch started;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingGatewayService(int expectedCalls) {
            super(0, null, null, null);
            this.started = new CountDownLatch(expectedCalls);
        }

        @Override
        public GatewayResult process(PrecoPayload preco) {
            calls.incrementAndGet();
            started.countDown();
            return GatewayResult.success(2);
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(1, TimeUnit.SECONDS);
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class IdentifyingGatewayService extends GatewayService {
        private IdentifyingGatewayService() {
            super(0, null, null, null);
        }

        @Override
        public GatewayResult process(PrecoPayload preco) {
            return GatewayResult.error(400, "REQ_" + preco.ativo());
        }
    }

    private static final class BlockingGatewayService extends GatewayService {
        private final CountDownLatch started;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();

        private BlockingGatewayService(int expectedStarts) {
            super(0, null, null, null);
            this.started = new CountDownLatch(expectedStarts);
        }

        @Override
        public GatewayResult process(PrecoPayload preco) {
            calls.incrementAndGet();
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            started.countDown();
            try {
                release.await();
                return GatewayResult.success(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return GatewayResult.error(503, "FALHA_INTERNA");
            } finally {
                active.decrementAndGet();
            }
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        private int calls() {
            return calls.get();
        }

        private int maxActive() {
            return maxActive.get();
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
