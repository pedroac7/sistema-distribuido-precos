package validador.server;

import validador.model.*;
import validador.service.ValidadorService;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class UdpValidadorServer implements ProtocolServer {
    private static final int BUFFER_SIZE = 2048;
    private static final int PACKET_WORKER_POOL_SIZE = 64;
    private static final int PACKET_QUEUE_CAPACITY = 256;
    private static final int VALIDATION_WORKER_POOL_SIZE = ValidationExecutor.VALIDATION_WORKER_POOL_SIZE;
    private static final int VALIDATION_QUEUE_CAPACITY = ValidationExecutor.VALIDATION_QUEUE_CAPACITY;
    private static final int VALIDATION_RESULT_TIMEOUT_MS = ValidationExecutor.VALIDATION_RESULT_TIMEOUT_MS;

    private final int businessPort;
    private final ValidadorService validadorService;
    private final ExecutorService packetWorkerPool;
    private final ValidationExecutor validationExecutor;

    public UdpValidadorServer(int businessPort, ValidadorService validadorService) {
        this.businessPort = businessPort;
        this.validadorService = validadorService;
        this.packetWorkerPool = new ThreadPoolExecutor(
                PACKET_WORKER_POOL_SIZE,
                PACKET_WORKER_POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PACKET_QUEUE_CAPACITY),
                namedThreadFactory("validador-udp-packet"),
                new ThreadPoolExecutor.AbortPolicy());
        this.validationExecutor = new ValidationExecutor("validador-udp-validation");
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownWorkers, "validador-udp-workers-shutdown"));
    }

    @Override
    public void start() {
        try (DatagramSocket socket = new DatagramSocket(businessPort)) {
            System.out.println("[Validador] Servidor UDP de negocio iniciado na porta " + businessPort);
            while (true) {
                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket requestPacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(requestPacket);

                byte[] requestData = Arrays.copyOfRange(
                        requestPacket.getData(),
                        requestPacket.getOffset(),
                        requestPacket.getOffset() + requestPacket.getLength()
                );
                InetAddress clientAddress = requestPacket.getAddress();
                int clientPort = requestPacket.getPort();

                try {
                    packetWorkerPool.execute(() -> handlePacket(socket, clientAddress, clientPort, requestData));
                } catch (RejectedExecutionException e) {
                    sendResponse(socket, clientAddress, clientPort, "ERRO|503|SOBRECARGA");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor UDP do validador", e);
        }
    }

    void shutdownWorkers() {
        packetWorkerPool.shutdownNow();
        validationExecutor.shutdownNow();
    }

    private void handlePacket(DatagramSocket socket, InetAddress clientAddress, int clientPort, byte[] requestData) {
        String request = new String(requestData, StandardCharsets.UTF_8).trim();
        String response = processRequest(request);
        try {
            sendResponse(socket, clientAddress, clientPort, response);
        } catch (IOException e) {
            System.out.println("[Validador] Falha ao responder datagrama UDP: " + e.getMessage());
        }
    }

    private String processRequest(String request) {
        if (request == null || request.isBlank()) {
            return "ERRO|REQUISICAO_VAZIA";
        }

        String[] parts = request.split("\\|", -1);
        if (parts.length != 4 || !"VALIDAR".equals(parts[0])) {
            return "ERRO|FORMATO_INVALIDO";
        }

        try {
            PrecoPayload payload = new PrecoPayload(parts[1], Double.parseDouble(parts[2]), Long.parseLong(parts[3]));
            ValidationResult validationResult = validationExecutor.validate(validadorService, payload);
            if (validationResult.valid()) {
                return "OK|VALIDO";
            }
            return "ERRO|" + validationResult.mensagem();
        } catch (ValidationExecutionUnavailableException e) {
            return "ERRO|503|" + e.getMessage();
        } catch (NumberFormatException e) {
            return "ERRO|FORMATO_INVALIDO";
        } catch (RuntimeException e) {
            return "ERRO|FALHA_INTERNA";
        }
    }

    private void sendResponse(DatagramSocket socket, InetAddress address, int port, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        DatagramPacket responsePacket = new DatagramPacket(responseBytes, responseBytes.length, address, port);
        synchronized (socket) {
            socket.send(responsePacket);
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
    }
}
