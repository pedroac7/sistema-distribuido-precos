package repositorio;

public final class RepositorioBusinessServerFactory {
    private RepositorioBusinessServerFactory() {
    }

    public static BusinessServer create(String protocolo, int businessPort, RepositorioService repositorioService) {
        return switch (protocolo.toLowerCase()) {
            case "tcp" -> new TcpRepositorioServer(businessPort, repositorioService);
            case "http" -> new HttpRepositorioServer(businessPort, repositorioService);
            default -> throw new IllegalArgumentException("Protocolo de negocio nao suportado: " + protocolo);
        };
    }
}
