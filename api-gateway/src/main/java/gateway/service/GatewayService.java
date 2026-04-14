package gateway.service;

import gateway.client.*;
import gateway.model.*;

import heartbeat.HeartbeatReceiver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

public class GatewayService {
    private static final int SOCKET_TIMEOUT_MS = 2000;

    private final long epsilonMillis;
    private final HeartbeatReceiver heartbeatReceiver;
    private final ValidadorClient validadorClient;
    private final RepositorioClient repositorioClient;
    private final AtomicInteger nextValidadorIndex = new AtomicInteger();

    public GatewayService(long epsilonMillis,
                          HeartbeatReceiver heartbeatReceiver,
                          ValidadorClient validadorClient,
                          RepositorioClient repositorioClient) {
        this.epsilonMillis = epsilonMillis;
        this.heartbeatReceiver = heartbeatReceiver;
        this.validadorClient = validadorClient;
        this.repositorioClient = repositorioClient;
    }

    public static int socketTimeoutMs() {
        return SOCKET_TIMEOUT_MS;
    }

    public GatewayResult process(PrecoPayload preco) {
        List<RemoteEndpoint> validadores = snapshotEndpoints(heartbeatReceiver.getValidadores());
        System.out.println("[Gateway] Validadores vivos: " + formatEndpoints(validadores));
        if (validadores.isEmpty()) {
            return GatewayResult.error(503, "SEM_VALIDADOR_DISPONIVEL");
        }

        ValidationAttempt validationAttempt = validate(preco, validadores);
        if (!validationAttempt.available()) {
            return GatewayResult.error(503, validationAttempt.message());
        }
        if (!validationAttempt.valid()) {
            return GatewayResult.error(400, validationAttempt.message());
        }

        List<RemoteEndpoint> repositorios = snapshotEndpoints(heartbeatReceiver.getRepositorios());
        System.out.println("[Gateway] Repositorios vivos: " + formatEndpoints(repositorios));
        if (repositorios.isEmpty()) {
            return GatewayResult.error(503, "SEM_REPOSITORIO_DISPONIVEL");
        }

        int replicasComSucesso = replicate(preco, repositorios);
        if (replicasComSucesso == 0) {
            return GatewayResult.error(503, "FALHA_AO_REPLICAR");
        }

        long waitMillis = 2 * epsilonMillis;
        System.out.println("[Gateway] Aplicacao do Clock-Bound Wait: " + waitMillis + "ms");
        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GatewayResult.error(503, "INTERRUPTADO_NO_CLOCK_BOUND_WAIT");
        }

        return GatewayResult.success(replicasComSucesso);
    }

    private ValidationAttempt validate(PrecoPayload preco, List<RemoteEndpoint> validadores) {
        int startIndex = Math.floorMod(nextValidadorIndex.getAndIncrement(), validadores.size());
        for (int attempt = 0; attempt < validadores.size(); attempt++) {
            RemoteEndpoint validador = validadores.get((startIndex + attempt) % validadores.size());
            System.out.println("[Gateway] Escolha do validador: " + validador.address());
            try {
                ValidationClientResult result = validadorClient.validar(validador.host(), validador.port(), preco);
                System.out.println("[Gateway] Resultado da validacao em " + validador.address() + ": " + result.mensagem());
                if (result.valid()) {
                    return ValidationAttempt.accepted();
                }
                return ValidationAttempt.invalid(result.mensagem());
            } catch (IOException e) {
                System.out.println("[Gateway] Falha ao chamar validador " + validador.address() + ": " + e.getMessage());
            }
        }
        return ValidationAttempt.unavailable("FALHA_AO_VALIDAR");
    }

    private int replicate(PrecoPayload preco, List<RemoteEndpoint> repositorios) {
        int sucessos = 0;

        for (RemoteEndpoint repositorio : repositorios) {
            System.out.println("[Gateway] Tentativa de replicacao em " + repositorio.address());
            try {
                StorageClientResult result = repositorioClient.armazenar(repositorio.host(), repositorio.port(), preco);
                if (result.success()) {
                    sucessos++;
                    System.out.println("[Gateway] Replicacao com sucesso em " + repositorio.address());
                } else {
                    System.out.println("[Gateway] Falha na replicacao em " + repositorio.address() + ": " + result.mensagem());
                }
            } catch (IOException e) {
                System.out.println("[Gateway] Falha na replicacao em " + repositorio.address() + ": " + e.getMessage());
            }
        }

        System.out.println("[Gateway] Sucessos por repositorio: " + sucessos + "/" + repositorios.size());
        return sucessos;
    }

    private List<RemoteEndpoint> snapshotEndpoints(Map<String, Long> instances) {
        List<RemoteEndpoint> endpoints = new ArrayList<>();
        for (String key : instances.keySet()) {
            RemoteEndpoint endpoint = RemoteEndpoint.fromKey(key);
            if (endpoint != null) {
                endpoints.add(endpoint);
            }
        }
        endpoints.sort(Comparator.comparing(RemoteEndpoint::address));
        return endpoints;
    }

    private String formatEndpoints(List<RemoteEndpoint> endpoints) {
        if (endpoints.isEmpty()) {
            return "[]";
        }

        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (RemoteEndpoint endpoint : endpoints) {
            joiner.add(endpoint.address());
        }
        return joiner.toString();
    }

    private record RemoteEndpoint(String host, int port) {
        private static RemoteEndpoint fromKey(String key) {
            int separatorIndex = key.lastIndexOf(':');
            if (separatorIndex <= 0 || separatorIndex == key.length() - 1) {
                return null;
            }

            try {
                String host = key.substring(0, separatorIndex);
                int port = Integer.parseInt(key.substring(separatorIndex + 1));
                return new RemoteEndpoint(host, port);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private String address() {
            return host + ":" + port;
        }
    }

    private record ValidationAttempt(boolean available, boolean valid, String message) {
        private static ValidationAttempt accepted() {
            return new ValidationAttempt(true, true, "VALIDO");
        }

        private static ValidationAttempt invalid(String message) {
            return new ValidationAttempt(true, false, message);
        }

        private static ValidationAttempt unavailable(String message) {
            return new ValidationAttempt(false, false, message);
        }
    }
}
