package gateway.client;

import gateway.model.*;
import gateway.transport.*;

import java.io.IOException;

public class TcpValidadorClient implements ValidadorClient, AutoCloseable {
    private final TcpRawClient tcpBusinessClient;

    public TcpValidadorClient(int timeoutMs) {
        this.tcpBusinessClient = new TcpRawClient(timeoutMs);
    }

    @Override
    public ValidationClientResult validar(String host, int port, PrecoPayload preco) throws IOException {
        return validarResponse(tcpBusinessClient.send(host, port, preco.toValidationMessage()));
    }

    @Override
    public ValidationClientResult validar(String host, int port, PrecoPayload preco, long timeoutMs) throws IOException {
        return validarResponse(tcpBusinessClient.send(host, port, preco.toValidationMessage(), timeoutMs));
    }

    private ValidationClientResult validarResponse(String response) throws IOException {
        if ("OK|VALIDO".equals(response)) {
            return ValidationClientResult.accepted();
        }
        if (response != null && response.startsWith("ERRO|")) {
            if (response.startsWith("ERRO|503|")) {
                throw new IOException(response);
            }
            return ValidationClientResult.invalid(response.substring("ERRO|".length()));
        }
        throw new IOException("RESPOSTA_INVALIDA_DO_VALIDADOR");
    }

    @Override
    public void close() {
        tcpBusinessClient.close();
    }
}
