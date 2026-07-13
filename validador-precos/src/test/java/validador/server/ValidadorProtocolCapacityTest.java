package validador.server;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValidadorProtocolCapacityTest {

    @Test
    void protocolosDoValidadorPossuemCapacidadeDeValidacaoConfigurada() throws Exception {
        assertValidationCapacity(TcpValidadorServer.class, 8);
        assertValidationCapacity(HttpValidadorServer.class, 15);
        assertValidationCapacity(UdpValidadorServer.class, 15);
        assertValidationCapacity(GrpcValidadorServer.class, 15);
    }

    private static void assertValidationCapacity(Class<?> serverClass, int expectedWorkerPoolSize) throws Exception {
        assertEquals(expectedWorkerPoolSize, privateStaticInt(serverClass, "VALIDATION_WORKER_POOL_SIZE"));
        assertEquals(40, privateStaticInt(serverClass, "VALIDATION_QUEUE_CAPACITY"));
        assertEquals(1500, privateStaticInt(serverClass, "VALIDATION_RESULT_TIMEOUT_MS"));
    }

    private static int privateStaticInt(Class<?> targetClass, String fieldName) throws Exception {
        Field field = targetClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }
}
