package gateway.service;

import gateway.client.RepositorioClient;
import gateway.client.ValidadorClient;
import gateway.model.GatewayResult;
import gateway.model.PrecoPayload;
import gateway.model.StorageClientResult;
import gateway.model.ValidationClientResult;
import heartbeat.HeartbeatReceiver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayServiceTest {
    private static final PrecoPayload PRECO_VALIDO = new PrecoPayload("BTC", 65000.0, 1712250000L);

    private HeartbeatReceiver receiver;

    @BeforeEach
    void setUp() {
        receiver = HeartbeatReceiver.getInstance(0, "tcp");
        receiver.getValidadores().clear();
        receiver.getRepositorios().clear();
    }

    @Test
    void retornaErroQuandoNaoHaValidadorDisponivel() {
        receiver.getRepositorios().put("127.0.0.1:8082", System.currentTimeMillis());

        GatewayService service = new GatewayService(
                0,
                receiver,
                acceptingValidador(),
                acceptingRepositorio());

        GatewayResult result = service.process(PRECO_VALIDO);

        assertEquals(503, result.statusCode());
        assertEquals("SEM_VALIDADOR_DISPONIVEL", result.mensagem());
    }

    @Test
    void usaRoundRobinEntreValidadoresDisponiveis() {
        addValidadores();
        addRepositorio();
        RecordingValidadorClient validadorClient = new RecordingValidadorClient();

        GatewayService service = new GatewayService(
                0,
                receiver,
                validadorClient,
                acceptingRepositorio());

        assertEquals(200, service.process(PRECO_VALIDO).statusCode());
        assertEquals(200, service.process(PRECO_VALIDO).statusCode());

        assertEquals(List.of(8081, 8084), validadorClient.calledPorts);
        assertTrue(validadorClient.timeoutMs.stream().allMatch(timeout -> timeout > 0 && timeout <= 2000));
    }

    @Test
    void tentaProximoValidadorQuandoPrimeiroFalhaTemporariamente() {
        addValidadores();
        addRepositorio();
        RecordingValidadorClient validadorClient = new RecordingValidadorClient();
        validadorClient.failFirstCall = true;

        GatewayService service = new GatewayService(
                0,
                receiver,
                validadorClient,
                acceptingRepositorio());

        GatewayResult result = service.process(PRECO_VALIDO);

        assertEquals(200, result.statusCode());
        assertEquals(List.of(8081, 8084), validadorClient.calledPorts);
    }

    @Test
    void sobrecargaDosValidadoresEhFalhaTemporariaSemInvalidarPreco() {
        addValidadores();
        addRepositorio();

        GatewayService service = new GatewayService(
                0,
                receiver,
                timeoutAwareValidador((host, port, preco, timeoutMs) -> {
                    throw new IOException("ERRO|503|SOBRECARGA");
                }),
                acceptingRepositorio());

        GatewayResult result = service.process(PRECO_VALIDO);

        assertEquals(503, result.statusCode());
        assertEquals("FALHA_AO_VALIDAR", result.mensagem());
    }

    @Test
    void deadlineGlobalLimitaTodasAsTentativasDeValidacao() {
        addValidadores();
        addRepositorio();
        List<Long> timeouts = new ArrayList<>();

        GatewayService service = new GatewayService(
                0,
                receiver,
                timeoutAwareValidador((host, port, preco, timeoutMs) -> {
                    timeouts.add(timeoutMs);
                    if (timeouts.size() == 1) {
                        sleep(1850);
                        throw new IOException("TIMEOUT_SIMULADO");
                    }
                    return ValidationClientResult.accepted();
                }),
                acceptingRepositorio());

        GatewayResult result = service.process(PRECO_VALIDO);

        assertEquals(200, result.statusCode());
        assertEquals(2, timeouts.size());
        assertTrue(timeouts.get(0) > 1900 && timeouts.get(0) <= 2000);
        assertTrue(timeouts.get(1) > 0 && timeouts.get(1) < 500);
    }

    @Test
    void validacaoRetornaTimeoutQuandoDeadlineExpiraAntesDeNovoFailover() {
        addValidadores();
        addRepositorio();
        List<Long> timeouts = new ArrayList<>();
        long startNanos = System.nanoTime();

        GatewayService service = new GatewayService(
                0,
                receiver,
                timeoutAwareValidador((host, port, preco, timeoutMs) -> {
                    timeouts.add(timeoutMs);
                    sleep(timeoutMs + 50);
                    throw new IOException("TIMEOUT_SIMULADO");
                }),
                acceptingRepositorio());

        GatewayResult result = service.process(PRECO_VALIDO);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals(503, result.statusCode());
        assertEquals("TIMEOUT_AO_VALIDAR", result.mensagem());
        assertEquals(1, timeouts.size());
        assertTrue(elapsedMillis >= 1900 && elapsedMillis < 2600,
                "deadline deveria encerrar perto de 2s, mas levou " + elapsedMillis + "ms");
    }

    private void addValidadores() {
        receiver.getValidadores().put("127.0.0.1:8081", System.currentTimeMillis());
        receiver.getValidadores().put("127.0.0.1:8084", System.currentTimeMillis());
    }

    private void addRepositorio() {
        receiver.getRepositorios().put("127.0.0.1:8082", System.currentTimeMillis());
    }

    private static ValidadorClient acceptingValidador() {
        return (host, port, preco) -> ValidationClientResult.accepted();
    }

    private static RepositorioClient acceptingRepositorio() {
        return (host, port, preco) -> StorageClientResult.success("ARMAZENADO");
    }

    private static ValidadorClient timeoutAwareValidador(TimeoutAwareValidation validation) {
        return new ValidadorClient() {
            @Override
            public ValidationClientResult validar(String host, int port, PrecoPayload preco) throws IOException {
                return validar(host, port, preco, 2000);
            }

            @Override
            public ValidationClientResult validar(String host, int port, PrecoPayload preco, long timeoutMs)
                    throws IOException {
                return validation.validar(host, port, preco, timeoutMs);
            }
        };
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("INTERRUPTADO", e);
        }
    }

    @FunctionalInterface
    private interface TimeoutAwareValidation {
        ValidationClientResult validar(String host, int port, PrecoPayload preco, long timeoutMs) throws IOException;
    }

    private static final class RecordingValidadorClient implements ValidadorClient {
        private final List<Integer> calledPorts = new ArrayList<>();
        private final List<Long> timeoutMs = new ArrayList<>();
        private boolean failFirstCall;

        @Override
        public ValidationClientResult validar(String host, int port, PrecoPayload preco) throws IOException {
            return validar(host, port, preco, 2000);
        }

        @Override
        public ValidationClientResult validar(String host, int port, PrecoPayload preco, long timeoutMs)
                throws IOException {
            calledPorts.add(port);
            this.timeoutMs.add(timeoutMs);
            if (failFirstCall && calledPorts.size() == 1) {
                throw new IOException("FALHA_SIMULADA");
            }
            return ValidationClientResult.accepted();
        }
    }
}
