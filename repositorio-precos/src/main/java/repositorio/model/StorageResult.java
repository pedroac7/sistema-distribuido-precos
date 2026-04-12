package repositorio.model;

public record StorageResult(boolean success, String mensagem) {
    public static StorageResult success(String mensagem) {
        return new StorageResult(true, mensagem);
    }

    public static StorageResult error(String mensagem) {
        return new StorageResult(false, mensagem);
    }
}
