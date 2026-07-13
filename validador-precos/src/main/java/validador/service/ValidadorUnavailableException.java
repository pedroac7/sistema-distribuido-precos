package validador.service;

public class ValidadorUnavailableException extends RuntimeException {
    public ValidadorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
