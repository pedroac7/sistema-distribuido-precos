package gateway.client;

import gateway.model.*;
import gateway.transport.*;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import precos.Comunicacao;
import precos.RepositorioPrecosGrpc;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class GrpcRepositorioClient implements RepositorioClient {
    private final int timeoutMs;
    private final ConcurrentHashMap<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();

    public GrpcRepositorioClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public StorageClientResult armazenar(String host, int port, PrecoPayload preco) throws IOException {
        String destinationKey = destinationKey(host, port);
        ManagedChannel channel = getOrCreateChannel(destinationKey, host, port);

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
            discardChannel(destinationKey, channel);
            throw new IOException("FALHA_GRPC_REPOSITORIO: " + e.getStatus().getCode(), e);
        }
    }

    private ManagedChannel getOrCreateChannel(String destinationKey, String host, int port) {
        return channelCache.compute(destinationKey, (ignored, current) -> {
            if (current != null && !current.isShutdown() && !current.isTerminated()) {
                return current;
            }
            if (current != null) {
                current.shutdownNow();
            }
            return ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build();
        });
    }

    private void discardChannel(String destinationKey, ManagedChannel channel) {
        if (channelCache.remove(destinationKey, channel)) {
            channel.shutdownNow();
        }
    }

    private String destinationKey(String host, int port) {
        return host + ":" + port;
    }

    private Comunicacao.Preco toGrpcPreco(PrecoPayload preco) {
        return Comunicacao.Preco.newBuilder()
                .setAtivo(preco.ativo())
                .setValor(preco.valor())
                .setTimestamp(preco.timestamp())
                .build();
    }
}
