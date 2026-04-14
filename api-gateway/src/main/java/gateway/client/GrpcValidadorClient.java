package gateway.client;

import gateway.model.*;
import gateway.transport.*;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import precos.Comunicacao;
import precos.ValidadorPrecosGrpc;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class GrpcValidadorClient implements ValidadorClient {
    private final int timeoutMs;

    public GrpcValidadorClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public ValidationClientResult validar(String host, int port, PrecoPayload preco) throws IOException {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();

        try {
            ValidadorPrecosGrpc.ValidadorPrecosBlockingStub stub = ValidadorPrecosGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);

            Comunicacao.ValidaPrecoResponse response = stub.validarPreco(
                    Comunicacao.ValidaPrecoRequest.newBuilder()
                            .setPreco(toGrpcPreco(preco))
                            .build()
            );

            if (response.getValido()) {
                return ValidationClientResult.accepted();
            }
            return ValidationClientResult.invalid(response.getMensagem());
        } catch (StatusRuntimeException e) {
            throw new IOException("FALHA_GRPC_VALIDADOR: " + e.getStatus().getCode(), e);
        } finally {
            channel.shutdownNow();
        }
    }

    private Comunicacao.Preco toGrpcPreco(PrecoPayload preco) {
        return Comunicacao.Preco.newBuilder()
                .setAtivo(preco.ativo())
                .setValor(preco.valor())
                .setTimestamp(preco.timestamp())
                .build();
    }
}
