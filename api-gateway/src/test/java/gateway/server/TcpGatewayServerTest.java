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
import java.io.StringReader;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpGatewayServerTest {

    @Test
    void processaDuasRequisicoesValidasNaMesmaConexao() {
        AtomicInteger chamadas = new AtomicInteger();
        TcpGatewayServer server = new TcpGatewayServer(0, gatewayService(chamadas));
        try {
            StubSocket socket = new StubSocket("""
                    PRECO|BTC|65000.0|1712250000
                    PRECO|ETH|3500.0|1712250001
                    """);

            server.handleClient(socket);

            assertEquals(List.of(
                    "OK|ARMAZENADO|replicas=2",
                    "OK|ARMAZENADO|replicas=2"), responseLines(socket));
            assertEquals(2, chamadas.get());
            assertTrue(socket.closed);
        } finally {
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

    @Test
    void encerraLimpoQuandoClienteFechaConexao() {
        TcpGatewayServer server = new TcpGatewayServer(0, gatewayService(new AtomicInteger()));
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
        TcpGatewayServer server = new TcpGatewayServer(0, gatewayService(new AtomicInteger()));
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
        TcpGatewayServer server = new TcpGatewayServer(0, gatewayService(new AtomicInteger()));
        try {
            StubSocket socket = new StubSocket("");

            server.rejectConnection(socket);

            assertEquals(List.of("ERRO|503|SOBRECARGA"), responseLines(socket));
            assertTrue(socket.closed);
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

    private static List<String> responseLines(StubSocket socket) {
        return new BufferedReader(new StringReader(socket.responseText())).lines().toList();
    }

    private static final class StubSocket extends Socket {
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
        }

        private String responseText() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class TimeoutInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new SocketTimeoutException("idle");
        }
    }
}
