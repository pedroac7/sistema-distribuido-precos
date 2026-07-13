package gateway.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GrpcValidadorClientTest {

    @Test
    void usaMenorTimeoutEntreConfiguradoERestanteDaValidacao() throws Exception {
        GrpcValidadorClient client = new GrpcValidadorClient(1000);

        assertEquals(120, client.effectiveTimeoutMillis(120));
        assertEquals(1000, client.effectiveTimeoutMillis(1500));
    }

    @Test
    void rejeitaDeadlineRestanteEsgotado() {
        GrpcValidadorClient client = new GrpcValidadorClient(1000);

        IOException failure = assertThrows(IOException.class, () -> client.effectiveTimeoutMillis(0));

        assertEquals("TIMEOUT_GRPC", failure.getMessage());
    }
}
