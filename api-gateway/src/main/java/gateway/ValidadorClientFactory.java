package gateway;

public final class ValidadorClientFactory {
    private ValidadorClientFactory() {
    }

    public static ValidadorClient create(String protocolo, int timeoutMs) {
        return switch (protocolo.toLowerCase()) {
            case "tcp" -> new TcpValidadorClient(timeoutMs);
            case "http" -> new HttpValidadorClient(timeoutMs);
            default -> throw new IllegalArgumentException("Protocolo de negocio nao suportado: " + protocolo);
        };
    }
}
