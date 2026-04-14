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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TcpValidadorServer implements ProtocolServer {
    private static final int SOCKET_TIMEOUT_MS = 5000;
    private static final int WORKER_POOL_SIZE = 64;

    private final int businessPort;
    private final ValidadorService validadorService;
    private final ExecutorService workerPool;

    public TcpValidadorServer(int businessPort, ValidadorService validadorService) {
        this.businessPort = businessPort;
        this.validadorService = validadorService;
        this.workerPool = Executors.newFixedThreadPool(WORKER_POOL_SIZE);
        Runtime.getRuntime().addShutdownHook(new Thread(workerPool::shutdown, "validador-tcp-workers-shutdown"));
    }

    @Override
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(businessPort)) {
            System.out.println("[Validador] Servidor TCP de negocio iniciado na porta " + businessPort);
            while (true) {
                Socket client = serverSocket.accept();
                workerPool.execute(() -> handleClient(client));
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor TCP do validador", e);
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
            System.out.println("[Validador] Falha ao atender conexao: " + e.getMessage());
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
            ValidationResult validationResult = validadorService.validar(payload);
            if (validationResult.valid()) {
                return "OK|VALIDO";
            }
            return "ERRO|" + validationResult.mensagem();
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
