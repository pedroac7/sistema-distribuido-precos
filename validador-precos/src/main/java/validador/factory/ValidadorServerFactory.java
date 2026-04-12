package validador.factory;

import validador.server.*;
import validador.service.ValidadorService;

public final class ValidadorServerFactory {
    private ValidadorServerFactory() {
    }

    public static ProtocolServer create(String protocolo, int businessPort, ValidadorService validadorService) {
        return switch (protocolo.toLowerCase()) {
            case "grpc" -> new GrpcValidadorServer(businessPort, validadorService);
            case "udp" -> new UdpValidadorServer(businessPort, validadorService);
            case "tcp" -> new TcpValidadorServer(businessPort, validadorService);
            case "http" -> new HttpValidadorServer(businessPort, validadorService);
            default -> throw new IllegalArgumentException("Protocolo de negocio nao suportado: " + protocolo);
        };
    }
}
