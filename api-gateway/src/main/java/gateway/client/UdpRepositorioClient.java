package gateway.client;

import gateway.model.*;
import gateway.transport.*;

import java.io.IOException;

public class UdpRepositorioClient implements RepositorioClient {
    private final UdpRawClient udpBusinessClient;

    public UdpRepositorioClient(int timeoutMs) {
        this.udpBusinessClient = new UdpRawClient(timeoutMs);
    }

    @Override
    public StorageClientResult armazenar(String host, int port, PrecoPayload preco) throws IOException {
        String response = udpBusinessClient.send(host, port, preco.toStorageMessage());
        if ("OK|ARMAZENADO".equals(response)) {
            return StorageClientResult.success("ARMAZENADO");
        }
        if (response != null && response.startsWith("ERRO|")) {
            return StorageClientResult.error(response.substring("ERRO|".length()));
        }
        return StorageClientResult.error("RESPOSTA_INVALIDA_DO_REPOSITORIO");
    }
}
