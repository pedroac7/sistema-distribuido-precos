package validador.server;

import validador.model.*;
import validador.service.ValidadorService;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import precos.Comunicacao;
import precos.ValidadorPrecosGrpc;

import java.io.IOException;

public class GrpcValidadorServer implements ProtocolServer {
    private final int businessPort;
    private final ValidadorService validadorService;

    public GrpcValidadorServer(int businessPort, ValidadorService validadorService) {
        this.businessPort = businessPort;
        this.validadorService = validadorService;
    }

    @Override
    public void start() {
        try {
            Server server = ServerBuilder.forPort(businessPort)
                    .addService(new ValidadorGrpcService())
                    .build()
                    .start();

            System.out.println("[Validador] Servidor gRPC de negocio iniciado na porta " + businessPort);
            Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "validador-grpc-shutdown"));
            server.awaitTermination();
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor gRPC do validador", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Servidor gRPC do validador interrompido", e);
        }
    }

    private class ValidadorGrpcService extends ValidadorPrecosGrpc.ValidadorPrecosImplBase {
        @Override
        public void validarPreco(Comunicacao.ValidaPrecoRequest request,
                                 StreamObserver<Comunicacao.ValidaPrecoResponse> responseObserver) {
            PrecoPayload payload = new PrecoPayload(
                    request.getPreco().getAtivo(),
                    request.getPreco().getValor(),
                    request.getPreco().getTimestamp()
            );
            System.out.println("[Validador] Requisicao gRPC recebida para ativo " + payload.ativo());

            ValidationResult validationResult = validadorService.validar(payload);
            Comunicacao.ValidaPrecoResponse response = Comunicacao.ValidaPrecoResponse.newBuilder()
                    .setValido(validationResult.valid())
                    .setMensagem(validationResult.mensagem())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
            System.out.println("[Validador] Resposta gRPC enviada: " + validationResult.mensagem());
        }
    }
}
