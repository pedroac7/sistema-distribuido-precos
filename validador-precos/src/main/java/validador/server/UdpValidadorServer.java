package validador.server;

import validador.model.*;
import validador.service.ValidadorService;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class UdpValidadorServer implements ProtocolServer {
    private static final int BUFFER_SIZE = 2048;

    private final int businessPort;
    private final ValidadorService validadorService;

    public UdpValidadorServer(int businessPort, ValidadorService validadorService) {
        this.businessPort = businessPort;
        this.validadorService = validadorService;
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

                Thread clientThread = new Thread(
                        () -> handlePacket(socket, clientAddress, clientPort, requestData),
                        "validador-udp-" + clientPort
                );
                clientThread.start();
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor UDP do validador", e);
        }
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
            ValidationResult validationResult = validadorService.validar(payload);
            if (validationResult.valid()) {
                return "OK|VALIDO";
            }
            return "ERRO|" + validationResult.mensagem();
        } catch (NumberFormatException e) {
            return "ERRO|FORMATO_INVALIDO";
        }
    }

    private void sendResponse(DatagramSocket socket, InetAddress address, int port, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        DatagramPacket responsePacket = new DatagramPacket(responseBytes, responseBytes.length, address, port);
        synchronized (socket) {
            socket.send(responsePacket);
        }
    }
}
