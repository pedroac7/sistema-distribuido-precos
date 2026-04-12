package gateway;

public final class GatewayBusinessServerFactory {
    private GatewayBusinessServerFactory() {
    }

    public static BusinessServer create(String protocolo, int businessPort, GatewayService gatewayService) {
        return switch (protocolo.toLowerCase()) {
            case "tcp" -> new TcpGatewayServer(businessPort, gatewayService);
            case "http" -> new HttpGatewayServer(businessPort, gatewayService);
            default -> throw new IllegalArgumentException("Protocolo de negocio nao suportado: " + protocolo);
        };
    }
}
