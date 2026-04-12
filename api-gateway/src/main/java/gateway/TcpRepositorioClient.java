package gateway;

import java.io.IOException;

public class TcpRepositorioClient implements RepositorioClient {
    private final TcpBusinessClient tcpBusinessClient;

    public TcpRepositorioClient(int timeoutMs) {
        this.tcpBusinessClient = new TcpBusinessClient(timeoutMs);
    }

    @Override
    public StorageClientResult armazenar(String host, int port, PrecoPayload preco) throws IOException {
        String response = tcpBusinessClient.send(host, port, preco.toStorageMessage());
        if ("OK|ARMAZENADO".equals(response)) {
            return StorageClientResult.success("ARMAZENADO");
        }
        if (response != null && response.startsWith("ERRO|")) {
            return StorageClientResult.error(response.substring("ERRO|".length()));
        }
        return StorageClientResult.error("RESPOSTA_INVALIDA_DO_REPOSITORIO");
    }
}
