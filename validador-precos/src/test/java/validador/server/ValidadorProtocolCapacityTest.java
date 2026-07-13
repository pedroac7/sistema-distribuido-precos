package validador.server;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValidadorProtocolCapacityTest {

    @Test
    void protocolosDoValidadorPossuemCapacidadeDeValidacaoConfigurada() throws Exception {
        assertValidationCapacity(TcpValidadorServer.class, 8, 40);
        assertValidationCapacity(HttpValidadorServer.class, 15, 40);
        assertValidationCapacity(UdpValidadorServer.class, 15, 40);
        assertValidationCapacity(GrpcValidadorServer.class, 15, 50);
    }

    private static void assertValidationCapacity(
            Class<?> serverClass,
            int expectedWorkerPoolSize,
            int expectedQueueCapacity) throws Exception {
        assertEquals(expectedWorkerPoolSize, privateStaticInt(serverClass, "VALIDATION_WORKER_POOL_SIZE"));
        assertEquals(expectedQueueCapacity, privateStaticInt(serverClass, "VALIDATION_QUEUE_CAPACITY"));
        assertEquals(2000, privateStaticInt(serverClass, "VALIDATION_RESULT_TIMEOUT_MS"));
    }

    private static int privateStaticInt(Class<?> targetClass, String fieldName) throws Exception {
        Field field = targetClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }
}
