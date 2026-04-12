package gateway.model;

public record GatewayResult(int statusCode, String mensagem, int replicas) {
    public static GatewayResult success(int replicas) {
        return new GatewayResult(200, "ARMAZENADO", replicas);
    }

    public static GatewayResult error(int statusCode, String mensagem) {
        return new GatewayResult(statusCode, mensagem, 0);
    }

    public boolean isSuccess() {
        return statusCode == 200;
    }

    public String toTcpResponse() {
        if (isSuccess()) {
            return "OK|" + mensagem + "|replicas=" + replicas;
        }
        return "ERRO|" + statusCode + "|" + mensagem;
    }
}
