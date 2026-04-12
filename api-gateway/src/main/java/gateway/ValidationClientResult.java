package gateway;

public record ValidationClientResult(boolean valid, String mensagem) {
    public static ValidationClientResult accepted() {
        return new ValidationClientResult(true, "VALIDO");
    }

    public static ValidationClientResult invalid(String mensagem) {
        return new ValidationClientResult(false, mensagem);
    }
}
