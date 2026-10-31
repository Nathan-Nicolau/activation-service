package com.deroyque.challenge.activationservice.controller;

import com.deroyque.challenge.activationservice.dto.CreateActivationRequestDto;
import com.deroyque.challenge.activationservice.repository.ActivationRequestRepository;
import com.deroyque.challenge.activationservice.service.ActivationRequestService;
import com.deroyque.challenge.activationservice.service.CreationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ActivationRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActivationRequestRepository repository;

    @Autowired
    private ActivationRequestService service;

    @BeforeEach
    void limparBanco() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("Deve criar solicitação com dados válidos e retornar 201 Created com status PENDING")
    void deveCriarSolicitacaoComDadosValidos() throws Exception {
        String payload = """
                {
                  "externalId": "EXT-12345",
                  "customerId": "CUST-10001",
                  "productCode": "INTERNET-500"
                }
                """;

        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.externalId").value("EXT-12345"))
                .andExpect(jsonPath("$.customerId").value("CUST-10001"))
                .andExpect(jsonPath("$.productCode").value("INTERNET-500"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Deve retornar 400 Bad Request em formato ProblemDetail quando dados obrigatórios forem inválidos")
    void deveRetornar400QuandoDadosForemInvalidos() throws Exception {
        String payloadInvalido = """
                {
                  "externalId": "",
                  "customerId": null,
                  "productCode": "INTERNET-500"
                }
                """;

        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadInvalido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").isArray());

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("Deve garantir idempotência retornando 200 OK e o mesmo registro quando repetir mesmo externalId e payload")
    void deveGarantirIdempotenciaParaMesmoExternalIdEMesmoPayload() throws Exception {
        String payload = """
                {
                  "externalId": "EXT-IDEMP-01",
                  "customerId": "CUST-10001",
                  "productCode": "INTERNET-500"
                }
                """;

        // 1ª chamada: cria o registro (201 Created)
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        // 2ª chamada (retry): retorna o registro existente sem duplicar (200 OK)
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("EXT-IDEMP-01"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Deve retornar 409 Conflict quando enviar mesmo externalId com productCode divergente")
    void deveRetornar409QuandoExternalIdRepetidoComPayloadDiferente() throws Exception {
        String primeiroPayload = """
                {
                  "externalId": "EXT-CONFLICT-01",
                  "customerId": "CUST-10001",
                  "productCode": "INTERNET-500"
                }
                """;

        String segundoPayloadDivergente = """
                {
                  "externalId": "EXT-CONFLICT-01",
                  "customerId": "CUST-10001",
                  "productCode": "PLANO-TV-4K"
                }
                """;

        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(primeiroPayload))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(segundoPayloadDivergente))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflito de duplicidade"))
                .andExpect(jsonPath("$.status").value(409));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Deve criar exatamente um registro quando várias requisições concorrentes usarem o mesmo externalId")
    void deveEvitarDuplicidadeSobConcorrenciaSimultanea() throws Exception {
        int totalThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch largada = new CountDownLatch(1);
        CreateActivationRequestDto dto = new CreateActivationRequestDto(
                "EXT-CONCURRENT-999",
                "CUST-999",
                "INTERNET-1G"
        );

        List<Callable<CreationResult>> tarefas = new ArrayList<>();
        for (int i = 0; i < totalThreads; i++) {
            tarefas.add(() -> {
                largada.await();
                return service.criarOuObterSolicitacao(dto);
            });
        }

        List<Future<CreationResult>> futuros = new ArrayList<>();
        for (Callable<CreationResult> tarefa : tarefas) {
            futuros.add(executor.submit(tarefa));
        }

        // Libera todas as 10 threads no mesmo instante
        largada.countDown();

        List<CreationResult> resultados = new ArrayList<>();
        for (Future<CreationResult> futuro : futuros) {
            resultados.add(futuro.get());
        }
        executor.shutdown();

        // Verifica que todas retornaram o mesmo ID único e apenas 1 registro existe no banco
        Set<UUID> idsGerados = resultados.stream()
                .map(r -> r.request().getId())
                .collect(Collectors.toSet());

        assertThat(idsGerados).hasSize(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Deve consultar solicitação existente por ID (200 OK) e retornar 404 para ID inexistente")
    void deveConsultarSolicitacaoPorIdERetornar404QuandoNaoExistir() throws Exception {
        CreationResult criada = service.criarOuObterSolicitacao(
                new CreateActivationRequestDto("EXT-GET-01", "CUST-01", "INTERNET-500")
        );
        UUID idExistente = criada.request().getId();

        mockMvc.perform(get("/api/v1/requests/{id}", idExistente))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(idExistente.toString()))
                .andExpect(jsonPath("$.externalId").value("EXT-GET-01"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Registro não encontrado"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("Deve listar solicitações de forma paginada filtrando por customerId e status")
    void deveListarSolicitacoesPaginadasComFiltros() throws Exception {
        service.criarOuObterSolicitacao(new CreateActivationRequestDto("EXT-L1", "CUST-A", "PROD-1"));
        service.criarOuObterSolicitacao(new CreateActivationRequestDto("EXT-L2", "CUST-A", "PROD-2"));
        service.criarOuObterSolicitacao(new CreateActivationRequestDto("EXT-L3", "CUST-B", "PROD-1"));

        mockMvc.perform(get("/api/v1/requests")
                        .param("customerId", "CUST-A")
                        .param("status", "PENDING")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }
}
