package com.deroyque.challenge.activationservice.service;

import com.deroyque.challenge.activationservice.client.ProviderClient;
import com.deroyque.challenge.activationservice.domain.ActivationRequest;
import com.deroyque.challenge.activationservice.domain.ActivationStatus;
import com.deroyque.challenge.activationservice.dto.CreateActivationRequestDto;
import com.deroyque.challenge.activationservice.repository.ActivationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class ActivationWorkerTest {

    @Autowired
    private ActivationRequestService requestService;

    @Autowired
    private ActivationWorker activationWorker;

    @Autowired
    private ActivationRequestRepository repository;

    @MockitoBean
    private ProviderClient providerClient;

    @BeforeEach
    void limparBanco() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("Deve marcar solicitação como COMPLETED quando o provedor externo retornar sucesso (2xx)")
    void deveConcluirAtivacaoQuandoProvedorRetornarSucesso200() {
        CreationResult criada = requestService.criarOuObterSolicitacao(
                new CreateActivationRequestDto("EXT-200-OK", "CUST-100", "INTERNET-500")
        );
        UUID id = criada.request().getId();

        doNothing().when(providerClient).ativarProduto(any(ActivationRequest.class));

        activationWorker.processarSolicitacoesPendentes();

        ActivationRequest atualizada = repository.findById(id).orElseThrow();
        assertThat(atualizada.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(atualizada.getRetryCount()).isZero();
        assertThat(atualizada.getErrorMessage()).isNull();
        verify(providerClient, times(1)).ativarProduto(any(ActivationRequest.class));
    }

    @Test
    @DisplayName("Deve marcar solicitação como FAILED imediatamente sem retentar quando provedor retornar HTTP 400")
    void deveMarcarComoFailedSemRetentarQuandoProvedorRetornarErro400() {
        CreationResult criada = requestService.criarOuObterSolicitacao(
                new CreateActivationRequestDto("EXT-400-ERR", "CUST-100", "ERR-400-INVALID")
        );
        UUID id = criada.request().getId();

        doThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, new byte[0], null
        )).when(providerClient).ativarProduto(any(ActivationRequest.class));

        // 1º ciclo do worker: deve ir direto para FAILED
        activationWorker.processarSolicitacoesPendentes();

        ActivationRequest atualizada = repository.findById(id).orElseThrow();
        assertThat(atualizada.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(atualizada.getRetryCount()).isZero();
        assertThat(atualizada.getErrorMessage()).contains("HTTP 400");

        // 2º ciclo do worker: não deve tentar processar novamente pois já está FAILED
        activationWorker.processarSolicitacoesPendentes();
        verify(providerClient, times(1)).ativarProduto(any(ActivationRequest.class));
    }

    @Test
    @DisplayName("Deve retentar até o limite máximo quando provedor retornar HTTP 500 e depois marcar como FAILED")
    void deveRetentarAteLimiteEMarcarComoFailedQuandoProvedorRetornarErro500() {
        CreationResult criada = requestService.criarOuObterSolicitacao(
                new CreateActivationRequestDto("EXT-500-ERR", "CUST-100", "ERR-500-SERVER")
        );
        UUID id = criada.request().getId();

        doThrow(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], null
        )).when(providerClient).ativarProduto(any(ActivationRequest.class));

        // Tentativa 1: falha temporária -> volta para PENDING com retryCount = 1
        activationWorker.processarSolicitacoesPendentes();
        ActivationRequest aposTentativa1 = repository.findById(id).orElseThrow();
        assertThat(aposTentativa1.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(aposTentativa1.getRetryCount()).isEqualTo(1);

        // Tentativa 2: falha temporária -> volta para PENDING com retryCount = 2
        activationWorker.processarSolicitacoesPendentes();
        ActivationRequest aposTentativa2 = repository.findById(id).orElseThrow();
        assertThat(aposTentativa2.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(aposTentativa2.getRetryCount()).isEqualTo(2);

        // Tentativa 3 (limite atingido): marca definitivamente como FAILED com retryCount = 3
        activationWorker.processarSolicitacoesPendentes();
        ActivationRequest aposTentativa3 = repository.findById(id).orElseThrow();
        assertThat(aposTentativa3.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(aposTentativa3.getRetryCount()).isEqualTo(3);
        assertThat(aposTentativa3.getErrorMessage()).contains("HTTP 500");

        verify(providerClient, times(3)).ativarProduto(any(ActivationRequest.class));
    }

    @Test
    @DisplayName("Deve tratar Timeout do provedor como falha temporária e permitir recuperação na tentativa seguinte")
    void deveTratarTimeoutDoProvedorERecuperarNaTentativaSeguinte() {
        CreationResult criada = requestService.criarOuObterSolicitacao(
                new CreateActivationRequestDto("EXT-TIMEOUT-REC", "CUST-100", "INTERNET-500")
        );
        UUID id = criada.request().getId();

        // Na 1ª chamada dá Read Timeout; na 2ª chamada responde com sucesso (200 OK)
        doThrow(new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out")))
                .doNothing()
                .when(providerClient).ativarProduto(any(ActivationRequest.class));

        // 1º ciclo: sofre Timeout -> volta para PENDING com retryCount = 1
        activationWorker.processarSolicitacoesPendentes();
        ActivationRequest aposTimeout = repository.findById(id).orElseThrow();
        assertThat(aposTimeout.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(aposTimeout.getRetryCount()).isEqualTo(1);
        assertThat(aposTimeout.getErrorMessage()).contains("Timeout");

        // 2º ciclo: provedor responde com sucesso -> muda para COMPLETED
        activationWorker.processarSolicitacoesPendentes();
        ActivationRequest aposRecuperacao = repository.findById(id).orElseThrow();
        assertThat(aposRecuperacao.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(aposRecuperacao.getRetryCount()).isEqualTo(1);
        assertThat(aposRecuperacao.getErrorMessage()).isNull();

        verify(providerClient, times(2)).ativarProduto(any(ActivationRequest.class));
    }
}
