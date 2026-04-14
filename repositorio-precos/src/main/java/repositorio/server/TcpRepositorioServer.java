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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TcpRepositorioServer implements ProtocolServer {
    private static final int SOCKET_TIMEOUT_MS = 5000;
    private static final int WORKER_POOL_SIZE = 64;

    private final int businessPort;
    private final RepositorioService repositorioService;
    private final ExecutorService workerPool;

    public TcpRepositorioServer(int businessPort, RepositorioService repositorioService) {
        this.businessPort = businessPort;
        this.repositorioService = repositorioService;
        this.workerPool = Executors.newFixedThreadPool(WORKER_POOL_SIZE);
        Runtime.getRuntime().addShutdownHook(new Thread(workerPool::shutdown, "repositorio-tcp-workers-shutdown"));
    }

    @Override
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(businessPort)) {
            System.out.println("[Repositorio] Servidor TCP de negocio iniciado na porta " + businessPort);
            while (true) {
                Socket client = serverSocket.accept();
                workerPool.execute(() -> handleClient(client));
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor TCP do repositorio", e);
        }
    }

    private void handleClient(Socket client) {
        BufferedWriter writer = null;
        try (Socket socket = client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter currentWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            writer = currentWriter;
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.setTcpNoDelay(true);

            String request = reader.readLine();
            if (request == null) {
                return;
            }

            String response = processRequest(request);
            currentWriter.write(response);
            currentWriter.newLine();
            currentWriter.flush();
        } catch (Exception e) {
            tryWriteErrorResponse(writer, "ERRO|FALHA_INTERNA");
            System.out.println("[Repositorio] Falha ao atender conexao: " + e.getMessage());
        }
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

    private void tryWriteErrorResponse(BufferedWriter writer, String response) {
        if (writer == null) {
            return;
        }

        try {
            writer.write(response);
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            // Ignora falha ao responder erro antes de fechar a conexao
        }
    }
}
