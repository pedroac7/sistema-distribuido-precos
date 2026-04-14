package gateway.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class UdpRawClient {
    private static final int BUFFER_SIZE = 2048;
    private static final int MAX_IDLE_SOCKETS_PER_DESTINATION = 4;

    private final int timeoutMs;
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<PooledDatagramSocket>> socketCache = new ConcurrentHashMap<>();

    public UdpRawClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String send(String host, int port, String message) throws IOException {
        String destinationKey = destinationKey(host, port);
        byte[] requestBytes = message.getBytes(StandardCharsets.UTF_8);
        IOException lastFailure = null;

        for (int attempt = 0; attempt < 2; attempt++) {
            PooledDatagramSocket pooledSocket = borrowSocket(host, port);
            boolean reusable = false;

            try {
                DatagramPacket requestPacket = new DatagramPacket(requestBytes, requestBytes.length);
                pooledSocket.socket().send(requestPacket);

                byte[] responseBuffer = new byte[BUFFER_SIZE];
                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                pooledSocket.socket().receive(responsePacket);

                reusable = true;
                return new String(
                        responsePacket.getData(),
                        responsePacket.getOffset(),
                        responsePacket.getLength(),
                        StandardCharsets.UTF_8
                ).trim();
            } catch (IOException e) {
                lastFailure = e;
            } finally {
                if (reusable) {
                    returnSocket(destinationKey, pooledSocket);
                } else {
                    closeQuietly(pooledSocket);
                }
            }
        }

        throw lastFailure == null ? new IOException("FALHA_UDP_DESCONHECIDA") : lastFailure;
    }

    private PooledDatagramSocket borrowSocket(String host, int port) throws IOException {
        String destinationKey = destinationKey(host, port);
        ConcurrentLinkedDeque<PooledDatagramSocket> sockets =
                socketCache.computeIfAbsent(destinationKey, ignored -> new ConcurrentLinkedDeque<>());

        while (true) {
            PooledDatagramSocket pooledSocket = sockets.pollFirst();
            if (pooledSocket == null) {
                return createSocket(host, port);
            }
            if (pooledSocket.isUsable()) {
                return pooledSocket;
            }
            closeQuietly(pooledSocket);
        }
    }

    private void returnSocket(String destinationKey, PooledDatagramSocket pooledSocket) {
        if (!pooledSocket.isUsable()) {
            closeQuietly(pooledSocket);
            return;
        }

        ConcurrentLinkedDeque<PooledDatagramSocket> sockets =
                socketCache.computeIfAbsent(destinationKey, ignored -> new ConcurrentLinkedDeque<>());
        if (sockets.size() >= MAX_IDLE_SOCKETS_PER_DESTINATION) {
            closeQuietly(pooledSocket);
            return;
        }
        sockets.offerFirst(pooledSocket);
    }

    private PooledDatagramSocket createSocket(String host, int port) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        socket.connect(InetAddress.getByName(host), port);
        socket.setSoTimeout(timeoutMs);
        return new PooledDatagramSocket(socket);
    }

    private String destinationKey(String host, int port) {
        return host + ":" + port;
    }

    private void closeQuietly(PooledDatagramSocket pooledSocket) {
        pooledSocket.socket().close();
    }

    private record PooledDatagramSocket(DatagramSocket socket) {
        private boolean isUsable() {
            return !socket.isClosed() && socket.isConnected();
        }
    }
}
