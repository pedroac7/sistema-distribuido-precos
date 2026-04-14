package gateway.client;

import gateway.model.*;
import gateway.transport.*;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import precos.Comunicacao;
import precos.ValidadorPrecosGrpc;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class GrpcValidadorClient implements ValidadorClient {
    private final int timeoutMs;
    private final ConcurrentHashMap<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();

    public GrpcValidadorClient(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public ValidationClientResult validar(String host, int port, PrecoPayload preco) throws IOException {
        String destinationKey = destinationKey(host, port);
        ManagedChannel channel = getOrCreateChannel(destinationKey, host, port);

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
            discardChannel(destinationKey, channel);
            throw new IOException("FALHA_GRPC_VALIDADOR: " + e.getStatus().getCode(), e);
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
