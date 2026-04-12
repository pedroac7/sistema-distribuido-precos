package repositorio;

import repositorio.factory.RepositorioServerFactory;
import repositorio.server.ProtocolServer;
import repositorio.service.RepositorioService;

import heartbeat.HeartbeatSender;

public class Main {
    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Uso: java repositorio.Main <businessPort> <gatewayHost> <gatewayHeartbeatPort> <businessProtocol> [heartbeatProtocol]");
            System.exit(1);
        }
        int businessPort = Integer.parseInt(args[0]);
        String gatewayHost = args[1];
        int gatewayHeartbeatPort = Integer.parseInt(args[2]);
        String businessProtocol = args[3];
        String heartbeatProtocol = args.length >= 5 ? args[4] : businessProtocol;

        HeartbeatSender heartbeatSender = new HeartbeatSender(gatewayHost, gatewayHeartbeatPort, businessPort, heartbeatProtocol, "repositorio");
        Thread heartbeatThread = new Thread(heartbeatSender);
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        System.out.println("[Repositorio] Heartbeat iniciado com protocolo: " + heartbeatProtocol);
        System.out.println("[Repositorio] Servidor de negocio configurado com protocolo: " + businessProtocol);
        RepositorioService repositorioService = new RepositorioService();
        ProtocolServer businessServer = RepositorioServerFactory.create(businessProtocol, businessPort, repositorioService);
        businessServer.start();
    }
}