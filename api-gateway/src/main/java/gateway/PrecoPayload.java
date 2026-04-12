package gateway;

public record PrecoPayload(String ativo, double valor, long timestamp) {
    public String toValidationMessage() {
        return "VALIDAR|" + ativo + "|" + valor + "|" + timestamp;
    }

    public String toStorageMessage() {
        return "ARMAZENAR|" + ativo + "|" + valor + "|" + timestamp;
    }
}
