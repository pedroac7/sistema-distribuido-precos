package gateway.server;

import gateway.model.*;
import gateway.service.GatewayService;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class UdpGatewayServer implements ProtocolServer {
    private static final int BUFFER_SIZE = 2048;

    private final int businessPort;
    private final GatewayService gatewayService;

    public UdpGatewayServer(int businessPort, GatewayService gatewayService) {
        this.businessPort = businessPort;
        this.gatewayService = gatewayService;
    }

    @Override
    public void start() {
        try (DatagramSocket socket = new DatagramSocket(businessPort)) {
            System.out.println("[Gateway] Servidor UDP de negocio iniciado na porta " + businessPort);
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
                        "gateway-udp-" + clientPort
                );
                clientThread.start();
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor UDP do gateway", e);
        }
    }

    private void handlePacket(DatagramSocket socket, InetAddress clientAddress, int clientPort, byte[] requestData) {
        String request = new String(requestData, StandardCharsets.UTF_8).trim();
        System.out.println("[Gateway] Recebimento no gateway: " + request);

        GatewayResult result;
        try {
            result = processRequest(request);
        } catch (Exception e) {
            System.out.println("[Gateway] Falha ao processar datagrama UDP: " + e.getMessage());
            result = GatewayResult.error(503, "FALHA_INTERNA");
        }

        String response = result.toTcpResponse();
        try {
            sendResponse(socket, clientAddress, clientPort, response);
            System.out.println("[Gateway] Resposta final: " + response);
        } catch (IOException e) {
            System.out.println("[Gateway] Falha ao responder cliente UDP: " + e.getMessage());
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
        if (request == null || request.isBlank()) {
            throw new IllegalArgumentException("REQUISICAO_VAZIA");
        }

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

    private void sendResponse(DatagramSocket socket, InetAddress address, int port, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        DatagramPacket responsePacket = new DatagramPacket(responseBytes, responseBytes.length, address, port);
        synchronized (socket) {
            socket.send(responsePacket);
        }
    }
}
