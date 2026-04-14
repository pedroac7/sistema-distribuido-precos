package repositorio.server;

import repositorio.model.*;
import repositorio.service.RepositorioService;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import precos.Comunicacao;
import precos.RepositorioPrecosGrpc;

import java.io.IOException;

public class GrpcRepositorioServer implements ProtocolServer {
    private final int businessPort;
    private final RepositorioService repositorioService;

    public GrpcRepositorioServer(int businessPort, RepositorioService repositorioService) {
        this.businessPort = businessPort;
        this.repositorioService = repositorioService;
    }

    @Override
    public void start() {
        try {
            Server server = ServerBuilder.forPort(businessPort)
                    .addService(new RepositorioGrpcService())
                    .build()
                    .start();

            System.out.println("[Repositorio] Servidor gRPC de negocio iniciado na porta " + businessPort);
            Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "repositorio-grpc-shutdown"));
            server.awaitTermination();
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor gRPC do repositorio", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Servidor gRPC do repositorio interrompido", e);
        }
    }

    private class RepositorioGrpcService extends RepositorioPrecosGrpc.RepositorioPrecosImplBase {
        @Override
        public void armazenarPreco(Comunicacao.ArmazenaPrecoRequest request,
                                   StreamObserver<Comunicacao.ArmazenaPrecoResponse> responseObserver) {
            PrecoPayload payload = new PrecoPayload(
                    request.getPreco().getAtivo(),
                    request.getPreco().getValor(),
                    request.getPreco().getTimestamp()
            );
            StorageResult storageResult = repositorioService.armazenar(payload);
            Comunicacao.ArmazenaPrecoResponse response = Comunicacao.ArmazenaPrecoResponse.newBuilder()
                    .setSucesso(storageResult.success())
                    .setMensagem(storageResult.mensagem())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
