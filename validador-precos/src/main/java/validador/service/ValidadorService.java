package validador.service;

import validador.model.*;

public class ValidadorService {
    private static final int PROCESSING_DELAY_MILLIS = 50;

    public ValidationResult validar(PrecoPayload preco) {
        ValidationResult delayResult = simulateProcessingDelay();
        if (delayResult != null) {
            return delayResult;
        }

        if (preco == null) {
            return ValidationResult.invalid("JSON_INVALIDO");
        }
        if (preco.ativo() == null || preco.ativo().isBlank()) {
            return ValidationResult.invalid("ATIVO_OBRIGATORIO");
        }
        if (preco.valor() <= 0) {
            return ValidationResult.invalid("VALOR_INVALIDO");
        }
        if (preco.timestamp() <= 0) {
            return ValidationResult.invalid("TIMESTAMP_INVALIDO");
        }
        return ValidationResult.accepted();
    }

    private ValidationResult simulateProcessingDelay() {
        try {
            Thread.sleep(PROCESSING_DELAY_MILLIS);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ValidadorUnavailableException("VALIDACAO_INTERROMPIDA", e);
        }
    }
}
