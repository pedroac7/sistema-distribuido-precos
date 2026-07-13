package gateway.transport;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpRawClientTest {

    @Test
    void cemRequisicoesSequenciaisReutilizamUmaConexao() throws Exception {
        try (TestTcpServer server = TestTcpServer.echo();
             TcpRawClient client = tcpClient(1000, 4, 200, 900)) {
            for (int i = 0; i < 100; i++) {
                assertEquals("OK|REQ-" + i, client.send("127.0.0.1", server.port(), "REQ-" + i));
            }

            assertEquals(1, server.connectionCount());
            assertEquals(1, client.openedConnectionCount("127.0.0.1", server.port()));
        }
    }

    @Test
    void variasThreadsUsamMesmoDestinoSemMisturarRespostas() throws Exception {
        try (TestTcpServer server = TestTcpServer.echo();
             TcpRawClient client = tcpClient(1000, 8, 500, 900)) {
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                List<Future<String>> futures = new ArrayList<>();
                for (int i = 0; i < 40; i++) {
                    int requestId = i;
                    futures.add(executor.submit(() -> client.send("127.0.0.1", server.port(), "REQ-" + requestId)));
                }

                for (int i = 0; i < futures.size(); i++) {
                    assertEquals("OK|REQ-" + i, futures.get(i).get(2, TimeUnit.SECONDS));
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void umaConexaoNaoEhUsadaSimultaneamentePorDuasChamadas() throws Exception {
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        try (TestTcpServer server = TestTcpServer.delayedEcho(150, firstRequestStarted);
             TcpRawClient client = tcpClient(1000, 1, 1000, 900)) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<String> first = executor.submit(() -> client.send("127.0.0.1", server.port(), "A"));
                assertTrue(firstRequestStarted.await(1, TimeUnit.SECONDS));
                Future<String> second = executor.submit(() -> client.send("127.0.0.1", server.port(), "B"));

                assertEquals("OK|A", first.get(2, TimeUnit.SECONDS));
                assertEquals("OK|B", second.get(2, TimeUnit.SECONDS));
                assertEquals(1, server.connectionCount());
                assertEquals(1, server.maxActiveRequests());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void conexaoComEofEhDescartadaSemRetryAutomatico() throws Exception {
        try (TestTcpServer server = TestTcpServer.closeAfterResponses(1);
             TcpRawClient client = tcpClient(1000, 2, 200, 900)) {
            assertEquals("OK|PRIMEIRA", client.send("127.0.0.1", server.port(), "PRIMEIRA"));

            assertThrows(IOException.class,
                    () -> client.send("127.0.0.1", server.port(), "SEGUNDA"));

            assertEquals(1, client.openedConnectionCount("127.0.0.1", server.port()));
            assertEquals(0, client.idleConnectionCount("127.0.0.1", server.port()));
        }
    }

    @Test
    void conexaoComTimeoutEhDescartada() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        try (TestTcpServer server = TestTcpServer.noResponse(requestStarted);
             TcpRawClient client = tcpClient(100, 2, 200, 900)) {
            IOException failure = assertThrows(IOException.class,
                    () -> client.send("127.0.0.1", server.port(), "SEM_RESPOSTA"));

            assertTrue(requestStarted.await(1, TimeUnit.SECONDS));
            assertEquals("TIMEOUT_TCP", failure.getMessage());
            assertEquals(1, client.openedConnectionCount("127.0.0.1", server.port()));
            assertEquals(0, client.idleConnectionCount("127.0.0.1", server.port()));
        }
    }

    @Test
    void timeoutDaChamadaLimitaEsperaPorResposta() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        try (TestTcpServer server = TestTcpServer.noResponse(requestStarted);
             TcpRawClient client = tcpClient(1000, 2, 200, 900)) {
            long startNanos = System.nanoTime();

            IOException failure = assertThrows(IOException.class,
                    () -> client.send("127.0.0.1", server.port(), "SEM_RESPOSTA", 120));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(requestStarted.await(1, TimeUnit.SECONDS));
            assertEquals("TIMEOUT_TCP", failure.getMessage());
            assertTrue(elapsedMillis < 700, "timeout por chamada deveria limitar a leitura TCP");
            assertEquals(0, client.idleConnectionCount("127.0.0.1", server.port()));
        }
    }

    @Test
    void numeroMaximoDeConexoesPorDestinoEhRespeitado() throws Exception {
        CountDownLatch twoRequestsStarted = new CountDownLatch(2);
        try (TestTcpServer server = TestTcpServer.delayedEcho(400, twoRequestsStarted);
             TcpRawClient client = tcpClient(1000, 2, 120, 900)) {
            ExecutorService executor = Executors.newFixedThreadPool(3);
            try {
                Future<String> first = executor.submit(() -> client.send("127.0.0.1", server.port(), "A"));
                Future<String> second = executor.submit(() -> client.send("127.0.0.1", server.port(), "B"));
                assertTrue(twoRequestsStarted.await(1, TimeUnit.SECONDS));

                IOException failure = assertThrows(IOException.class,
                        () -> client.send("127.0.0.1", server.port(), "C"));

                assertTrue(failure.getMessage().startsWith("POOL_TCP_ESGOTADO|"));
                assertEquals("OK|A", first.get(2, TimeUnit.SECONDS));
                assertEquals("OK|B", second.get(2, TimeUnit.SECONDS));
                assertEquals(2, server.connectionCount());
                assertEquals(2, client.openedConnectionCount("127.0.0.1", server.port()));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void timeoutDaChamadaLimitaEsperaPorConexaoDisponivelNoPool() throws Exception {
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        try (TestTcpServer server = TestTcpServer.delayedEcho(500, firstRequestStarted);
             TcpRawClient client = tcpClient(1000, 1, 1000, 900)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> first = executor.submit(() -> client.send("127.0.0.1", server.port(), "A"));
                assertTrue(firstRequestStarted.await(1, TimeUnit.SECONDS));

                long startNanos = System.nanoTime();
                IOException failure = assertThrows(IOException.class,
                        () -> client.send("127.0.0.1", server.port(), "B", 120));
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

                assertEquals("TIMEOUT_TCP", failure.getMessage());
                assertTrue(elapsedMillis < 700, "timeout por chamada deveria limitar a espera no pool");
                assertEquals("OK|A", first.get(2, TimeUnit.SECONDS));
                assertEquals(1, client.openedConnectionCount("127.0.0.1", server.port()));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void closeFechaConexoesEImpedeNovasAquisicoes() throws Exception {
        try (TestTcpServer server = TestTcpServer.echo();
             TcpRawClient client = tcpClient(1000, 2, 200, 900)) {
            assertEquals("OK|A", client.send("127.0.0.1", server.port(), "A"));
            assertEquals(1, client.idleConnectionCount("127.0.0.1", server.port()));

            client.close();

            assertEquals(0, client.idleConnectionCount("127.0.0.1", server.port()));
            IOException failure = assertThrows(IOException.class,
                    () -> client.send("127.0.0.1", server.port(), "B"));
            assertEquals("CLIENTE_TCP_FECHADO", failure.getMessage());
        }
    }

    @Test
    void destinosDiferentesMantemPoolsIndependentes() throws Exception {
        try (TestTcpServer firstServer = TestTcpServer.echo();
             TestTcpServer secondServer = TestTcpServer.echo();
             TcpRawClient client = tcpClient(1000, 1, 200, 900)) {
            assertEquals("OK|A", client.send("127.0.0.1", firstServer.port(), "A"));
            assertEquals("OK|B", client.send("127.0.0.1", secondServer.port(), "B"));

            assertEquals(1, client.openedConnectionCount("127.0.0.1", firstServer.port()));
            assertEquals(1, client.openedConnectionCount("127.0.0.1", secondServer.port()));
            assertEquals(1, firstServer.connectionCount());
            assertEquals(1, secondServer.connectionCount());
        }
    }

    @Test
    void conexaoEhAposentadaAoAtingirLimiteDeRequisicoesDoCliente() throws Exception {
        try (TestTcpServer server = TestTcpServer.echo();
             TcpRawClient client = tcpClient(1000, 2, 200, 2)) {
            assertEquals("OK|A", client.send("127.0.0.1", server.port(), "A"));
            assertEquals("OK|B", client.send("127.0.0.1", server.port(), "B"));
            assertEquals("OK|C", client.send("127.0.0.1", server.port(), "C"));

            assertEquals(2, client.openedConnectionCount("127.0.0.1", server.port()));
        }
    }

    private static TcpRawClient tcpClient(int timeoutMs,
                                         int maxConnectionsPerDestination,
                                         int poolAcquireTimeoutMs,
                                         int maxRequestsPerConnection) {
        return new TcpRawClient(
                timeoutMs,
                maxConnectionsPerDestination,
                poolAcquireTimeoutMs,
                maxRequestsPerConnection,
                Clock.systemUTC());
    }

    private static final class TestTcpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread acceptThread;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicInteger connectionCount = new AtomicInteger();
        private final AtomicInteger activeRequests = new AtomicInteger();
        private final AtomicInteger maxActiveRequests = new AtomicInteger();
        private final int responseDelayMs;
        private final int closeAfterResponses;
        private final boolean noResponse;
        private final CountDownLatch requestStartedLatch;
        private volatile boolean running = true;

        private TestTcpServer(int responseDelayMs,
                              int closeAfterResponses,
                              boolean noResponse,
                              CountDownLatch requestStartedLatch) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.responseDelayMs = responseDelayMs;
            this.closeAfterResponses = closeAfterResponses;
            this.noResponse = noResponse;
            this.requestStartedLatch = requestStartedLatch;
            this.acceptThread = new Thread(this::acceptLoop, "tcp-raw-client-test-accept");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        private static TestTcpServer echo() throws IOException {
            return new TestTcpServer(0, 0, false, new CountDownLatch(0));
        }

        private static TestTcpServer delayedEcho(int responseDelayMs, CountDownLatch requestStartedLatch) throws IOException {
            return new TestTcpServer(responseDelayMs, 0, false, requestStartedLatch);
        }

        private static TestTcpServer closeAfterResponses(int closeAfterResponses) throws IOException {
            return new TestTcpServer(0, closeAfterResponses, false, new CountDownLatch(0));
        }

        private static TestTcpServer noResponse(CountDownLatch requestStartedLatch) throws IOException {
            return new TestTcpServer(0, 0, true, requestStartedLatch);
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private int connectionCount() {
            return connectionCount.get();
        }

        private int maxActiveRequests() {
            return maxActiveRequests.get();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    sockets.add(socket);
                    connectionCount.incrementAndGet();
                    Thread handler = new Thread(() -> handle(socket), "tcp-raw-client-test-handler");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    if (running) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        private void handle(Socket socket) {
            try (Socket currentSocket = socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(currentSocket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(currentSocket.getOutputStream(), StandardCharsets.UTF_8))) {
                int responses = 0;
                String request;
                while ((request = reader.readLine()) != null) {
                    int active = activeRequests.incrementAndGet();
                    maxActiveRequests.accumulateAndGet(active, Math::max);
                    requestStartedLatch.countDown();
                    try {
                        if (noResponse) {
                            sleep(500);
                            return;
                        }
                        sleep(responseDelayMs);
                        writer.write("OK|" + request);
                        writer.newLine();
                        writer.flush();
                        responses++;
                        if (closeAfterResponses > 0 && responses >= closeAfterResponses) {
                            return;
                        }
                    } finally {
                        activeRequests.decrementAndGet();
                    }
                }
            } catch (IOException ignored) {
                // Cliente fechou a conexao durante o teste
            } finally {
                sockets.remove(socket);
            }
        }

        private static void sleep(int millis) {
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() throws IOException {
            running = false;
            serverSocket.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }
}
