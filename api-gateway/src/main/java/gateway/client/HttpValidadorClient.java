package gateway.client;

import gateway.model.*;
import gateway.transport.*;

import java.io.IOException;

public class HttpValidadorClient implements ValidadorClient {
    private final HttpRawClient httpBusinessClient;

    public HttpValidadorClient(int timeoutMs) {
        this.httpBusinessClient = new HttpRawClient(timeoutMs);
    }

    @Override
    public ValidationClientResult validar(String host, int port, PrecoPayload preco) throws IOException {
        return validarResponse(httpBusinessClient.postJson(host, port, "/validar", preco));
    }

    @Override
    public ValidationClientResult validar(String host, int port, PrecoPayload preco, long timeoutMs) throws IOException {
        return validarResponse(httpBusinessClient.postJson(host, port, "/validar", preco, timeoutMs));
    }

    private ValidationClientResult validarResponse(HttpRawClient.HttpCallResult response) throws IOException {
        if (response.statusCode() == 200) {
            return ValidationClientResult.accepted();
        }
        if (response.statusCode() == 400) {
            return ValidationClientResult.invalid(response.message());
        }
        throw new IOException(response.message().isBlank() ? "RESPOSTA_INVALIDA_DO_VALIDADOR" : response.message());
    }
}
