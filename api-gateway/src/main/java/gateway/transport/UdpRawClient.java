package gateway.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

public class UdpRawClient {
    private static final int BUFFER_SIZE = 2048;
    private static final int MAX_IDLE_SOCKETS_PER_DESTINATION = 4;

    private final int timeoutMs;
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<PooledDatagramSocket>> socketCache = new ConcurrentHashMap<>();

    public UdpRawClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String send(String host, int port, String message) throws IOException {
        return send(host, port, message, timeoutMs);
    }

    public String send(String host, int port, String message, long requestTimeoutMs) throws IOException {
        if (requestTimeoutMs <= 0) {
            throw new IOException("TIMEOUT_UDP");
        }

        String destinationKey = destinationKey(host, port);
        byte[] requestBytes = message.getBytes(StandardCharsets.UTF_8);
        IOException lastFailure = null;
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMs, requestTimeoutMs));

        for (int attempt = 0; attempt < 2; attempt++) {
            PooledDatagramSocket pooledSocket = borrowSocket(host, port, deadlineNanos);
            boolean reusable = false;

            try {
                pooledSocket.socket().setSoTimeout(effectiveTimeoutMillis(deadlineNanos, timeoutMs));
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
            } catch (SocketTimeoutException e) {
                lastFailure = new IOException("TIMEOUT_UDP", e);
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

    private PooledDatagramSocket borrowSocket(String host, int port, long deadlineNanos) throws IOException {
        String destinationKey = destinationKey(host, port);
        ConcurrentLinkedDeque<PooledDatagramSocket> sockets =
                socketCache.computeIfAbsent(destinationKey, ignored -> new ConcurrentLinkedDeque<>());

        while (true) {
            PooledDatagramSocket pooledSocket = sockets.pollFirst();
            if (pooledSocket == null) {
                return createSocket(host, port, deadlineNanos);
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

    private PooledDatagramSocket createSocket(String host, int port, long deadlineNanos) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        boolean success = false;
        try {
            socket.connect(InetAddress.getByName(host), port);
            socket.setSoTimeout(effectiveTimeoutMillis(deadlineNanos, timeoutMs));
            PooledDatagramSocket pooledSocket = new PooledDatagramSocket(socket);
            success = true;
            return pooledSocket;
        } finally {
            if (!success) {
                socket.close();
            }
        }
    }

    private static int effectiveTimeoutMillis(long deadlineNanos, int configuredTimeoutMs) throws IOException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new IOException("TIMEOUT_UDP");
        }
        long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos + 999_999L));
        return (int) Math.min(configuredTimeoutMs, remainingMillis);
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
