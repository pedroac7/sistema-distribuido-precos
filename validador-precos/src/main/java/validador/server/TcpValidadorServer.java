package validador.server;

import validador.model.*;
import validador.service.ValidadorService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpValidadorServer implements ProtocolServer {
    static final int TCP_CONNECTION_IDLE_TIMEOUT_MS = 5000;
    static final int MAX_REQUESTS_PER_CONNECTION = 1000;
    private static final int CONNECTION_WORKER_POOL_SIZE = 32;
    private static final int CONNECTION_QUEUE_CAPACITY = 64;
    private static final int VALIDATION_WORKER_POOL_SIZE = 8;
    private static final int VALIDATION_QUEUE_CAPACITY = ValidationExecutor.VALIDATION_QUEUE_CAPACITY;
    private static final int VALIDATION_RESULT_TIMEOUT_MS = ValidationExecutor.VALIDATION_RESULT_TIMEOUT_MS;

    private final int businessPort;
    private final ValidadorService validadorService;
    private final ExecutorService connectionWorkerPool;
    private final ValidationExecutor validationExecutor;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile boolean shuttingDown;

    public TcpValidadorServer(int businessPort, ValidadorService validadorService) {
        this.businessPort = businessPort;
        this.validadorService = validadorService;
        this.connectionWorkerPool = new ThreadPoolExecutor(
                CONNECTION_WORKER_POOL_SIZE,
                CONNECTION_WORKER_POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CONNECTION_QUEUE_CAPACITY),
                namedThreadFactory("validador-tcp-connection"),
                new ThreadPoolExecutor.AbortPolicy());
        this.validationExecutor = new ValidationExecutor("validador-tcp-validation", VALIDATION_WORKER_POOL_SIZE);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownWorkers, "validador-tcp-workers-shutdown"));
    }

    @Override
    public void start() {
        try (ServerSocket currentServerSocket = new ServerSocket(businessPort)) {
            serverSocket = currentServerSocket;
            System.out.println("[Validador] Servidor TCP de negocio iniciado na porta " + businessPort);
            while (!shuttingDown) {
                Socket client = currentServerSocket.accept();
                acceptConnection(client);
            }
        } catch (IOException e) {
            if (shuttingDown) {
                return;
            }
            throw new RuntimeException("Falha ao iniciar servidor TCP do validador", e);
        } finally {
            serverSocket = null;
        }
    }

    void acceptConnection(Socket client) {
        if (shuttingDown) {
            closeQuietly(client);
            return;
        }
        try {
            connectionWorkerPool.execute(() -> handleClient(client));
        } catch (RejectedExecutionException e) {
            rejectConnection(client);
        }
    }

    void handleClient(Socket client) {
        BufferedWriter writer = null;
        activeSockets.add(client);
        try (Socket socket = client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter currentWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            writer = currentWriter;
            socket.setSoTimeout(TCP_CONNECTION_IDLE_TIMEOUT_MS);
            socket.setTcpNoDelay(true);

            for (int requests = 0; requests < MAX_REQUESTS_PER_CONNECTION; requests++) {
                String request;
                try {
                    request = reader.readLine();
                } catch (SocketTimeoutException e) {
                    return;
                }

                if (request == null) {
                    return;
                }

                writeResponse(currentWriter, processRequest(request));
            }
        } catch (IOException e) {
            if (shuttingDown) {
                return;
            }
            System.out.println("[Validador] Falha TCP ao atender conexao: " + e.getMessage());
        } catch (RuntimeException e) {
            tryWriteErrorResponse(writer, "ERRO|FALHA_INTERNA");
            System.out.println("[Validador] Falha interna ao atender conexao TCP: " + e.getMessage());
        } finally {
            activeSockets.remove(client);
        }
    }

    void shutdownWorkers() {
        shuttingDown = true;
        closeServerSocket();
        for (Socket socket : activeSockets) {
            closeQuietly(socket);
        }
        connectionWorkerPool.shutdownNow();
        validationExecutor.shutdownNow();
    }

    private String processRequest(String request) {
        if (request == null || request.isBlank()) {
            return "ERRO|REQUISICAO_VAZIA";
        }

        String[] parts = request.split("\\|", -1);
        if (parts.length != 4 || !"VALIDAR".equals(parts[0])) {
            return "ERRO|FORMATO_INVALIDO";
        }

        PrecoPayload payload;
        try {
            payload = new PrecoPayload(parts[1], Double.parseDouble(parts[2]), Long.parseLong(parts[3]));
        } catch (NumberFormatException e) {
            return "ERRO|FORMATO_INVALIDO";
        }

        try {
            return formatValidationResult(validationExecutor.validate(validadorService, payload));
        } catch (ValidationExecutionUnavailableException e) {
            return "ERRO|503|" + e.getMessage();
        } catch (RuntimeException e) {
            return "ERRO|FALHA_INTERNA";
        }
    }

    private String formatValidationResult(ValidationResult validationResult) {
        if (validationResult.valid()) {
            return "OK|VALIDO";
        }
        return "ERRO|" + validationResult.mensagem();
    }

    private void writeResponse(BufferedWriter writer, String response) throws IOException {
        writer.write(response);
        writer.newLine();
        writer.flush();
    }

    private void tryWriteErrorResponse(BufferedWriter writer, String response) {
        if (writer == null) {
            return;
        }

        try {
            writeResponse(writer, response);
        } catch (IOException ignored) {
            // Ignora falha ao responder erro antes de fechar a conexao
        }
    }

    void rejectConnection(Socket client) {
        try (Socket socket = client;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(TCP_CONNECTION_IDLE_TIMEOUT_MS);
            writeResponse(writer, "ERRO|503|SOBRECARGA");
        } catch (IOException e) {
            if (!shuttingDown) {
                System.out.println("[Validador] Falha ao rejeitar conexao TCP: " + e.getMessage());
            }
        }
    }

    private void closeServerSocket() {
        ServerSocket currentServerSocket = serverSocket;
        if (currentServerSocket != null) {
            try {
                currentServerSocket.close();
            } catch (IOException ignored) {
                // Ignora falha ao fechar socket servidor durante shutdown
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Ignora falha ao fechar conexao TCP durante shutdown
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
