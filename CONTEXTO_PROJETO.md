# Contexto Geral do Projeto: Sistema Distribuído de Preços
**Disciplina:** Programação Distribuída - UFRN (Prof. Nélio Cacho)
**Linguagem:** Java 21 puro (Java SE). 
**Restrição Máxima:** É estritamente proibido usar frameworks como Spring Boot ou classes de alto nível prontas como `HttpServer` ou `HttpClient`. Tudo deve ser feito usando Sockets e I/O básicos.

## 1. Arquitetura
O sistema é dividido em 3 projetos independentes construídos com Maven, rodando em processos distintos no terminal:
* **api-gateway:** Recebe requisições HTTP do JMeter, orquestra a chamada para o validador, depois para os repositórios, aplica o padrão "Clock-Bound Wait" e responde ao JMeter.
* **validador-precos (Stateless):** Recebe os dados do gateway, valida se os valores estão corretos (ex: preço > 0) e devolve "OK".
* **repositorio-precos (Stateful):** Recebe o dado do gateway e armazena em memória (ex: `ConcurrentHashMap`). Haverá duas instâncias rodando simultaneamente para replicação.

## 2. O Desafio "Poliglota" (Interface de Comunicação)
Todas as 3 entidades devem conseguir se comunicar usando 4 protocolos diferentes, definidos por argumentos de linha de comando na inicialização (ex: `--proto udp --port 8080`).
* **UDP:** Implementado com `DatagramSocket` e `DatagramPacket`.
* **TCP:** Implementado com `ServerSocket` e `Socket` (streams de bytes/strings).
* **HTTP 1.1 "Na Mão":** Usando `ServerSocket` (TCP puro), mas fazendo o *parse* manual da String da primeira linha (ex: `POST / HTTP/1.1`) e respondendo com o formato de texto HTTP válido.
* **gRPC:** Única exceção ao "fazer na mão". Usaremos as bibliotecas oficiais do gRPC via arquivo `.proto`.

## 3. Padrão de Projeto: Clock-Bound Wait (Capítulo 24)
O `api-gateway` deve implementar este padrão.
* Quando o gateway envia a requisição de escrita para as instâncias do `repositorio-precos`, ele NÃO pode responder imediatamente ao JMeter.
* Ele deve calcular um tempo de espera obrigatório: `Thread.sleep(2 * EPSILON)`, onde `EPSILON` é a incerteza simulada do relógio (ex: 100ms).
* Só após esse "Wait", ele retorna o status 200 OK.

## 4. Tolerância a Falhas e Heartbeat
* As instâncias `validador-precos` e `repositorio-precos` devem ter uma thread rodando em background que envia um "Ping" (estou vivo) para o IP/Porta do Gateway a cada 2 segundos.
* O Gateway mantém uma lista de instâncias ativas (com timestamp do último ping).
* Se uma instância de repositório cair, o Gateway deve ignorá-la e continuar replicando na instância sobrevivente. O JMeter deve registrar 0% de erro (Availability).

## 5. Estrutura de Dados Transmitida
Para facilitar os sockets puros, os dados trafegados (exceto no gRPC) podem ser Strings simples ou JSON parseado via Jackson. 
Estrutura base: `{ "ativo": "BTC", "valor": 65000.0, "timestamp": 1712250000 }`