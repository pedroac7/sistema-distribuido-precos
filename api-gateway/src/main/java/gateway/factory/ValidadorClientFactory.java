package gateway.factory;

import gateway.client.*;
import gateway.server.*;
import gateway.service.GatewayService;

public final class ValidadorClientFactory {
    private ValidadorClientFactory() {
    }

    public static ValidadorClient create(String protocolo, int timeoutMs) {
        return switch (protocolo.toLowerCase()) {
            case "grpc" -> new GrpcValidadorClient(timeoutMs);
            case "udp" -> new UdpValidadorClient(timeoutMs);
            case "tcp" -> new TcpValidadorClient(timeoutMs);
            case "http" -> new HttpValidadorClient(timeoutMs);
            default -> throw new IllegalArgumentException("Protocolo de negocio nao suportado: " + protocolo);
        };
    }
}
