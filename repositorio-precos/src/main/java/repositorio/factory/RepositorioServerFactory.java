package repositorio.factory;

import repositorio.server.*;
import repositorio.service.RepositorioService;

public final class RepositorioServerFactory {
    private RepositorioServerFactory() {
    }

    public static ProtocolServer create(String protocolo, int businessPort, RepositorioService repositorioService) {
        return switch (protocolo.toLowerCase()) {
            case "grpc" -> new GrpcRepositorioServer(businessPort, repositorioService);
            case "udp" -> new UdpRepositorioServer(businessPort, repositorioService);
            case "tcp" -> new TcpRepositorioServer(businessPort, repositorioService);
            case "http" -> new HttpRepositorioServer(businessPort, repositorioService);
            default -> throw new IllegalArgumentException("Protocolo de negocio nao suportado: " + protocolo);
        };
    }
}
