package heartbeat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import precos.Comunicacao;
import precos.HeartbeatGatewayGrpc;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HeartbeatReceiver {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static HeartbeatReceiver instance;
    private final int listenPort;
    private final String protocolo;
    private final Map<String, Long> validadores = new ConcurrentHashMap<>();
    private final Map<String, Long> repositorios = new ConcurrentHashMap<>();

    private HeartbeatReceiver(int listenPort, String protocolo) {
        this.listenPort = listenPort;
        this.protocolo = protocolo;
    }

    public static synchronized HeartbeatReceiver getInstance(int listenPort, String protocolo) {
        if (instance == null) {
            instance = new HeartbeatReceiver(listenPort, protocolo);
        }
        return instance;
    }

    public void start() {
        switch (protocolo.toLowerCase()) {
            case "udp":
                new Thread(this::listenUdp).start();
                break;
            case "tcp":
                new Thread(this::listenTcp).start();
                break;
            case "http":
                new Thread(this::listenHttp).start();
                break;
            case "grpc":
                new Thread(this::listenGrpc).start();
                break;
            default:
                System.out.println("Protocolo de heartbeat nao suportado: " + protocolo);
        }
        new Thread(this::faxina).start();
    }

    private void listenUdp() {
        try (DatagramSocket socket = new DatagramSocket(listenPort)) {
            byte[] buf = new byte[256];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                processHeartbeat(msg);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listenTcp() {
        try (ServerSocket serverSocket = new ServerSocket(listenPort)) {
            while (true) {
                try (Socket client = serverSocket.accept()) {
                    byte[] buf = client.getInputStream().readAllBytes();
                    String msg = new String(buf, StandardCharsets.UTF_8).trim();
                    processHeartbeat(msg);
                } catch (Exception e) {
                    // Ignora falhas de conexao
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listenHttp() {
        try (ServerSocket serverSocket = new ServerSocket(listenPort)) {
            while (true) {
                try (Socket client = serverSocket.accept()) {
                    HttpHeartbeatRequest request = readHttpRequest(client.getInputStream());
                    if (request.requestLine().startsWith("POST /heartbeat")) {
                        processHeartbeatJson(request.body().trim());
                    }
                    String response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
                    client.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    // Ignora falhas de conexao
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listenGrpc() {
        try {
            Server server = ServerBuilder.forPort(listenPort)
                    .addService(new HeartbeatGrpcService())
                    .build()
                    .start();

            System.out.println("[Gateway] Servidor gRPC de heartbeat iniciado na porta " + listenPort);
            Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "gateway-heartbeat-grpc-shutdown"));
            server.awaitTermination();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processHeartbeat(String msg) {
        if (msg.startsWith("HEARTBEAT:")) {
            String[] parts = msg.split(":");
            if (parts.length >= 4) {
                registerHeartbeat(parts[1], parts[2], parts[3], "Heartbeat");
            }
        }
    }

    private void processHeartbeatJson(String json) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            JsonNode hostNode = root.get("ip");
            JsonNode portNode = root.get("port");
            JsonNode tipoNode = root.get("tipo");
            if (hostNode == null || portNode == null || tipoNode == null) {
                return;
            }

            String hostVal = hostNode.asText();
            String portVal = Integer.toString(portNode.asInt());
            String tipoVal = tipoNode.asText();
            registerHeartbeat(hostVal, portVal, tipoVal, "Heartbeat HTTP");
        } catch (Exception e) {
            System.out.println("[Gateway] Heartbeat HTTP invalido: " + e.getMessage());
        }
    }

    private void processHeartbeatGrpc(Comunicacao.HeartbeatRequest request) {
        registerHeartbeat(request.getHost(), Integer.toString(request.getPort()), request.getTipo(), "Heartbeat gRPC");
    }

    private void registerHeartbeat(String host, String port, String tipoRaw, String canal) {
        if (host == null || host.isBlank() || port == null || port.isBlank() || tipoRaw == null || tipoRaw.isBlank()) {
            return;
        }

        String chave = host + ":" + port;
        String tipo = tipoRaw.toLowerCase();
        long agora = System.currentTimeMillis();
        if (tipo.contains("validador")) {
            Long anterior = validadores.put(chave, agora);
            if (anterior == null) {
                System.out.println("[Gateway] VALIDADOR REGISTRADO: " + chave);
            }
        } else if (tipo.contains("repositorio")) {
            Long anterior = repositorios.put(chave, agora);
            if (anterior == null) {
                System.out.println("[Gateway] REPOSITORIO REGISTRADO: " + chave);
            }
        } else {
            System.out.println("[Gateway] " + canal + " recebido de tipo desconhecido: " + tipo);
        }
    }

    public Map<String, Long> getValidadores() {
        return validadores;
    }

    public Map<String, Long> getRepositorios() {
        return repositorios;
    }

    private void faxina() {
        while (true) {
            long agora = System.currentTimeMillis();
            removerMortos(validadores, "VALIDADOR", agora);
            removerMortos(repositorios, "REPOSITORIO", agora);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void removerMortos(Map<String, Long> mapa, String tipo, long agora) {
        Iterator<Map.Entry<String, Long>> it = mapa.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (agora - entry.getValue() > 3000) {
                System.out.println("[Gateway] " + tipo + " REMOVIDO por timeout: " + entry.getKey());
                it.remove();
            }
        }
    }

    private HttpHeartbeatRequest readHttpRequest(java.io.InputStream inputStream) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int matched = 0;

        while (true) {
            int value = inputStream.read();
            if (value == -1) {
                throw new java.io.IOException("CABECALHO_HTTP_INCOMPLETO");
            }

            buffer.write(value);
            if ((matched == 0 && value == '\r')
                    || (matched == 1 && value == '\n')
                    || (matched == 2 && value == '\r')
                    || (matched == 3 && value == '\n')) {
                matched++;
                if (matched == 4) {
                    break;
                }
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }

        byte[] rawHeader = buffer.toByteArray();
        String headerText = new String(rawHeader, 0, rawHeader.length - 4, StandardCharsets.UTF_8);
        String[] lines = headerText.split("\r\n");
        String requestLine = lines.length == 0 ? "" : lines[0];

        int contentLength = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int separator = line.indexOf(':');
            if (separator > 0) {
                String name = line.substring(0, separator).trim().toLowerCase();
                String value = line.substring(separator + 1).trim();
                if ("content-length".equals(name)) {
                    contentLength = Integer.parseInt(value);
                }
            }
        }

        byte[] bodyBytes = inputStream.readNBytes(contentLength);
        if (bodyBytes.length != contentLength) {
            throw new java.io.IOException("BODY_HTTP_INCOMPLETO");
        }

        return new HttpHeartbeatRequest(requestLine, new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private class HeartbeatGrpcService extends HeartbeatGatewayGrpc.HeartbeatGatewayImplBase {
        @Override
        public void registrarHeartbeat(Comunicacao.HeartbeatRequest request,
                                       StreamObserver<Comunicacao.HeartbeatResponse> responseObserver) {
            processHeartbeatGrpc(request);
            Comunicacao.HeartbeatResponse response = Comunicacao.HeartbeatResponse.newBuilder()
                    .setOk(true)
                    .setMensagem("REGISTRADO")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    private record HttpHeartbeatRequest(String requestLine, String body) {
    }
}
