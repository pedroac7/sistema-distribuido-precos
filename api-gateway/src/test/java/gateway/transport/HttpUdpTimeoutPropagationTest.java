package gateway.transport;

import gateway.model.PrecoPayload;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpUdpTimeoutPropagationTest {

    @Test
    void httpUsaTimeoutRestanteDaChamadaAoAguardarResposta() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        try (NoResponseHttpServer server = new NoResponseHttpServer(requestReceived)) {
            HttpRawClient client = new HttpRawClient(1000);
            long startNanos = System.nanoTime();

            IOException failure = assertThrows(IOException.class,
                    () -> client.postJson("127.0.0.1", server.port(), "/validar",
                            new PrecoPayload("BTC", 65000.0, 1712250000L), 120));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(requestReceived.await(1, TimeUnit.SECONDS));
            assertEquals("TIMEOUT_HTTP", failure.getMessage());
            assertTrue(elapsedMillis < 700, "timeout restante deveria limitar a espera HTTP");
        }
    }

    @Test
    void udpUsaTimeoutRestanteDaChamadaAoAguardarResposta() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        try (NoResponseUdpServer server = new NoResponseUdpServer(requestReceived)) {
            UdpRawClient client = new UdpRawClient(1000);
            long startNanos = System.nanoTime();

            IOException failure = assertThrows(IOException.class,
                    () -> client.send("127.0.0.1", server.port(), "VALIDAR|BTC|65000.0|1712250000", 120));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(requestReceived.await(1, TimeUnit.SECONDS));
            assertEquals("TIMEOUT_UDP", failure.getMessage());
            assertTrue(elapsedMillis < 700, "timeout restante deveria limitar a espera UDP");
        }
    }

    private static final class NoResponseHttpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final CountDownLatch requestReceived;
        private volatile boolean running = true;

        private NoResponseHttpServer(CountDownLatch requestReceived) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.requestReceived = requestReceived;
            this.thread = new Thread(this::acceptLoop, "http-timeout-test-server");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread handler = new Thread(() -> handle(socket), "http-timeout-test-handler");
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
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         currentSocket.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    requestReceived.countDown();
                }
            } catch (IOException ignored) {
                // Cliente fechou a conexao apos timeout
            }
        }

        @Override
        public void close() throws IOException {
            running = false;
            serverSocket.close();
        }
    }

    private static final class NoResponseUdpServer implements AutoCloseable {
        private final DatagramSocket socket;
        private final Thread thread;
        private final CountDownLatch requestReceived;
        private volatile boolean running = true;

        private NoResponseUdpServer(CountDownLatch requestReceived) throws SocketException {
            this.socket = new DatagramSocket(0);
            this.requestReceived = requestReceived;
            this.thread = new Thread(this::receiveLoop, "udp-timeout-test-server");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private int port() {
            return socket.getLocalPort();
        }

        private void receiveLoop() {
            byte[] buffer = new byte[2048];
            while (running) {
                try {
                    socket.receive(new DatagramPacket(buffer, buffer.length));
                    requestReceived.countDown();
                } catch (IOException e) {
                    if (running) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        @Override
        public void close() {
            running = false;
            socket.close();
        }
    }
}
