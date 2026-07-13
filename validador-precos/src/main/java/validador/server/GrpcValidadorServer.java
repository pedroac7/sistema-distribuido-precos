package validador.server;

import validador.model.*;
import validador.service.ValidadorService;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import precos.Comunicacao;
import precos.ValidadorPrecosGrpc;

import java.io.IOException;

public class GrpcValidadorServer implements ProtocolServer {
    private static final int VALIDATION_WORKER_POOL_SIZE = ValidationExecutor.VALIDATION_WORKER_POOL_SIZE;
    private static final int VALIDATION_QUEUE_CAPACITY = ValidationExecutor.VALIDATION_QUEUE_CAPACITY;
    private static final int VALIDATION_RESULT_TIMEOUT_MS = ValidationExecutor.VALIDATION_RESULT_TIMEOUT_MS;

    private final int businessPort;
    private final ValidadorService validadorService;
    private final ValidationExecutor validationExecutor;

    public GrpcValidadorServer(int businessPort, ValidadorService validadorService) {
        this.businessPort = businessPort;
        this.validadorService = validadorService;
        this.validationExecutor = new ValidationExecutor("validador-grpc-validation");
    }

    @Override
    public void start() {
        try {
            Server server = ServerBuilder.forPort(businessPort)
                    .addService(new ValidadorGrpcService())
                    .build()
                    .start();

            System.out.println("[Validador] Servidor gRPC de negocio iniciado na porta " + businessPort);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.shutdown();
                validationExecutor.shutdownNow();
            }, "validador-grpc-shutdown"));
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
            ValidationResult validationResult;
            try {
                validationResult = validationExecutor.validate(validadorService, payload);
            } catch (ValidationExecutionUnavailableException e) {
                responseObserver.onError(Status.UNAVAILABLE
                        .withDescription(e.getMessage())
                        .asRuntimeException());
                return;
            } catch (RuntimeException e) {
                responseObserver.onError(Status.INTERNAL
                        .withDescription("FALHA_INTERNA")
                        .asRuntimeException());
                return;
            }

            Comunicacao.ValidaPrecoResponse response = Comunicacao.ValidaPrecoResponse.newBuilder()
                    .setValido(validationResult.valid())
                    .setMensagem(validationResult.mensagem())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
