package com.deroyque.challenge.activationservice.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

    @Test
    @DisplayName("Deve inicializar a solicitação com status PENDING e zero tentativas")
    void deveInicializarComStatusPending() {
        ActivationRequest request = new ActivationRequest("EXT-001", "CUST-01", "INTERNET-500");

        assertThat(request.getExternalId()).isEqualTo("EXT-001");
        assertThat(request.getCustomerId()).isEqualTo("CUST-01");
        assertThat(request.getProductCode()).isEqualTo("INTERNET-500");
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getRetryCount()).isZero();
        assertThat(request.getCreatedAt()).isNotNull();
        assertThat(request.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Deve transicionar de PENDING para PROCESSING e depois para COMPLETED")
    void deveTransicionarDePendingParaProcessingEParaCompleted() {
        ActivationRequest request = new ActivationRequest("EXT-001", "CUST-01", "INTERNET-500");

        request.marcarComoProcessando();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);

        request.marcarComoConcluido();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("Deve transicionar de PROCESSING para FAILED em caso de erro definitivo")
    void deveTransicionarDeProcessingParaFailed() {
        ActivationRequest request = new ActivationRequest("EXT-001", "CUST-01", "INTERNET-500");
        request.marcarComoProcessando();

        request.marcarComoFalha("HTTP 400: Código de produto inválido");

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getErrorMessage()).isEqualTo("HTTP 400: Código de produto inválido");
    }

    @Test
    @DisplayName("Deve voltar para PENDING em falha temporária abaixo do limite e ir para FAILED ao esgotar tentativas")
    void deveTratarFalhasTemporariasRespeitandoLimiteDeTentativas() {
        ActivationRequest request = new ActivationRequest("EXT-001", "CUST-01", "INTERNET-500");

        // Tentativa 1 (retryCount = 1 < 3 -> volta para PENDING)
        request.marcarComoProcessando();
        request.tratarFalhaTemporaria(3, "HTTP 500: Erro interno no provedor");
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getRetryCount()).isEqualTo(1);

        // Tentativa 2 (retryCount = 2 < 3 -> volta para PENDING)
        request.marcarComoProcessando();
        request.tratarFalhaTemporaria(3, "Timeout na chamada ao provedor");
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getRetryCount()).isEqualTo(2);

        // Tentativa 3 (retryCount = 3 == 3 -> marca como FAILED definitivo)
        request.marcarComoProcessando();
        request.tratarFalhaTemporaria(3, "Timeout novamente");
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getRetryCount()).isEqualTo(3);
        assertThat(request.getErrorMessage()).isEqualTo("Timeout novamente");
    }

    @Test
    @DisplayName("Deve rejeitar transições de status inválidas (ex.: COMPLETED voltar para PROCESSING)")
    void deveRejeitarTransicoesInvalidas() {
        ActivationRequest request = new ActivationRequest("EXT-001", "CUST-01", "INTERNET-500");

        // Não pode concluir direto de PENDING sem passar por PROCESSING
        assertThatThrownBy(request::marcarComoConcluido)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Não é possível alterar para COMPLETED");

        request.marcarComoProcessando();
        request.marcarComoConcluido();

        // Não pode voltar para PROCESSING nem ir para FAILED após COMPLETED
        assertThatThrownBy(request::marcarComoProcessando)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Não é possível alterar para PROCESSING");
        assertThatThrownBy(() -> request.marcarComoFalha("Erro"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Não é possível alterar para FAILED");
    }
}
