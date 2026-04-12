package gateway;

public final class RepositorioClientFactory {
    private RepositorioClientFactory() {
    }

    public static RepositorioClient create(String protocolo, int timeoutMs) {
        return switch (protocolo.toLowerCase()) {
            case "tcp" -> new TcpRepositorioClient(timeoutMs);
            case "http" -> new HttpRepositorioClient(timeoutMs);
            default -> throw new IllegalArgumentException("Protocolo de negocio nao suportado: " + protocolo);
        };
    }
}
