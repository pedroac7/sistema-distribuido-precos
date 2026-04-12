package gateway.client;

import gateway.model.*;
import gateway.transport.*;

import java.io.IOException;

public class TcpValidadorClient implements ValidadorClient {
    private final TcpRawClient tcpBusinessClient;

    public TcpValidadorClient(int timeoutMs) {
        this.tcpBusinessClient = new TcpRawClient(timeoutMs);
    }

    @Override
    public ValidationClientResult validar(String host, int port, PrecoPayload preco) throws IOException {
        String response = tcpBusinessClient.send(host, port, preco.toValidationMessage());
        if ("OK|VALIDO".equals(response)) {
            return ValidationClientResult.accepted();
        }
        if (response != null && response.startsWith("ERRO|")) {
            return ValidationClientResult.invalid(response.substring("ERRO|".length()));
        }
        throw new IOException("RESPOSTA_INVALIDA_DO_VALIDADOR");
    }
}
