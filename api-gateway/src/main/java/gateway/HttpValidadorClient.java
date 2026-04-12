package gateway;

import java.io.IOException;

public class HttpValidadorClient implements ValidadorClient {
    private final HttpBusinessClient httpBusinessClient;

    public HttpValidadorClient(int timeoutMs) {
        this.httpBusinessClient = new HttpBusinessClient(timeoutMs);
    }

    @Override
    public ValidationClientResult validar(String host, int port, PrecoPayload preco) throws IOException {
        HttpBusinessClient.HttpCallResult response = httpBusinessClient.postJson(host, port, "/validar", preco);
        if (response.statusCode() == 200) {
            return ValidationClientResult.accepted();
        }
        if (response.statusCode() == 400) {
            return ValidationClientResult.invalid(response.message());
        }
        throw new IOException(response.message().isBlank() ? "RESPOSTA_INVALIDA_DO_VALIDADOR" : response.message());
    }
}
