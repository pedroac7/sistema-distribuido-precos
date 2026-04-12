package gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpBusinessClient {
    private final int timeoutMs;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpBusinessClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public HttpCallResult postJson(String host, int port, String path, PrecoPayload payload) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            byte[] requestBody = objectMapper.writeValueAsBytes(payload);
            OutputStream outputStream = socket.getOutputStream();
            String requestHeaders = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + requestBody.length + "\r\n"
                    + "Connection: close\r\n\r\n";

            outputStream.write(requestHeaders.getBytes(StandardCharsets.UTF_8));
            outputStream.write(requestBody);
            outputStream.flush();

            ParsedHttpResponse response = readResponse(socket.getInputStream());
            return new HttpCallResult(response.statusCode(), extractMessage(response.body()));
        }
    }

    private ParsedHttpResponse readResponse(InputStream inputStream) throws IOException {
        byte[] headerBytes = readHeaders(inputStream);
        String headerText = new String(headerBytes, StandardCharsets.UTF_8);
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0) {
            throw new IOException("RESPOSTA_HTTP_INVALIDA");
        }

        String[] statusLineParts = lines[0].split(" ");
        if (statusLineParts.length < 2) {
            throw new IOException("STATUS_LINE_INVALIDA");
        }

        int statusCode;
        try {
            statusCode = Integer.parseInt(statusLineParts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("STATUS_CODE_INVALIDO", e);
        }
        Map<String, String> headers = parseHeaders(lines);
        int contentLength = parseContentLength(headers.get("content-length"));
        String body = readBody(inputStream, contentLength);
        return new ParsedHttpResponse(statusCode, body);
    }

    private byte[] readHeaders(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;

        while (true) {
            int value = inputStream.read();
            if (value == -1) {
                throw new IOException("CABECALHO_HTTP_INCOMPLETO");
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
                String name = line.substring(0, separator).trim().toLowerCase();
                String value = line.substring(separator + 1).trim();
                headers.put(name, value);
            }
        }
        return headers;
    }

    private int parseContentLength(String rawValue) throws IOException {
        if (rawValue == null || rawValue.isBlank()) {
            return 0;
        }

        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException e) {
            throw new IOException("CONTENT_LENGTH_INVALIDO", e);
        }
    }

    private String readBody(InputStream inputStream, int contentLength) throws IOException {
        if (contentLength <= 0) {
            return "";
        }

        byte[] bodyBytes = inputStream.readNBytes(contentLength);
        if (bodyBytes.length != contentLength) {
            throw new IOException("BODY_HTTP_INCOMPLETO");
        }
        return new String(bodyBytes, StandardCharsets.UTF_8);
    }

    private String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }

        try {
            JsonNode jsonNode = objectMapper.readTree(body);
            if (jsonNode.hasNonNull("mensagem")) {
                return jsonNode.get("mensagem").asText();
            }
        } catch (IOException e) {
            return body;
        }
        return body;
    }

    public record HttpCallResult(int statusCode, String message) {
    }

    private record ParsedHttpResponse(int statusCode, String body) {
    }
}
