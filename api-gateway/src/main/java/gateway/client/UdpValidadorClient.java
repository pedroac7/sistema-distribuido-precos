package gateway.client;

import gateway.model.*;
import gateway.transport.*;

import java.io.IOException;

public class UdpValidadorClient implements ValidadorClient {
    private final UdpRawClient udpBusinessClient;

    public UdpValidadorClient(int timeoutMs) {
        this.udpBusinessClient = new UdpRawClient(timeoutMs);
    }

    @Override
    public ValidationClientResult validar(String host, int port, PrecoPayload preco) throws IOException {
        String response = udpBusinessClient.send(host, port, preco.toValidationMessage());
        if ("OK|VALIDO".equals(response)) {
            return ValidationClientResult.accepted();
        }
        if (response != null && response.startsWith("ERRO|")) {
            return ValidationClientResult.invalid(response.substring("ERRO|".length()));
        }
        throw new IOException("RESPOSTA_INVALIDA_DO_VALIDADOR");
    }
}
