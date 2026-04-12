package gateway.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public class UdpRawClient {
    private static final int BUFFER_SIZE = 2048;

    private final int timeoutMs;

    public UdpRawClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String send(String host, int port, String message) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);

            byte[] requestBytes = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket requestPacket = new DatagramPacket(
                    requestBytes,
                    requestBytes.length,
                    InetAddress.getByName(host),
                    port
            );
            socket.send(requestPacket);

            byte[] responseBuffer = new byte[BUFFER_SIZE];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(responsePacket);

            return new String(
                    responsePacket.getData(),
                    responsePacket.getOffset(),
                    responsePacket.getLength(),
                    StandardCharsets.UTF_8
            ).trim();
        }
    }
}
