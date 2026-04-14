package gateway.client;

import gateway.model.*;
import gateway.transport.*;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import precos.Comunicacao;
import precos.RepositorioPrecosGrpc;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class GrpcRepositorioClient implements RepositorioClient {
    private final int timeoutMs;

    public GrpcRepositorioClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public StorageClientResult armazenar(String host, int port, PrecoPayload preco) throws IOException {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();

        try {
            RepositorioPrecosGrpc.RepositorioPrecosBlockingStub stub = RepositorioPrecosGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);

            Comunicacao.ArmazenaPrecoResponse response = stub.armazenarPreco(
                    Comunicacao.ArmazenaPrecoRequest.newBuilder()
                            .setPreco(toGrpcPreco(preco))
                            .build()
            );

            if (response.getSucesso()) {
                return StorageClientResult.success(response.getMensagem());
            }
            return StorageClientResult.error(response.getMensagem());
        } catch (StatusRuntimeException e) {
            throw new IOException("FALHA_GRPC_REPOSITORIO: " + e.getStatus().getCode(), e);
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
