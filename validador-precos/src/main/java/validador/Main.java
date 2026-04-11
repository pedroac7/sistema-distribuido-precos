package validador;

import heartbeat.HeartbeatSender;

public class Main {
    public static void main(String[] args) {
        if (args.length < 5) {
            System.out.println("Uso: java validador.Main <gatewayHost> <gatewayPort> <localPort> <protocolo> <tipoEntidade>");
            System.exit(1);
        }
        String gatewayHost = args[0];
        int gatewayPort = Integer.parseInt(args[1]);
        int localPort = Integer.parseInt(args[2]);
        String protocolo = args[3];
        String tipoEntidade = args[4]; // "validador"

        HeartbeatSender heartbeatSender = new HeartbeatSender(gatewayHost, gatewayPort, localPort, protocolo, tipoEntidade);
        Thread heartbeatThread = new Thread(heartbeatSender);
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        System.out.println("[Validador] Heartbeat iniciado com protocolo: " + protocolo);
        // ...restante da lógica do serviço...


    }
}
