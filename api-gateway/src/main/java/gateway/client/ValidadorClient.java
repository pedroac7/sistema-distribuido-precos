package gateway.client;

import gateway.model.*;
import gateway.transport.*;

import java.io.IOException;

public interface ValidadorClient {
    ValidationClientResult validar(String host, int port, PrecoPayload preco) throws IOException;
}
