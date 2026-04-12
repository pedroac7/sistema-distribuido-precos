package repositorio;

import heartbeat.HeartbeatSender;

public class Main {
    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Uso: java repositorio.Main <businessPort> <gatewayHost> <gatewayHeartbeatPort> <protocolo>");
            System.exit(1);
        }
        int businessPort = Integer.parseInt(args[0]);
        String gatewayHost = args[1];
        int gatewayHeartbeatPort = Integer.parseInt(args[2]);
        String protocolo = args[3];

        HeartbeatSender heartbeatSender = new HeartbeatSender(gatewayHost, gatewayHeartbeatPort, businessPort, protocolo, "repositorio");
        Thread heartbeatThread = new Thread(heartbeatSender);
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        System.out.println("[Repositorio] Heartbeat iniciado com protocolo: " + protocolo);
        RepositorioService repositorioService = new RepositorioService();
        BusinessServer businessServer = RepositorioBusinessServerFactory.create(protocolo, businessPort, repositorioService);
        businessServer.start();
        // ...restante da lógica do serviço...

    }
}
