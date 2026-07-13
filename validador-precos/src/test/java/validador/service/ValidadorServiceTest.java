package validador.service;

import org.junit.jupiter.api.Test;
import validador.model.PrecoPayload;
import validador.model.ValidationResult;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidadorServiceTest {

    @Test
    void validacaoAplicaAtrasoFixoDeProcessamento() {
        ValidadorService service = new ValidadorService();
        long startNanos = System.nanoTime();

        ValidationResult result = service.validar(new PrecoPayload("BTC", 65000.0, 1712250000L));

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertTrue(result.valid());
        assertTrue(elapsedMillis >= 45, "validacao deveria levar pelo menos aproximadamente 50ms");
    }

    @Test
    void interrupcaoDuranteAtrasoPreservaFlagERetornaIndisponibilidadeTemporaria() {
        ValidadorService service = new ValidadorService();
        Thread.currentThread().interrupt();
        try {
            ValidadorUnavailableException failure = assertThrows(
                    ValidadorUnavailableException.class,
                    () -> service.validar(new PrecoPayload("BTC", 65000.0, 1712250000L)));

            assertEquals("VALIDACAO_INTERROMPIDA", failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
