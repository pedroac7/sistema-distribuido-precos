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

public class TcpRepositorioServer implements ProtocolServer {
    private static final int SOCKET_TIMEOUT_MS = 2000;

    private final int businessPort;
    private final RepositorioService repositorioService;

    public TcpRepositorioServer(int businessPort, RepositorioService repositorioService) {
        this.businessPort = businessPort;
        this.repositorioService = repositorioService;
    }

    @Override
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(businessPort)) {
            System.out.println("[Repositorio] Servidor TCP de negocio iniciado na porta " + businessPort);
            while (true) {
                Socket client = serverSocket.accept();
                Thread clientThread = new Thread(() -> handleClient(client), "repositorio-business-" + client.getPort());
                clientThread.start();
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor TCP do repositorio", e);
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            String request = reader.readLine();
            System.out.println("[Repositorio] Requisicao recebida: " + request);

            String response = processRequest(request);
            writer.write(response);
            writer.newLine();
            writer.flush();

            System.out.println("[Repositorio] Resposta enviada: " + response);
        } catch (Exception e) {
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
}
