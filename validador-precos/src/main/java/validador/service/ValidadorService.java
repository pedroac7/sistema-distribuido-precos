package validador.service;

import validador.model.*;

public class ValidadorService {
    public ValidationResult validar(PrecoPayload preco) {
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
}
