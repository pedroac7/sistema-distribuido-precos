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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TcpGatewayServer implements ProtocolServer {
    private static final int SOCKET_TIMEOUT_MS = 5000;
    private static final int WORKER_POOL_SIZE = 64;

    private final int businessPort;
    private final GatewayService gatewayService;
    private final ExecutorService workerPool;

    public TcpGatewayServer(int businessPort, GatewayService gatewayService) {
        this.businessPort = businessPort;
        this.gatewayService = gatewayService;
        this.workerPool = Executors.newFixedThreadPool(WORKER_POOL_SIZE);
        Runtime.getRuntime().addShutdownHook(new Thread(workerPool::shutdown, "gateway-tcp-workers-shutdown"));
    }

    @Override
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(businessPort)) {
            System.out.println("[Gateway] Servidor TCP de negocio iniciado na porta " + businessPort);
            while (true) {
                Socket client = serverSocket.accept();
                workerPool.execute(() -> handleClient(client));
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor TCP do gateway", e);
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
            if (request.isBlank()) {
                writeResponse(writer, GatewayResult.error(400, "REQUISICAO_VAZIA").toTcpResponse());
                return;
            }

            GatewayResult result = processRequest(request);
            writeResponse(currentWriter, result.toTcpResponse());
        } catch (Exception e) {
            tryWriteErrorResponse(writer, GatewayResult.error(503, "FALHA_INTERNA").toTcpResponse());
            System.out.println("[Gateway] Falha ao atender cliente: " + e.getMessage());
        }
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

}
