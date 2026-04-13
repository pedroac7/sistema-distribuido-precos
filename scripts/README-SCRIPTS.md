# Scripts de Apresentacao

Os scripts desta pasta facilitam a subida do sistema distribuído no Windows para a apresentação, sem alterar a lógica do projeto.

## Como executar

Abra um terminal na raiz do repositório e rode um dos scripts abaixo:

```bat
scripts\start-http.bat
scripts\start-tcp.bat
scripts\start-udp.bat
scripts\start-grpc.bat
```

Para encerrar todas as janelas abertas pelos scripts de apresentação:

```bat
scripts\stop-all.bat
```

## O que cada script de start abre

Cada script sobe 5 janelas separadas:

- 1 gateway
- 2 validadores
- 2 repositorios

Os títulos das janelas deixam claro qual instância esta rodando. Exemplos:

- `GATEWAY_HTTP`
- `VALIDADOR1_HTTP`
- `VALIDADOR2_HTTP`
- `REPOSITORIO1_HTTP`
- `REPOSITORIO2_HTTP`

O mesmo padrão vale para `TCP`, `UDP` e `GRPC`.

## Derrubar uma instância específica

Durante a apresentação, para derrubar manualmente uma instância específica, basta fechar a janela correspondente.

## Portas usadas

- Gateway business: `8080`
- Gateway heartbeat: `9090`
- Validador 1 business: `8081`
- Validador 2 business: `8084`
- Repositório 1 business: `8082`
- Repositório 2 business: `8083`
- Gateway host: `127.0.0.1`
- Epsilon: `100`

## Observações

- Os scripts assumem que os módulos já foram compilados.
- Os scripts assumem que `target\classes` e `target\dependency\*` ja existem em cada modulo.
- Cada processo abre em sua própria janela `cmd`, o que facilita acompanhar logs e fechar instâncias individualmente.
