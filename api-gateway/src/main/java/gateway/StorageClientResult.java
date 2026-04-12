package gateway;

public record StorageClientResult(boolean success, String mensagem) {
    public static StorageClientResult success(String mensagem) {
        return new StorageClientResult(true, mensagem);
    }

    public static StorageClientResult error(String mensagem) {
        return new StorageClientResult(false, mensagem);
    }
}
