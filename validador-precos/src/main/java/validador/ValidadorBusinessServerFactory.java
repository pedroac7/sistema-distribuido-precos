package validador;

public final class ValidadorBusinessServerFactory {
    private ValidadorBusinessServerFactory() {
    }

    public static BusinessServer create(String protocolo, int businessPort, ValidadorService validadorService) {
        return switch (protocolo.toLowerCase()) {
            case "tcp" -> new TcpValidadorServer(businessPort, validadorService);
            case "http" -> new HttpValidadorServer(businessPort, validadorService);
            default -> throw new IllegalArgumentException("Protocolo de negocio nao suportado: " + protocolo);
        };
    }
}
