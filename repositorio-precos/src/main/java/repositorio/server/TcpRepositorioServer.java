package repositorio.server;

import repositorio.model.*;
import repositorio.service.RepositorioService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class TcpRepositorioServer implements ProtocolServer {
    static final int TCP_CONNECTION_IDLE_TIMEOUT_MS = 5000;
    static final int MAX_REQUESTS_PER_CONNECTION = 1000;
    private static final int WORKER_POOL_SIZE = 64;
    private static final int WORK_QUEUE_CAPACITY = 256;

    private final int businessPort;
    private final RepositorioService repositorioService;
    private final ExecutorService workerPool;

    public TcpRepositorioServer(int businessPort, RepositorioService repositorioService) {
        this.businessPort = businessPort;
        this.repositorioService = repositorioService;
        this.workerPool = new ThreadPoolExecutor(
                WORKER_POOL_SIZE,
                WORKER_POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY),
                new ThreadPoolExecutor.AbortPolicy());
        Runtime.getRuntime().addShutdownHook(new Thread(workerPool::shutdown, "repositorio-tcp-workers-shutdown"));
    }

    @Override
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(businessPort)) {
            System.out.println("[Repositorio] Servidor TCP de negocio iniciado na porta " + businessPort);
            while (true) {
                Socket client = serverSocket.accept();
                try {
                    workerPool.execute(() -> handleClient(client));
                } catch (RejectedExecutionException e) {
                    rejectConnection(client);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor TCP do repositorio", e);
        }
    }

    void handleClient(Socket client) {
        BufferedWriter writer = null;
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
            System.out.println("[Repositorio] Falha TCP ao atender conexao: " + e.getMessage());
        } catch (RuntimeException e) {
            tryWriteErrorResponse(writer, "ERRO|FALHA_INTERNA");
            System.out.println("[Repositorio] Falha interna ao atender conexao TCP: " + e.getMessage());
        }
    }

    void shutdownWorkers() {
        workerPool.shutdownNow();
    }

    private String processRequest(String request) {
        if (request == null || request.isBlank()) {
            return "ERRO|REQUISICAO_VAZIA";
        }

        String[] parts = request.split("\\|", -1);
        if (parts.length != 4 || !"ARMAZENAR".equals(parts[0])) {
            return "ERRO|FORMATO_INVALIDO";
        }

        try {
            PrecoPayload payload = new PrecoPayload(parts[1], Double.parseDouble(parts[2]), Long.parseLong(parts[3]));
            StorageResult storageResult = repositorioService.armazenar(payload);
            if (storageResult.success()) {
                return "OK|ARMAZENADO";
            }
            return "ERRO|" + storageResult.mensagem();
        } catch (NumberFormatException e) {
            return "ERRO|FORMATO_INVALIDO";
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
            writeResponse(writer, "ERRO|503|SOBRECARGA");
        } catch (IOException e) {
            System.out.println("[Repositorio] Falha ao rejeitar conexao TCP: " + e.getMessage());
        }
    }
}
