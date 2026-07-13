package repositorio.server;

import org.junit.jupiter.api.Test;
import repositorio.service.RepositorioService;

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
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpRepositorioServerTest {

    @Test
    void processaDuasRequisicoesValidasNaMesmaConexao() {
        TcpRepositorioServer server = new TcpRepositorioServer(0, new RepositorioService());
        try {
            StubSocket socket = new StubSocket("""
                    ARMAZENAR|BTC|65000.0|1712250000
                    ARMAZENAR|ETH|3500.0|1712250001
                    """);

            server.handleClient(socket);

            assertEquals(List.of("OK|ARMAZENADO", "OK|ARMAZENADO"), responseLines(socket));
            assertTrue(socket.closed);
        } finally {
            server.shutdownWorkers();
        }
    }

    @Test
    void processaCemRequisicoesSequenciaisNaMesmaConexao() {
        TcpRepositorioServer server = new TcpRepositorioServer(0, new RepositorioService());
        try {
            StringBuilder requests = new StringBuilder();
            IntStream.range(0, 100)
                    .forEach(i -> requests.append("ARMAZENAR|BTC|65000.0|1712250")
                            .append(String.format("%03d", i))
                            .append('\n'));
            StubSocket socket = new StubSocket(requests.toString());

            server.handleClient(socket);

            List<String> responses = responseLines(socket);
            assertEquals(100, responses.size());
            assertTrue(responses.stream().allMatch("OK|ARMAZENADO"::equals));
        } finally {
            server.shutdownWorkers();
        }
    }

    @Test
    void requisicaoInvalidaNaoFechaConexaoAntesDaValidaSeguinte() {
        TcpRepositorioServer server = new TcpRepositorioServer(0, new RepositorioService());
        try {
            StubSocket socket = new StubSocket("""
                    INVALIDA
                    ARMAZENAR|BTC|65000.0|1712250000
                    """);

            server.handleClient(socket);

            assertEquals(List.of("ERRO|FORMATO_INVALIDO", "OK|ARMAZENADO"), responseLines(socket));
        } finally {
            server.shutdownWorkers();
        }
    }

    @Test
    void encerraLimpoQuandoClienteFechaConexao() {
        TcpRepositorioServer server = new TcpRepositorioServer(0, new RepositorioService());
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
        TcpRepositorioServer server = new TcpRepositorioServer(0, new RepositorioService());
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
        TcpRepositorioServer server = new TcpRepositorioServer(0, new RepositorioService());
        try {
            StubSocket socket = new StubSocket("");

            server.rejectConnection(socket);

            assertEquals(List.of("ERRO|503|SOBRECARGA"), responseLines(socket));
            assertTrue(socket.closed);
        } finally {
            server.shutdownWorkers();
        }
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
