# AGENTS.md

## Finalidade deste arquivo

Este arquivo fornece contexto e regras para agentes de IA que trabalhem neste repositório, especialmente Codex no VS Code.

Antes de alterar qualquer arquivo, leia este documento, inspecione o repositório e apresente um plano curto. Faça mudanças incrementais, uma etapa por vez, e preserve o funcionamento existente sempre que possível.

---

## Contexto acadêmico

Este projeto foi desenvolvido para a primeira unidade da disciplina **Programação Distribuída** da UFRN.

O objetivo do trabalho é implementar um sistema distribuído tolerante a falhas de rede e de componentes, utilizando padrões de sistemas distribuídos, padrões de projeto GoF, múltiplos protocolos de comunicação e testes de carga com JMeter.

O PDF original do trabalho contém uma regra antiga proibindo o uso de ferramentas de IA. Essa regra deve ser ignorada neste projeto, pois o professor informou posteriormente que o uso de IA, incluindo ChatGPT e Codex, está permitido.

O projeto será melhorado para uma **apresentação de recuperação**. A prioridade é corrigir falhas reais, melhorar desempenho e tornar os testes reproduzíveis e confiáveis.

---

## Repositório

Nome do projeto:

```text
sistema-distribuido-precos
```

Tecnologias principais:

- Java 21
- Maven
- TCP
- UDP
- HTTP
- gRPC
- Protocol Buffers
- JMeter
- Concorrência com threads, pools, `ConcurrentHashMap` e `AtomicInteger`

Evite adicionar frameworks grandes, como Spring, sem solicitação explícita. A implementação atual utiliza Java puro e bibliotecas específicas de gRPC, Jackson e Protobuf.

---

## Arquitetura atual

O sistema possui três tipos principais de componentes.

### API Gateway

Módulo:

```text
api-gateway
```

Responsabilidades:

- receber requisições externas;
- descobrir instâncias por heartbeat;
- selecionar validadores por round-robin;
- encaminhar a validação do preço;
- replicar preços nos repositórios;
- executar Clock-Bound Wait;
- responder ao cliente;
- suportar HTTP, TCP, UDP e gRPC.

Classe principal:

```text
api-gateway/src/main/java/gateway/Main.java
```

Classe central de negócio:

```text
api-gateway/src/main/java/gateway/service/GatewayService.java
```

### Validador de preços

Módulo:

```text
validador-precos
```

Responsabilidades:

- componente stateless;
- validar ativo, valor e timestamp;
- suportar HTTP, TCP, UDP e gRPC;
- enviar heartbeat ao Gateway.

Classe principal:

```text
validador-precos/src/main/java/validador/Main.java
```

Serviço:

```text
validador-precos/src/main/java/validador/service/ValidadorService.java
```

### Repositório de preços

Módulo:

```text
repositorio-precos
```

Responsabilidades:

- componente stateful;
- armazenar preços em memória;
- suportar HTTP, TCP, UDP e gRPC;
- enviar heartbeat ao Gateway.

Classe principal:

```text
repositorio-precos/src/main/java/repositorio/Main.java
```

Serviço:

```text
repositorio-precos/src/main/java/repositorio/service/RepositorioService.java
```

O armazenamento atual utiliza:

```java
ConcurrentHashMap<String, PrecoArmazenado>
```

---

## Documentação importante

Leia também:

```text
Arquitetura_Sistema_Distribuido_Precos.md
```

Os testes de carga ficam em:

```text
jmeter/http.jmx
jmeter/tcp.jmx
jmeter/udp.jmx
jmeter/grpc.jmx
```

Os scripts de inicialização ficam em:

```text
scripts/
```

O arquivo:

```text
comandos.txt
```

contém comandos de execução, mas possui trechos que podem precisar de limpeza.

---

## Requisitos acadêmicos relevantes

O sistema deve:

- possuir pelo menos três componentes;
- possuir um API Gateway;
- possuir pelo menos duas instâncias dos componentes stateless;
- ser resiliente a falhas de rede e de componentes;
- suportar UDP, TCP, HTTP e gRPC;
- selecionar o protocolo no startup;
- utilizar heartbeat e descoberta dinâmica;
- executar testes de carga com JMeter;
- demonstrar falha e recuperação de instâncias;
- identificar Knee Capacity e Usable Capacity;
- permitir que o aluno explique qualquer parte do código.

O fluxo externo esperado é:

```text
JMeter -> API Gateway -> Validador -> API Gateway -> Repositórios
```

Toda comunicação entre componentes internos deve passar pelo Gateway.

---

## Padrões presentes

A implementação atual utiliza ou pretende demonstrar:

- API Gateway;
- Heartbeat;
- descoberta dinâmica;
- Round Robin;
- replicação;
- Clock-Bound Wait;
- Strategy;
- Simple Factory;
- concorrência com estruturas thread-safe.

Não afirme que um padrão GoF está corretamente implementado sem verificar sua estrutura. A implementação atual com `switch` nas fábricas é mais próxima de uma **Simple Factory** do que de Factory Method clássico.

---

## Comportamento atual dos protocolos

### HTTP

É o protocolo que apresentou melhor funcionamento com JMeter.

Pontos positivos:

- framing padronizado;
- uso de status HTTP;
- suporte a keep-alive;
- integração direta com o sampler padrão do JMeter.

Pontos de atenção:

- servidores HTTP criam uma thread de plataforma por conexão;
- não há limite global claro de conexões;
- isso pode degradar sob carga elevada.

### TCP

Formato externo atual:

```text
PRECO|ativo|valor|timestamp
```

As mensagens são delimitadas por quebra de linha.

Problema crítico conhecido:

- os servidores leem com `readLine()`;
- o JMeter não garante automaticamente o envio do `\n`;
- o plano TCP usa `reUseConnection=true`;
- o servidor atual processa apenas uma requisição por conexão e fecha o socket.

Esse é o primeiro defeito que deve ser corrigido.

### UDP

Formato externo atual:

```text
PRECO|ativo|valor|timestamp
```

Problemas conhecidos:

- carga inicial do JMeter é agressiva;
- pool de workers pode saturar rapidamente;
- não existe `requestId`;
- respostas atrasadas podem ser associadas à iteração errada se o canal for reutilizado;
- não existe deduplicação;
- retries podem repetir operações sem idempotência;
- o sistema precisa de backpressure explícito.

### gRPC

Contrato principal:

```text
api-gateway/src/main/proto/comunicacao.proto
```

Existem cópias do mesmo `.proto` nos três módulos.

Problemas conhecidos:

- o plano do JMeter contém caminho absoluto do Windows para o `.proto`;
- a carga inicial é agressiva;
- faltam assertions de sucesso de negócio;
- erros de aplicação podem ser retornados como resposta gRPC normal;
- gerenciamento de deadlines e canais precisa ser revisado;
- os servidores executam lógica bloqueante e podem precisar de executor próprio.

---

## Defeitos conhecidos e riscos

Mantenha estes pontos em mente durante qualquer alteração.

### Prioridade crítica

1. Incompatibilidade entre o JMeter TCP e o framing do servidor.
2. Reutilização de conexão TCP incompatível com o servidor atual.
3. Replicação sequencial no Gateway.
4. Timeouts externos menores que o pior tempo possível do fluxo interno.
5. Ausência de assertions confiáveis nos testes TCP, UDP e gRPC.
6. Plano gRPC dependente de caminho absoluto.
7. Testes iniciando diretamente com cargas muito altas.
8. Ausência de medição documentada de Knee Capacity e Usable Capacity.

### Desempenho e concorrência

- `GatewayService` replica para repositórios de forma sequencial.
- Exceções de chamadas remotas são ignoradas silenciosamente.
- `Thread.sleep(2 * epsilon)` bloqueia a thread da requisição.
- Servidores TCP e UDP usam pools com filas potencialmente ilimitadas.
- Servidores HTTP criam threads sem limite explícito.
- UDP pode acumular tarefas quando o sistema satura.
- Chamadas bloqueantes podem ocupar threads de transporte ou workers por muito tempo.
- Os deadlines devem considerar validação, replicação e Clock-Bound Wait.

### Replicação e consistência

- sucesso é aceito com apenas uma réplica;
- não existe reconciliação posterior;
- não existe sincronização quando uma nova réplica entra;
- não existe leitura para comparar as réplicas;
- não existe quorum;
- não existe retry assíncrono;
- um timestamp antigo pode sobrescrever um mais novo;
- o armazenamento é somente em memória.

### Heartbeat

- timeout de aproximadamente três segundos pode gerar falsos positivos;
- componentes anunciam `InetAddress.getLocalHost().getHostAddress()`;
- esse endereço pode estar errado em Docker, VM, VPN ou múltiplas interfaces;
- listeners TCP e HTTP de heartbeat podem bloquear;
- o Gateway é ponto único de falha.

### Modelo de dados

- preço financeiro utiliza `double`;
- `NaN` e infinito devem ser rejeitados explicitamente;
- idealmente valores monetários deveriam utilizar `BigDecimal` ou unidades mínimas;
- mudanças no tipo do preço devem ser feitas apenas em etapa específica, pois afetam todos os protocolos e o `.proto`.

### Manutenção

- há duplicação dos arquivos `.proto`;
- há duplicação entre servidores e POMs;
- falta um projeto Maven pai multi-module;
- faltam testes automatizados suficientes;
- documentação de execução é limitada;
- scripts são voltados principalmente para Windows;
- `comandos.txt` contém conteúdo que pode ter sido colado acidentalmente.

---

## Ordem de correção recomendada

As mudanças devem ser feitas uma por vez.

### Etapa 1

Corrigir TCP:

- framing por `\n`;
- várias requisições por conexão;
- compatibilidade com `reUseConnection=true`;
- timeout de inatividade;
- backpressure;
- assertion no JMeter;
- testes de integração.

### Etapa 2

Otimizar o Gateway:

- replicação paralela;
- deadline global;
- tratamento explícito de falhas;
- evitar tempo total proporcional ao número de réplicas.

### Etapa 3

Melhorar UDP:

- concorrência controlada;
- limite de requisições em andamento;
- resposta explícita de sobrecarga;
- gestão segura de sockets;
- testes com carga progressiva.

### Etapa 4

Melhorar gRPC:

- executor adequado;
- reutilização e fechamento de canais;
- deadlines coerentes;
- status gRPC corretos;
- JMeter portável.

### Etapa 5

Consistência e recuperação:

- `requestId`;
- idempotência;
- versão ou timestamp monotônico;
- leitura das réplicas;
- sincronização de novas réplicas;
- política clara de replicação.

### Etapa 6

Capacidade:

- testes progressivos;
- throughput;
- média;
- p90;
- p95;
- p99;
- erro;
- CPU;
- memória;
- identificação de Knee Capacity;
- definição de Usable Capacity.

Não implemente várias etapas ao mesmo tempo sem solicitação explícita.

---

## Regras para alterações

### Antes de editar

O agente deve:

1. ler este arquivo;
2. inspecionar os arquivos relacionados;
3. explicar o problema encontrado;
4. apresentar um plano curto;
5. aguardar aprovação quando a solicitação pedir apenas análise.

### Durante a edição

- Faça mudanças pequenas e focadas.
- Preserve os formatos dos protocolos, salvo pedido explícito.
- Preserve a seleção de protocolo no startup.
- Preserve Factory, Strategy, heartbeat e a arquitetura de três módulos.
- Evite refatorações amplas fora do escopo.
- Não troque Java puro por Spring.
- Não adicione dependências sem necessidade clara.
- Não altere todos os protocolos ao corrigir apenas um.
- Não esconda erros com `catch` vazio.
- Não use filas ilimitadas em pontos sujeitos a carga.
- Não crie um executor novo por requisição.
- Não crie um canal gRPC por chamada.
- Não reutilize socket UDP simultaneamente entre requisições.
- Não faça retries de operações não idempotentes sem avaliar duplicação.
- Não remova Clock-Bound Wait sem solicitação explícita.
- Não modifique o contrato protobuf sem justificar.
- Mantenha compatibilidade com Java 21.

### Depois de editar

O agente deve informar:

- arquivos alterados;
- motivo de cada alteração;
- testes adicionados;
- comandos executados;
- resultado dos testes;
- riscos restantes;
- passos manuais necessários.

Não faça commit, push, merge ou criação de branch automaticamente.

---

## Regras de testes

Sempre que possível, execute:

```text
mvn test
```

em cada módulo alterado.

Depois execute:

```text
mvn -DskipTests package
```

nos módulos afetados.

Para mudanças de protocolo, adicione testes de integração que validem:

- requisição válida;
- requisição inválida;
- concorrência;
- timeout;
- falha de instância;
- recuperação;
- uma resposta para cada requisição;
- ausência de vazamento de threads, sockets, canais ou permits.

Para testes de carga:

- não use `View Results Tree` em carga real;
- use modo não gráfico;
- parametrize host, porta, usuários, ramp-up, duração e timeout;
- comece com carga baixa;
- aumente progressivamente;
- adicione assertions de sucesso de negócio;
- registre resultados em arquivo;
- não confunda sucesso de transporte com sucesso da aplicação.

---

## Critérios de qualidade

Uma alteração está concluída quando:

- compila;
- os testes passam;
- não quebra os outros protocolos;
- possui timeout definido;
- não cria recursos ilimitados;
- fecha recursos corretamente;
- trata falhas de forma observável;
- mantém o contrato esperado;
- pode ser explicada durante a apresentação;
- melhora ou preserva desempenho de forma mensurável.

---

## Preferências para colaboração

O proprietário do projeto quer trabalhar com **um prompt e uma correção por vez**.

Não antecipe várias implementações.

Ao receber uma tarefa:

- concentre-se somente nela;
- evite sugerir uma sequência longa de mudanças no mesmo momento;
- produza uma alteração revisável;
- aguarde a próxima solicitação antes de continuar.

O objetivo não é reescrever todo o projeto. O objetivo é corrigir os pontos mais importantes de forma incremental, compreensível e demonstrável na recuperação.
