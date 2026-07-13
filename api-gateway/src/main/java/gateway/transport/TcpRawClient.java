package gateway.transport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TcpRawClient implements AutoCloseable {
    public static final int DEFAULT_MAX_CONNECTIONS_PER_DESTINATION = 16;
    public static final int DEFAULT_POOL_ACQUIRE_TIMEOUT_MS = 500;
    public static final int CLIENT_MAX_REQUESTS_PER_CONNECTION = 900;

    private final int timeoutMs;
    private final int maxConnectionsPerDestination;
    private final int poolAcquireTimeoutMs;
    private final int maxRequestsPerConnection;
    private final Clock clock;
    private final Map<EndpointKey, DestinationPool> pools = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public TcpRawClient(int timeoutMs) {
        this(timeoutMs,
                DEFAULT_MAX_CONNECTIONS_PER_DESTINATION,
                DEFAULT_POOL_ACQUIRE_TIMEOUT_MS,
                CLIENT_MAX_REQUESTS_PER_CONNECTION,
                Clock.systemUTC());
    }

    TcpRawClient(int timeoutMs,
                 int maxConnectionsPerDestination,
                 int poolAcquireTimeoutMs,
                 int maxRequestsPerConnection,
                 Clock clock) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs deve ser positivo");
        }
        if (maxConnectionsPerDestination <= 0) {
            throw new IllegalArgumentException("maxConnectionsPerDestination deve ser positivo");
        }
        if (poolAcquireTimeoutMs <= 0) {
            throw new IllegalArgumentException("poolAcquireTimeoutMs deve ser positivo");
        }
        if (maxRequestsPerConnection <= 0) {
            throw new IllegalArgumentException("maxRequestsPerConnection deve ser positivo");
        }
        this.timeoutMs = timeoutMs;
        this.maxConnectionsPerDestination = maxConnectionsPerDestination;
        this.poolAcquireTimeoutMs = poolAcquireTimeoutMs;
        this.maxRequestsPerConnection = maxRequestsPerConnection;
        this.clock = clock;
    }

    public String send(String host, int port, String message) throws IOException {
        return send(host, port, message, timeoutMs);
    }

    public String send(String host, int port, String message, long requestTimeoutMs) throws IOException {
        if (closed) {
            throw new IOException("CLIENTE_TCP_FECHADO");
        }
        if (requestTimeoutMs <= 0) {
            throw new IOException("TIMEOUT_TCP");
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMs, requestTimeoutMs));
        EndpointKey endpoint = new EndpointKey(host, port);
        DestinationPool pool = pools.computeIfAbsent(endpoint, DestinationPool::new);
        PooledConnection connection;
        try {
            connection = pool.acquire(deadlineNanos);
        } catch (SocketTimeoutException e) {
            throw new IOException("TIMEOUT_TCP", e);
        }
        boolean reusable = false;
        try {
            String response = connection.send(message, effectiveTimeoutMillis(deadlineNanos, timeoutMs));
            if (response == null) {
                throw new IOException("RESPOSTA_TCP_INCOMPLETA");
            }
            reusable = connection.isReusable();
            return response;
        } catch (SocketTimeoutException e) {
            throw new IOException("TIMEOUT_TCP", e);
        } finally {
            pool.release(connection, reusable);
        }
    }

    @Override
    public void close() {
        closed = true;
        for (DestinationPool pool : pools.values()) {
            pool.close();
        }
    }

    int openedConnectionCount(String host, int port) {
        DestinationPool pool = pools.get(new EndpointKey(host, port));
        return pool == null ? 0 : pool.openedConnectionCount();
    }

    int idleConnectionCount(String host, int port) {
        DestinationPool pool = pools.get(new EndpointKey(host, port));
        return pool == null ? 0 : pool.idleConnectionCount();
    }

    private PooledConnection openConnection(EndpointKey endpoint, long deadlineNanos) throws IOException {
        Socket socket = new Socket();
        boolean success = false;
        try {
            int connectTimeoutMs = effectiveTimeoutMillis(deadlineNanos, timeoutMs);
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), connectTimeoutMs);
            socket.setSoTimeout(effectiveTimeoutMillis(deadlineNanos, timeoutMs));
            socket.setTcpNoDelay(true);
            PooledConnection connection = new PooledConnection(
                    socket,
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)),
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)));
            success = true;
            return connection;
        } finally {
            if (!success) {
                closeQuietly(socket);
            }
        }
    }

    private static int effectiveTimeoutMillis(long deadlineNanos, int configuredTimeoutMs) throws IOException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new IOException("TIMEOUT_TCP");
        }
        long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos + 999_999L));
        return (int) Math.min(configuredTimeoutMs, remainingMillis);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Ignora erro ao descartar conexao TCP quebrada
        }
    }

    private final class DestinationPool {
        private final EndpointKey endpoint;
        private final ArrayDeque<PooledConnection> idleConnections = new ArrayDeque<>();
        private final Set<PooledConnection> allConnections = new HashSet<>();
        private int totalConnections;
        private int openedConnections;
        private boolean poolClosed;

        private DestinationPool(EndpointKey endpoint) {
            this.endpoint = endpoint;
        }

        private PooledConnection acquire(long requestDeadlineNanos) throws IOException {
            long poolDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    effectiveTimeoutMillis(requestDeadlineNanos, poolAcquireTimeoutMs));

            while (true) {
                synchronized (this) {
                    if (closed || poolClosed) {
                        throw new IOException("CLIENTE_TCP_FECHADO");
                    }

                    while (!idleConnections.isEmpty()) {
                        PooledConnection connection = idleConnections.removeFirst();
                        if (connection.isReusable()) {
                            return connection;
                        }
                        discardConnection(connection);
                    }

                    if (totalConnections < maxConnectionsPerDestination) {
                        totalConnections++;
                        break;
                    }

                    long requestRemainingNanos = requestDeadlineNanos - System.nanoTime();
                    if (requestRemainingNanos <= 0) {
                        throw new IOException("TIMEOUT_TCP");
                    }
                    long poolRemainingNanos = poolDeadlineNanos - System.nanoTime();
                    if (poolRemainingNanos <= 0) {
                        throw new IOException("POOL_TCP_ESGOTADO|" + endpoint.host() + ":" + endpoint.port());
                    }
                    long waitMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(
                            Math.min(requestRemainingNanos, poolRemainingNanos) + 999_999L));

                    try {
                        wait(waitMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("INTERRUPTADO_AGUARDANDO_POOL_TCP", e);
                    }
                }
            }

            boolean created = false;
            try {
                PooledConnection connection = openConnection(endpoint, requestDeadlineNanos);
                synchronized (this) {
                    if (closed || poolClosed) {
                        closeConnection(connection);
                        throw new IOException("CLIENTE_TCP_FECHADO");
                    }
                    allConnections.add(connection);
                    openedConnections++;
                }
                created = true;
                return connection;
            } finally {
                if (!created) {
                    synchronized (this) {
                        totalConnections--;
                        notifyAll();
                    }
                }
            }
        }

        private void release(PooledConnection connection, boolean reusable) {
            synchronized (this) {
                if (!poolClosed && !closed && reusable && connection.isReusable()) {
                    idleConnections.addLast(connection);
                } else {
                    discardConnection(connection);
                }
                notifyAll();
            }
        }

        private void close() {
            synchronized (this) {
                poolClosed = true;
                for (PooledConnection connection : allConnections) {
                    closeConnection(connection);
                }
                allConnections.clear();
                idleConnections.clear();
                totalConnections = 0;
                notifyAll();
            }
        }

        private int openedConnectionCount() {
            synchronized (this) {
                return openedConnections;
            }
        }

        private int idleConnectionCount() {
            synchronized (this) {
                return idleConnections.size();
            }
        }

        private void discardConnection(PooledConnection connection) {
            if (allConnections.remove(connection)) {
                totalConnections--;
            }
            closeConnection(connection);
        }

        private void closeConnection(PooledConnection connection) {
            try {
                connection.close();
            } catch (IOException ignored) {
                // Ignora erro ao fechar conexao TCP descartada
            }
        }
    }

    private final class PooledConnection implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private int requestCount;
        private long lastUsedMillis;

        private PooledConnection(Socket socket, BufferedReader reader, BufferedWriter writer) {
            this.socket = socket;
            this.reader = reader;
            this.writer = writer;
            this.lastUsedMillis = clock.millis();
        }

        private String send(String message, int readTimeoutMs) throws IOException {
            socket.setSoTimeout(readTimeoutMs);
            writer.write(message);
            writer.newLine();
            writer.flush();
            String response = reader.readLine();
            if (response == null) {
                throw new IOException("RESPOSTA_TCP_INCOMPLETA");
            }
            requestCount++;
            lastUsedMillis = clock.millis();
            return response;
        }

        private boolean isReusable() {
            return !socket.isClosed()
                    && socket.isConnected()
                    && !socket.isInputShutdown()
                    && !socket.isOutputShutdown()
                    && requestCount < maxRequestsPerConnection;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private record EndpointKey(String host, int port) {
    }
}
