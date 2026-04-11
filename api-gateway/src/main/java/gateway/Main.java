package gateway;

import heartbeat.HeartbeatReceiver;

public class Main {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: java gateway.Main <porta> <protocolo>");
            System.exit(1);
        }
        int porta = Integer.parseInt(args[0]);
        String protocolo = args[1];

        HeartbeatReceiver receiver = HeartbeatReceiver.getInstance(porta, protocolo);
        receiver.start();

        System.out.println("[Gateway] HeartbeatReceiver iniciado na porta " + porta + " usando protocolo " + protocolo);
        // ...restante da lógica do gateway...
    }
}
