package gateway;

import heartbeat.HeartbeatReceiver;

public class Main {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: java gateway.Main <businessPort> <heartbeatPort> [protocolo] [epsilonMs]");
            System.exit(1);
        }
        int businessPort = Integer.parseInt(args[0]);
        int heartbeatPort = Integer.parseInt(args[1]);
        String businessProtocol = args.length >= 3 ? args[2] : "tcp";
        long epsilonMillis = args.length >= 4 ? Math.max(0L, Long.parseLong(args[3])) : 100L;

        HeartbeatReceiver receiver = HeartbeatReceiver.getInstance(heartbeatPort, businessProtocol);
        receiver.start();

        System.out.println("[Gateway] HeartbeatReceiver iniciado na porta " + heartbeatPort + " usando protocolo " + businessProtocol);
        System.out.println("[Gateway] EPSILON configurado em " + epsilonMillis + "ms");

        ValidadorClient validadorClient = ValidadorClientFactory.create(businessProtocol, GatewayService.socketTimeoutMs());
        RepositorioClient repositorioClient = RepositorioClientFactory.create(businessProtocol, GatewayService.socketTimeoutMs());
        GatewayService gatewayService = new GatewayService(epsilonMillis, receiver, validadorClient, repositorioClient);
        BusinessServer businessServer = GatewayBusinessServerFactory.create(businessProtocol, businessPort, gatewayService);
        businessServer.start();
        // ...restante da lógica do gateway...
    }
}
