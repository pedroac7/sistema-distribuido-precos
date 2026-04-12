package gateway.server;

import gateway.model.*;
import gateway.service.GatewayService;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import precos.Comunicacao;
import precos.GatewayPrecosGrpc;

import java.io.IOException;

public class GrpcGatewayServer implements ProtocolServer {
    private final int businessPort;
    private final GatewayService gatewayService;

    public GrpcGatewayServer(int businessPort, GatewayService gatewayService) {
        this.businessPort = businessPort;
        this.gatewayService = gatewayService;
    }

    @Override
    public void start() {
        try {
            Server server = ServerBuilder.forPort(businessPort)
                    .addService(new GatewayGrpcService())
                    .build()
                    .start();

            System.out.println("[Gateway] Servidor gRPC de negocio iniciado na porta " + businessPort);
            Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "gateway-grpc-shutdown"));
            server.awaitTermination();
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar servidor gRPC do gateway", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Servidor gRPC do gateway interrompido", e);
        }
    }

    private class GatewayGrpcService extends GatewayPrecosGrpc.GatewayPrecosImplBase {
        @Override
        public void processarPreco(Comunicacao.ProcessaPrecoRequest request,
                                   StreamObserver<Comunicacao.ProcessaPrecoResponse> responseObserver) {
            PrecoPayload preco = new PrecoPayload(
                    request.getPreco().getAtivo(),
                    request.getPreco().getValor(),
                    request.getPreco().getTimestamp()
            );
            System.out.println("[Gateway] Recebimento no gateway: gRPC ativo=" + preco.ativo());

            GatewayResult result = gatewayService.process(preco);
            Comunicacao.ProcessaPrecoResponse response = Comunicacao.ProcessaPrecoResponse.newBuilder()
                    .setSucesso(result.isSuccess())
                    .setCodigo(result.statusCode())
                    .setMensagem(result.mensagem())
                    .setReplicas(result.replicas())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
            System.out.println("[Gateway] Resposta final gRPC: codigo=" + result.statusCode() + " mensagem=" + result.mensagem());
        }
    }
}