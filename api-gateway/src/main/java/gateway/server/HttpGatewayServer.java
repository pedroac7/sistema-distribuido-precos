package gateway.server;

import gateway.model.*;
import gateway.service.GatewayService;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpGatewayServer implements ProtocolServer {
    private static final int KEEP_ALIVE_TIMEOUT_MS = 5000;
    private static final int KEEP_ALIVE_TIMEOUT_SECONDS = KEEP_ALIVE_TIMEOUT_MS / 1000;
    private static final int MAX_REQUESTS_PER_CONNECTION = 100;

    private final int businessPort;
    private final GatewayService gatewayService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpGatewayServer(int businessPort, GatewayService gatewayService) {
        this.businessPort = businessPort;
        this.gatewayService = gatewayService;
    }

    @Override
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(businessPort)) {
            System.out.println("[Gateway] Servidor HTTP de negocio iniciado na porta " + businessPort);
            while (true) {
                Socket client = serverSocket.accept();
                Thread clientThread = new Thread(() -> handleClient(client), "gateway-http-" + client.getPort());
                clientThread.start();
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor HTTP do gateway", e);
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(KEEP_ALIVE_TIMEOUT_MS);
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();

            for (int requestCount = 0; requestCount < MAX_REQUESTS_PER_CONNECTION; requestCount++) {
                HttpRequest request;
                try {
                    request = readRequest(inputStream);
                } catch (HttpParseException e) {
                    GatewayResult result = GatewayResult.error(400, e.getMessage());
                    writeJsonResponse(outputStream, result, false);
                    break;
                } catch (SocketTimeoutException e) {
                    break;
                } catch (IOException e) {
                    GatewayResult result = GatewayResult.error(400, "REQUISICAO_HTTP_INVALIDA");
                    writeJsonResponse(outputStream, result, false);
                    break;
                }

                if (request == null) {
                    break;
                }

                boolean keepAlive = shouldKeepAlive(request) && requestCount < MAX_REQUESTS_PER_CONNECTION - 1;
                GatewayResult result = processRequest(request);
                writeJsonResponse(outputStream, result, keepAlive);
                if (!keepAlive) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // Ignora erro final de conexao
        }
    }

    private GatewayResult processRequest(HttpRequest request) {
        if (!"POST".equals(request.method())) {
            return GatewayResult.error(405, "METODO_NAO_PERMITIDO");
        }
        if (!"/precos".equals(request.path())) {
            return GatewayResult.error(404, "ROTA_NAO_ENCONTRADA");
        }

        try {
            PrecoPayload preco = objectMapper.readValue(request.body(), PrecoPayload.class);
            return gatewayService.process(preco);
        } catch (IOException e) {
            return GatewayResult.error(400, "JSON_INVALIDO");
        }
    }

    private HttpRequest readRequest(InputStream inputStream) throws IOException {
        byte[] headerBytes = readHeaders(inputStream);
        if (headerBytes == null) {
            return null;
        }
        String headerText = new String(headerBytes, StandardCharsets.UTF_8);
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0) {
            throw new HttpParseException("REQUEST_LINE_INVALIDA");
        }

        String[] requestLineParts = lines[0].split(" ");
        if (requestLineParts.length < 3) {
            throw new HttpParseException("REQUEST_LINE_INVALIDA");
        }

        Map<String, String> headers = parseHeaders(lines);
        String contentLengthHeader = headers.get("content-length");
        if (contentLengthHeader == null) {
            throw new HttpParseException("CONTENT_LENGTH_OBRIGATORIO");
        }

        int contentLength = parseContentLength(contentLengthHeader);
        String body = readBody(inputStream, contentLength);
        return new HttpRequest(requestLineParts[0], requestLineParts[1], requestLineParts[2], headers, body);
    }

    private byte[] readHeaders(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;

        while (true) {
            int value = inputStream.read();
            if (value == -1) {
                if (buffer.size() == 0) {
                    return null;
                }
                throw new HttpParseException("CABECALHO_HTTP_INCOMPLETO");
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

        byte[] raw = buffer.toByteArray();
        return java.util.Arrays.copyOf(raw, raw.length - 4);
    }

    private Map<String, String> parseHeaders(String[] lines) {
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }

            String name = line.substring(0, separator).trim().toLowerCase();
            String value = line.substring(separator + 1).trim();
            headers.put(name, value);
        }
        return headers;
    }

    private int parseContentLength(String rawValue) throws HttpParseException {
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException e) {
            throw new HttpParseException("CONTENT_LENGTH_INVALIDO");
        }
    }

    private String readBody(InputStream inputStream, int contentLength) throws IOException {
        byte[] bodyBytes = inputStream.readNBytes(contentLength);
        if (bodyBytes.length != contentLength) {
            throw new HttpParseException("BODY_HTTP_INCOMPLETO");
        }
        return new String(bodyBytes, StandardCharsets.UTF_8);
    }

    private void writeJsonResponse(OutputStream outputStream, GatewayResult result, boolean keepAlive) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (result.isSuccess()) {
            body.put("status", "OK");
            body.put("mensagem", result.mensagem());
            body.put("replicas", result.replicas());
        } else {
            body.put("status", "ERRO");
            body.put("codigo", result.statusCode());
            body.put("mensagem", result.mensagem());
        }

        byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
        String responseHeaders = "HTTP/1.1 " + result.statusCode() + " " + statusText(result.statusCode()) + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + connectionHeaders(keepAlive);

        outputStream.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
        outputStream.write(bodyBytes);
        outputStream.flush();
    }

    private boolean shouldKeepAlive(HttpRequest request) {
        String connectionHeader = request.headers().get("connection");
        if ("close".equalsIgnoreCase(connectionHeader)) {
            return false;
        }
        return !"HTTP/1.0".equalsIgnoreCase(request.httpVersion());
    }

    private String connectionHeaders(boolean keepAlive) {
        if (!keepAlive) {
            return "Connection: close\r\n\r\n";
        }
        return "Connection: keep-alive\r\n"
                + "Keep-Alive: timeout=" + KEEP_ALIVE_TIMEOUT_SECONDS + ", max=" + MAX_REQUESTS_PER_CONNECTION + "\r\n\r\n";
    }

    private String statusText(int statusCode) {
        return switch (statusCode) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 503 -> "Service Unavailable";
            default -> "Internal Server Error";
        };
    }

    private record HttpRequest(String method, String path, String httpVersion, Map<String, String> headers, String body) {
    }

    private static class HttpParseException extends IOException {
        private HttpParseException(String message) {
            super(message);
        }
    }
}
