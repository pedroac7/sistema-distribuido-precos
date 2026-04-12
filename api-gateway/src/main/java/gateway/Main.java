package gateway;

import gateway.client.*;
import gateway.factory.*;
import gateway.server.*;
import gateway.service.GatewayService;

import heartbeat.HeartbeatReceiver;

public class Main {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: java gateway.Main <businessPort> <heartbeatPort> [businessProtocol] [epsilonMs] [heartbeatProtocol]");
            System.exit(1);
        }

        int businessPort = Integer.parseInt(args[0]);
        int heartbeatPort = Integer.parseInt(args[1]);
        String businessProtocol = args.length >= 3 ? args[2] : "tcp";
        long epsilonMillis = args.length >= 4 ? Math.max(0L, Long.parseLong(args[3])) : 100L;
        String heartbeatProtocol = args.length >= 5 ? args[4] : businessProtocol;

        HeartbeatReceiver receiver = HeartbeatReceiver.getInstance(heartbeatPort, heartbeatProtocol);
        receiver.start();

        System.out.println("[Gateway] HeartbeatReceiver iniciado na porta " + heartbeatPort + " usando protocolo " + heartbeatProtocol);
        System.out.println("[Gateway] Servidor de negocio configurado com protocolo " + businessProtocol);
        System.out.println("[Gateway] EPSILON configurado em " + epsilonMillis + "ms");

        ValidadorClient validadorClient = ValidadorClientFactory.create(businessProtocol, GatewayService.socketTimeoutMs());
        RepositorioClient repositorioClient = RepositorioClientFactory.create(businessProtocol, GatewayService.socketTimeoutMs());
        GatewayService gatewayService = new GatewayService(epsilonMillis, receiver, validadorClient, repositorioClient);
        ProtocolServer businessServer = GatewayServerFactory.create(businessProtocol, businessPort, gatewayService);
        businessServer.start();
    }
}