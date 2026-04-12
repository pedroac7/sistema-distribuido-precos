package gateway.factory;

import gateway.client.*;
import gateway.server.*;
import gateway.service.GatewayService;

public final class GatewayServerFactory {
    private GatewayServerFactory() {
    }

    public static ProtocolServer create(String protocolo, int businessPort, GatewayService gatewayService) {
        return switch (protocolo.toLowerCase()) {
            case "grpc" -> new GrpcGatewayServer(businessPort, gatewayService);
            case "udp" -> new UdpGatewayServer(businessPort, gatewayService);
            case "tcp" -> new TcpGatewayServer(businessPort, gatewayService);
            case "http" -> new HttpGatewayServer(businessPort, gatewayService);
            default -> throw new IllegalArgumentException("Protocolo de negocio nao suportado: " + protocolo);
        };
    }
}