package validador;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpValidadorServer implements BusinessServer {
    private static final int SOCKET_TIMEOUT_MS = 2000;

    private final int businessPort;
    private final ValidadorService validadorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpValidadorServer(int businessPort, ValidadorService validadorService) {
        this.businessPort = businessPort;
        this.validadorService = validadorService;
    }

    @Override
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(businessPort)) {
            System.out.println("[Validador] Servidor HTTP de negocio iniciado na porta " + businessPort);
            while (true) {
                Socket client = serverSocket.accept();
                Thread clientThread = new Thread(() -> handleClient(client), "validador-http-" + client.getPort());
                clientThread.start();
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor HTTP do validador", e);
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            try {
                HttpRequest request = readRequest(socket.getInputStream());
                System.out.println("[Validador] Requisicao HTTP recebida: " + request.method() + " " + request.path());

                HttpResult result = processRequest(request);
                writeJsonResponse(socket.getOutputStream(), result.statusCode(), result.body());
                System.out.println("[Validador] Resposta HTTP enviada: " + result.statusCode() + " - " + result.mensagem());
            } catch (HttpParseException e) {
                HttpResult result = errorResult(400, e.getMessage());
                writeJsonResponse(socket.getOutputStream(), result.statusCode(), result.body());
                System.out.println("[Validador] Resposta HTTP enviada: " + result.statusCode() + " - " + result.mensagem());
            } catch (IOException e) {
                HttpResult result = errorResult(400, "REQUISICAO_HTTP_INVALIDA");
                writeJsonResponse(socket.getOutputStream(), result.statusCode(), result.body());
                System.out.println("[Validador] Resposta HTTP enviada: " + result.statusCode() + " - " + result.mensagem());
            }
        } catch (IOException ignored) {
            // Ignora erro final de conexao
        }
    }

    private HttpResult processRequest(HttpRequest request) {
        if (!"POST".equals(request.method())) {
            return errorResult(405, "METODO_NAO_PERMITIDO");
        }
        if (!"/validar".equals(request.path())) {
            return errorResult(404, "ROTA_NAO_ENCONTRADA");
        }

        try {
            PrecoPayload payload = objectMapper.readValue(request.body(), PrecoPayload.class);
            ValidationResult validationResult = validadorService.validar(payload);
            if (validationResult.valid()) {
                return successResult("VALIDO");
            }
            return errorResult(400, validationResult.mensagem());
        } catch (IOException e) {
            return errorResult(400, "JSON_INVALIDO");
        }
    }

    private HttpRequest readRequest(InputStream inputStream) throws IOException {
        byte[] headerBytes = readHeaders(inputStream);
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
        return new HttpRequest(requestLineParts[0], requestLineParts[1], body);
    }

    private byte[] readHeaders(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;

        while (true) {
            int value = inputStream.read();
            if (value == -1) {
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
            if (separator > 0) {
                headers.put(line.substring(0, separator).trim().toLowerCase(), line.substring(separator + 1).trim());
            }
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

    private HttpResult successResult(String mensagem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("mensagem", mensagem);
        return new HttpResult(200, mensagem, body);
    }

    private HttpResult errorResult(int statusCode, String mensagem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ERRO");
        body.put("codigo", statusCode);
        body.put("mensagem", mensagem);
        return new HttpResult(statusCode, mensagem, body);
    }

    private void writeJsonResponse(OutputStream outputStream, int statusCode, Map<String, Object> body) throws IOException {
        byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
        String responseHeaders = "HTTP/1.1 " + statusCode + " " + statusText(statusCode) + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        outputStream.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
        outputStream.write(bodyBytes);
        outputStream.flush();
    }

    private String statusText(int statusCode) {
        return switch (statusCode) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> "Internal Server Error";
        };
    }

    private record HttpRequest(String method, String path, String body) {
    }

    private record HttpResult(int statusCode, String mensagem, Map<String, Object> body) {
    }

    private static class HttpParseException extends IOException {
        private HttpParseException(String message) {
            super(message);
        }
    }
}
