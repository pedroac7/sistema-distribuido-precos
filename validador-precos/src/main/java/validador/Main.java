package validador;

import validador.factory.ValidadorServerFactory;
import validador.server.ProtocolServer;
import validador.service.ValidadorService;

import heartbeat.HeartbeatSender;

public class Main {
    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Uso: java validador.Main <businessPort> <gatewayHost> <gatewayHeartbeatPort> <businessProtocol> [heartbeatProtocol]");
            System.exit(1);
        }
        int businessPort = Integer.parseInt(args[0]);
        String gatewayHost = args[1];
        int gatewayHeartbeatPort = Integer.parseInt(args[2]);
        String businessProtocol = args[3];
        String heartbeatProtocol = args.length >= 5 ? args[4] : businessProtocol;

        HeartbeatSender heartbeatSender = new HeartbeatSender(gatewayHost, gatewayHeartbeatPort, businessPort, heartbeatProtocol, "validador");
        Thread heartbeatThread = new Thread(heartbeatSender);
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        System.out.println("[Validador] Heartbeat iniciado com protocolo: " + heartbeatProtocol);
        System.out.println("[Validador] Servidor de negocio configurado com protocolo: " + businessProtocol);
        ValidadorService validadorService = new ValidadorService();
        ProtocolServer businessServer = ValidadorServerFactory.create(businessProtocol, businessPort, validadorService);
        businessServer.start();
    }
}