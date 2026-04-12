package heartbeat;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;


public class HeartbeatReceiver {
    private static HeartbeatReceiver instance;
    private final int listenPort;
    private final String protocolo;
    // Mapas separados
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
                System.out.println("HeartbeatReceiver: implemente o método gRPC no serviço do gateway.");
                break;
            default:
                System.out.println("Protocolo de heartbeat não suportado: " + protocolo);
        }
        // Faxina
        new Thread(this::faxina).start();
    }

    private void listenUdp() {
        try (DatagramSocket socket = new DatagramSocket(listenPort)) {
            byte[] buf = new byte[256];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                processHeartbeat(msg, packet.getAddress().getHostAddress());
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
                    processHeartbeat(msg, client.getInetAddress().getHostAddress());
                } catch (Exception e) {
                    // Ignora falhas de conexão
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
                    String reqStr = request.requestLine();
                    if (reqStr.startsWith("POST /heartbeat")) {
                        processHeartbeatJson(request.body().trim(), client.getInetAddress().getHostAddress());
                    }
                    // Responde HTTP 200 OK
                    String response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
                    client.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    // Ignora falhas de conexão
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processHeartbeat(String msg, String ip) {
        // Esperado: HEARTBEAT:ip:porta:tipo
        if (msg.startsWith("HEARTBEAT:")) {
            String[] parts = msg.split(":");
            if (parts.length >= 4) {
                String chave = parts[1] + ":" + parts[2];
                String tipo = parts[3].toLowerCase();
                if (tipo.contains("validador")) {
                    validadores.put(chave, System.currentTimeMillis());
                    System.out.println("[Gateway] Heartbeat recebido de VALIDADOR " + chave);
                } else if (tipo.contains("repositorio")) {
                    repositorios.put(chave, System.currentTimeMillis());
                    System.out.println("[Gateway] Heartbeat recebido de REPOSITORIO " + chave);
                } else {
                    System.out.println("[Gateway] Heartbeat recebido de tipo desconhecido: " + tipo);
                }
            }
        }
    }

    private void processHeartbeatJson(String json, String ip) {
        // Esperado: {"ip":"...","port":...,"tipo":"..."}
        String ipVal = extractJson(json, "ip");
        String portVal = extractJson(json, "port");
        String tipoVal = extractJson(json, "tipo");
        if (ipVal != null && portVal != null && tipoVal != null) {
            String chave = ipVal + ":" + portVal;
            String tipo = tipoVal.toLowerCase();
            if (tipo.contains("validador")) {
                validadores.put(chave, System.currentTimeMillis());
                System.out.println("[Gateway] Heartbeat HTTP recebido de VALIDADOR " + chave);
            } else if (tipo.contains("repositorio")) {
                repositorios.put(chave, System.currentTimeMillis());
                System.out.println("[Gateway] Heartbeat HTTP recebido de REPOSITORIO " + chave);
            } else {
                System.out.println("[Gateway] Heartbeat HTTP recebido de tipo desconhecido: " + tipo);
            }
        }
    }

    private String extractJson(String json, String key) {
        String pattern = "\\\"" + key + "\\\"\\s*:\\s*\\\"?([^\\\"]+)\\\"?";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    // Métodos para acessar as listas separadas
    public Map<String, Long> getValidadores() {
        return validadores;
    }

    public Map<String, Long> getRepositorios() {
        return repositorios;
    }

    // Faxina: remove instâncias "mortas"
    private void faxina() {
        while (true) {
            long agora = System.currentTimeMillis();
            removerMortos(validadores, "VALIDADOR", agora);
            removerMortos(repositorios, "REPOSITORIO", agora);
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void removerMortos(Map<String, Long> mapa, String tipo, long agora) {
        Iterator<Map.Entry<String, Long>> it = mapa.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (agora - entry.getValue() > 6000) {
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

    private record HttpHeartbeatRequest(String requestLine, String body) {
    }
}
