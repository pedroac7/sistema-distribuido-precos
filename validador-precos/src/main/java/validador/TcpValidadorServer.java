package validador;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class TcpValidadorServer implements BusinessServer {
    private static final int SOCKET_TIMEOUT_MS = 2000;

    private final int businessPort;
    private final ValidadorService validadorService;

    public TcpValidadorServer(int businessPort, ValidadorService validadorService) {
        this.businessPort = businessPort;
        this.validadorService = validadorService;
    }

    @Override
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(businessPort)) {
            System.out.println("[Validador] Servidor TCP de negocio iniciado na porta " + businessPort);
            while (true) {
                Socket client = serverSocket.accept();
                Thread clientThread = new Thread(() -> handleClient(client), "validador-business-" + client.getPort());
                clientThread.start();
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor TCP do validador", e);
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            String request = reader.readLine();
            System.out.println("[Validador] Requisicao recebida: " + request);

            String response = processRequest(request);
            writer.write(response);
            writer.newLine();
            writer.flush();

            System.out.println("[Validador] Resposta enviada: " + response);
        } catch (Exception e) {
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
}
