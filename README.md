# Serviço de Ativação de Produtos (`activation-service`)

Este projeto é a minha implementação para o desafio técnico do serviço de ativação de produtos de clientes, construído com **Java 21** e **Spring Boot**.

A ideia principal durante o desenvolvimento foi entregar uma solução simples, direta e fácil de manter, focando nos pontos mais importantes do desafio: **controle de duplicidade/concorrência**, **idempotência** e **processamento assíncrono sem segurar conexão de banco aberta**.

---

## 1. Como Executar o Projeto

### Pré-requisitos
- **Java 21** (caso queira rodar fora do Docker)
- **Docker e Docker Compose**

### Opção 1: Subir tudo com Docker Compose (Aplicação + PostgreSQL)
Na raiz do projeto (`activation-service`), basta rodar:
```bash
docker compose up --build -d
```
- Aplicação disponível em: `http://localhost:8090`
- PostgreSQL na porta `5432` (banco `activation_db`, usuário `postgres`, senha `postgres`).

*(Usei a porta `8090` para evitar conflito com outros serviços locais na `8080`).*

### Opção 2: Rodar pela IDE (IntelliJ) ou Maven
1. Suba apenas o banco pelo Docker:
   ```bash
   docker compose up -d postgres
   ```
2. Inicie a aplicação pela IDE (`ActivationServiceApplication`) ou via terminal:
   ```bash
   ./mvnw spring-boot:run
   ```

### Como Rodar os Testes Automatizados
Os testes usam o **H2 em memória no modo PostgreSQL** com Flyway ativo (não precisa de Docker rodando):
```bash
./mvnw test
```

---

## 2. Testando a API (Postman / cURL)

### Criar uma solicitação (`POST /api/v1/requests`)
```bash
curl -i -X POST http://localhost:8090/api/v1/requests \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "EXT-12345",
    "customerId": "CUST-10001",
    "productCode": "INTERNET-500"
  }'
```

### Consultar por ID (`GET /api/v1/requests/{id}`)
```bash
curl -i http://localhost:8090/api/v1/requests/{id}
```

### Listar solicitações paginadas com filtros (`GET /api/v1/requests`)
```bash
curl -i "http://localhost:8090/api/v1/requests?status=COMPLETED&customerId=CUST-10001&page=0&size=10"
```

> **Simulando cenários do Provedor Externo (`MockProviderController`):**  
> Criei um endpoint simulado na rota `/provider/v1/activate`. Pelo `productCode` enviado no `POST`, é possível testar cada retorno:
> - **Produto normal** (ex.: `"INTERNET-500"`): responde `200 OK` e muda para **`COMPLETED`**.
> - **Iniciando com `"ERR-400"`**: simula `400 Bad Request` e vai direto para **`FAILED`**.
> - **Iniciando com `"ERR-500"`**: simula erro `500`, tenta 3 vezes e marca **`FAILED`**.
> - **Iniciando com `"ERR-TIMEOUT"`**: demora 3s (acima do timeout de 2s do cliente), tratando como falha temporária.

---

## 3. Decisões Técnicas

### 3.1. Duplicidade e Concorrência (Item 3)
Fazer apenas um `SELECT` antes do `INSERT` não resolve requisições simultâneas, pois duas threads podem checar que o `externalId` não existe no mesmo milissegundo e duplicar o registro. Para resolver isso:
- **No banco:** Criei uma constraint `UNIQUE (external_id)` via Flyway e usei `@Version` (Optimistic Locking) na entidade para proteger atualizações de status.
- **No código:** Uso `saveAndFlush` em transação isolada (`REQUIRES_NEW`). Se duas requisições chegarem juntas, a primeira insere (`201 Created`) e a segunda cai no `catch (DataIntegrityViolationException)`, busca o registro recém-criado em uma nova transação e retorna `200 OK` com o mesmo registro (ou `409 Conflict` se o `productCode`/`customerId` for diferente).

### 3.2. Integração com o Provedor Externo (Item 5)
Para não travar o `POST` esperando o provedor externo e **não manter transação de banco aberta durante chamada HTTP**, usei um worker assíncrono com `@Scheduled` (`ActivationWorker`) que roda a cada 2s, muda o status para `PROCESSING`, chama o provedor via `RestClient` (timeout de 2s e header `Idempotency-Key`) e atualiza o status final:

| Retorno do Provedor | Novo Status | Nova Tentativa? | Justificativa |
| :--- | :--- | :--- | :--- |
| **Sucesso (`2xx`)** | `COMPLETED` | Não | Produto ativado com sucesso. |
| **`HTTP 400`** | `FAILED` | Não | Erro nos dados enviados; repetir a chamada daria o mesmo erro. |
| **`HTTP 500`** | `PENDING` (até 3x) → `FAILED` | Sim (até 3x) | Falha temporária no servidor do provedor. |
| **`Timeout`** | `PENDING` (até 3x) → `FAILED` | Sim (até 3x) | Usa `Idempotency-Key` (`externalId`) para retentar sem risco de ativar duas vezes. |

### 3.3. Erros, Índices e Segurança (Itens 4, 6 e 7)
- **Erros e Logs:** Tratamento global com `ProblemDetail` (RFC 9457) sem expor stack trace, e log das operações/erros no arquivo `logs/activation-audit.log` (`[SERVICE]`).
- **Índices SQL:** Além do índice `UNIQUE` em `external_id`, criei índices em `(status, created_at)` para o Worker e `(customer_id, status)` para a listagem paginada.
- **Segurança (Item 7):** Deixei os endpoints abertos para facilitar os testes locais. Em produção, usaria **Spring Security OAuth2 Resource Server** com o fluxo **`Client Credentials`** (comunicação entre sistemas), validando assinatura via JWKS, `iss`, `aud` e `exp` do JWT, separando escopos (`SCOPE_requests:write` para `POST` e `SCOPE_requests:read` para `GET`) e guardando credenciais do provedor em cofre de segredos (AWS Secrets Manager / Vault).

---

## 4. Cenário de Análise em Produção (Item 10)

**Sintomas:** API subiu de `300 ms → 2,5 s`, banco de `50 ms → 1,2 s`, serviço externo de `400 ms → 3 s`, mas **CPU (45%) e Memória (60%) continuam normais**.

- **O que investigar primeiro e causa comum:**  
  CPU e memória baixas com lentidão geral indicam **exaustão do pool de conexões do banco (HikariCP)**. Quando o serviço externo passou a demorar `3 s`, se a chamada HTTP estiver dentro de um `@Transactional`, cada requisição segura uma conexão do banco aberta por 3 segundos esperando a rede. Com poucas chamadas simultâneas, as conexões do pool acabam e até consultas simples ficam na fila esperando conexão liberar (`50 ms → 1,2 s`). Também checaria falta de índices na tabela.
- **Métricas e logs úteis:**  
  Métricas do HikariCP (`hikaricp.connections.active`, `pending` e tempo de `acquire`), conexões `idle in transaction` no PostgreSQL e `EXPLAIN ANALYZE` nas queries lentas.
- **Melhoria e validação:**  
  Tirar a chamada HTTP de dentro da transação de banco, processar de forma assíncrona e configurar timeout curto no cliente HTTP (como já foi feito nesta solução). Após o deploy, validaria se as conexões pendentes do HikariCP zeraram e se a latência da API e do banco voltou ao normal.

---

## 5. Produção, Pontos Pendentes e Melhorias Futuras

- **Execução em Produção:** A aplicação já possui `Dockerfile` multi-stage e `docker-compose.yml`, expondo healthcheck via Actuator (`/actuator/health`). Com múltiplas instâncias rodando juntas, a unicidade é garantida pelo `UNIQUE` no PostgreSQL e a concorrência no Worker pelo `@Version`.
- **Pontos Pendentes e Evolução:** Implementar o filtro ativo do Resource Server JWT; substituir o `@Scheduled` por fila de mensageria (RabbitMQ/Kafka) para escalar melhor e notificar outros microsserviços quando uma ativação terminar; e adicionar backoff exponencial / Circuit Breaker (Resilience4j).

---

## 6. Uso de IA

Utilizei IA como apoio para discutir as decisões de arquitetura (optando por uma solução mais enxuta com `@Scheduled` em vez de mensageria complexa), estruturar o teste de concorrência com `CountDownLatch` e revisar a documentação. Todo o código e fluxos foram revisados e testados manualmente via Docker e Postman.
