package gateway;

import java.io.IOException;

public class HttpRepositorioClient implements RepositorioClient {
    private final HttpBusinessClient httpBusinessClient;

    public HttpRepositorioClient(int timeoutMs) {
        this.httpBusinessClient = new HttpBusinessClient(timeoutMs);
    }

    @Override
    public StorageClientResult armazenar(String host, int port, PrecoPayload preco) throws IOException {
        HttpBusinessClient.HttpCallResult response = httpBusinessClient.postJson(host, port, "/armazenar", preco);
        if (response.statusCode() == 200) {
            return StorageClientResult.success(response.message().isBlank() ? "ARMAZENADO" : response.message());
        }
        return StorageClientResult.error(response.message().isBlank() ? "FALHA_AO_ARMAZENAR" : response.message());
    }
}
