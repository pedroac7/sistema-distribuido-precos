package heartbeat;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import java.io.PrintWriter;

// Para gRPC, é necessário implementar o stub gerado pelo .proto
// Aqui, deixamos um método stub para integração posterior
public class HeartbeatSender implements Runnable {
    private final String gatewayHost;
    private final int gatewayPort;
    private final int localPort;
    private final String protocolo;
    private final String tipoEntidade; // "repositorio" ou "validador"
    private volatile boolean running = true;

    public HeartbeatSender(String gatewayHost, int gatewayPort, int localPort, String protocolo, String tipoEntidade) {
        this.gatewayHost = gatewayHost;
        this.gatewayPort = gatewayPort;
        this.localPort = localPort;
        this.protocolo = protocolo;
        this.tipoEntidade = tipoEntidade;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        try {
            switch (protocolo.toLowerCase()) {
                case "udp":
                    sendUdpHeartbeat();
                    break;
                case "tcp":
                    sendTcpHeartbeat();
                    break;
                case "http":
                    sendHttpHeartbeat();
                    break;
                case "grpc":
                    sendGrpcHeartbeat();
                    break;
                default:
                    System.out.println("Protocolo de heartbeat não suportado: " + protocolo);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendUdpHeartbeat() throws Exception {
        DatagramSocket socket = new DatagramSocket();
        while (running) {
            String msg = "HEARTBEAT:" + InetAddress.getLocalHost().getHostAddress() + ":" + localPort + ":" + tipoEntidade;
            byte[] buf = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(buf, buf.length, InetAddress.getByName(gatewayHost), gatewayPort);
            socket.send(packet);
            Thread.sleep(2000);
        }
        socket.close();
    }

    private void sendTcpHeartbeat() throws Exception {
        while (running) {
            try (Socket socket = new Socket(gatewayHost, gatewayPort)) {
                String msg = "HEARTBEAT:" + InetAddress.getLocalHost().getHostAddress() + ":" + localPort + ":" + tipoEntidade + "\n";
                socket.getOutputStream().write(msg.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                // Ignora falhas de conexão
            }
            Thread.sleep(2000);
        }
    }

    private void sendHttpHeartbeat() throws Exception {
        while (running) {
            try (Socket socket = new Socket(gatewayHost, gatewayPort)) {
                String body = "{\"ip\":\"" + InetAddress.getLocalHost().getHostAddress() + "\",\"port\":" + localPort + ",\"tipo\":\"" + tipoEntidade + "\"}";
                String request = "POST /heartbeat HTTP/1.1\r\n" +
                        "Host: " + gatewayHost + "\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "Connection: close\r\n\r\n" +
                        body;
                OutputStream os = socket.getOutputStream();
                PrintWriter pw = new PrintWriter(os, true);
                pw.print(request);
                pw.flush();
            } catch (Exception e) {
                // Ignora falhas de conexão
            }
            Thread.sleep(2000);
        }
    }

    private void sendGrpcHeartbeat() {
        // Aqui você deve implementar a chamada gRPC para o método Heartbeat do Gateway
        // Exemplo (pseudo-código):
        // while (running) {
        //     HeartbeatRequest req = HeartbeatRequest.newBuilder()
        //         .setIp(InetAddress.getLocalHost().getHostAddress())
        //         .setPort(localPort)
        //         .setTipo(tipoEntidade)
        //         .build();
        //     gatewayStub.heartbeat(req);
        //     Thread.sleep(2000);
        // }
        System.out.println("Heartbeat gRPC: implementar integração com stub gerado pelo .proto");
    }
}
