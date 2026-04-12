package heartbeat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import precos.Comunicacao;
import precos.HeartbeatGatewayGrpc;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class HeartbeatSender implements Runnable {
    private final String gatewayHost;
    private final int gatewayPort;
    private final int servicePort;
    private final String protocolo;
    private final String tipoEntidade;
    private volatile boolean running = true;

    public HeartbeatSender(String gatewayHost, int gatewayPort, int servicePort, String protocolo, String tipoEntidade) {
        this.gatewayHost = gatewayHost;
        this.gatewayPort = gatewayPort;
        this.servicePort = servicePort;
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
                    System.out.println("Protocolo de heartbeat nao suportado: " + protocolo);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendUdpHeartbeat() throws Exception {
        DatagramSocket socket = new DatagramSocket();
        while (running) {
            String msg = "HEARTBEAT:" + InetAddress.getLocalHost().getHostAddress() + ":" + servicePort + ":" + tipoEntidade;
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
                String msg = "HEARTBEAT:" + InetAddress.getLocalHost().getHostAddress() + ":" + servicePort + ":" + tipoEntidade + "\n";
                socket.getOutputStream().write(msg.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                // Ignora falhas de conexao
            }
            Thread.sleep(2000);
        }
    }

    private void sendHttpHeartbeat() throws Exception {
        while (running) {
            try (Socket socket = new Socket(gatewayHost, gatewayPort)) {
                String body = "{\"ip\":\"" + InetAddress.getLocalHost().getHostAddress() + "\",\"port\":" + servicePort + ",\"tipo\":\"" + tipoEntidade + "\"}";
                String request = "POST /heartbeat HTTP/1.1\r\n"
                        + "Host: " + gatewayHost + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + body.length() + "\r\n"
                        + "Connection: close\r\n\r\n"
                        + body;
                OutputStream os = socket.getOutputStream();
                PrintWriter pw = new PrintWriter(os, true);
                pw.print(request);
                pw.flush();
            } catch (Exception e) {
                // Ignora falhas de conexao
            }
            Thread.sleep(2000);
        }
    }

    private void sendGrpcHeartbeat() throws Exception {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(gatewayHost, gatewayPort)
                .usePlaintext()
                .build();

        try {
            HeartbeatGatewayGrpc.HeartbeatGatewayBlockingStub stub = HeartbeatGatewayGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(1000, TimeUnit.MILLISECONDS);

            while (running) {
                try {
                    stub.registrarHeartbeat(
                            Comunicacao.HeartbeatRequest.newBuilder()
                                    .setHost(InetAddress.getLocalHost().getHostAddress())
                                    .setPort(servicePort)
                                    .setTipo(tipoEntidade)
                                    .build()
                    );
                } catch (StatusRuntimeException e) {
                    // Ignora falhas de conexao
                }
                Thread.sleep(2000);
            }
        } finally {
            channel.shutdownNow();
        }
    }
}