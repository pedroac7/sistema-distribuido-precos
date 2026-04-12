package validador.model;

public record ValidationResult(boolean valid, String mensagem) {
    public static ValidationResult accepted() {
        return new ValidationResult(true, "VALIDO");
    }

    public static ValidationResult invalid(String mensagem) {
        return new ValidationResult(false, mensagem);
    }
}
