package gateway.server;

import gateway.model.*;
import gateway.service.GatewayService;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpGatewayServer implements ProtocolServer {
    static final int TCP_CONNECTION_IDLE_TIMEOUT_MS = 5000;
    static final int MAX_REQUESTS_PER_CONNECTION = 1000;
    private static final int CONNECTION_WORKER_POOL_SIZE = 128;
    private static final int CONNECTION_QUEUE_CAPACITY = 256;
    private static final int REQUEST_WORKER_POOL_SIZE = 100;
    private static final int REQUEST_QUEUE_CAPACITY = 200;

    private final int businessPort;
    private final GatewayService gatewayService;
    private final ExecutorService connectionWorkerPool;
    private final ExecutorService requestWorkerPool;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile boolean shuttingDown;

    public TcpGatewayServer(int businessPort, GatewayService gatewayService) {
        this.businessPort = businessPort;
        this.gatewayService = gatewayService;
        this.connectionWorkerPool = new ThreadPoolExecutor(
                CONNECTION_WORKER_POOL_SIZE,
                CONNECTION_WORKER_POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CONNECTION_QUEUE_CAPACITY),
                namedThreadFactory("gateway-tcp-connection"),
                new ThreadPoolExecutor.AbortPolicy());
        this.requestWorkerPool = new ThreadPoolExecutor(
                REQUEST_WORKER_POOL_SIZE,
                REQUEST_WORKER_POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(REQUEST_QUEUE_CAPACITY),
                namedThreadFactory("gateway-tcp-request"),
                new ThreadPoolExecutor.AbortPolicy());
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownWorkers, "gateway-tcp-workers-shutdown"));
    }

    @Override
    public void start() {
        try (ServerSocket currentServerSocket = new ServerSocket(businessPort)) {
            serverSocket = currentServerSocket;
            System.out.println("[Gateway] Servidor TCP de negocio iniciado na porta " + businessPort);
            while (!shuttingDown) {
                Socket client = currentServerSocket.accept();
                acceptConnection(client);
            }
        } catch (IOException e) {
            if (shuttingDown) {
                return;
            }
            throw new RuntimeException("Falha ao iniciar servidor TCP do gateway", e);
        } finally {
            serverSocket = null;
        }
    }

    void acceptConnection(Socket client) {
        if (shuttingDown) {
            closeQuietly(client);
            return;
        }

        activeSockets.add(client);
        try {
            connectionWorkerPool.execute(() -> handleClient(client));
        } catch (RejectedExecutionException e) {
            activeSockets.remove(client);
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

                writeResponse(currentWriter, processRequestAsync(request));
            }
        } catch (IOException e) {
            if (shuttingDown) {
                return;
            }
            System.out.println("[Gateway] Falha TCP ao atender cliente: " + e.getMessage());
        } catch (RuntimeException e) {
            tryWriteErrorResponse(writer, GatewayResult.error(503, "FALHA_INTERNA").toTcpResponse());
            System.out.println("[Gateway] Falha interna ao atender cliente TCP: " + e.getMessage());
        } finally {
            activeSockets.remove(client);
        }
    }

    void shutdownWorkers() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        closeServerSocket();
        for (Socket socket : activeSockets) {
            closeQuietly(socket);
        }
        activeSockets.clear();
        connectionWorkerPool.shutdownNow();
        requestWorkerPool.shutdownNow();
    }

    private String processRequestAsync(String request) {
        Future<String> response;
        try {
            response = requestWorkerPool.submit(() -> processRequestLine(request));
        } catch (RejectedExecutionException e) {
            return GatewayResult.error(503, "SOBRECARGA").toTcpResponse();
        }

        try {
            return response.get();
        } catch (InterruptedException e) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            return GatewayResult.error(503, "FALHA_INTERNA").toTcpResponse();
        } catch (ExecutionException e) {
            return GatewayResult.error(503, "FALHA_INTERNA").toTcpResponse();
        }
    }

    private String processRequestLine(String request) {
        GatewayResult result = request.isBlank()
                ? GatewayResult.error(400, "REQUISICAO_VAZIA")
                : processRequest(request);
        return result.toTcpResponse();
    }

    private GatewayResult processRequest(String request) {
        PrecoPayload preco;
        try {
            preco = parseClientRequest(request);
        } catch (IllegalArgumentException e) {
            return GatewayResult.error(400, e.getMessage());
        }

        return gatewayService.process(preco);
    }

    private PrecoPayload parseClientRequest(String request) {
        String[] parts = request.split("\\|", -1);
        if (parts.length != 4 || !"PRECO".equals(parts[0])) {
            throw new IllegalArgumentException("FORMATO_INVALIDO");
        }

        try {
            double valor = Double.parseDouble(parts[2]);
            long timestamp = Long.parseLong(parts[3]);
            return new PrecoPayload(parts[1], valor, timestamp);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("FORMATO_INVALIDO");
        }
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
            writeResponse(writer, GatewayResult.error(503, "SOBRECARGA").toTcpResponse());
        } catch (IOException e) {
            if (!shuttingDown) {
                System.out.println("[Gateway] Falha ao rejeitar conexao TCP: " + e.getMessage());
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
