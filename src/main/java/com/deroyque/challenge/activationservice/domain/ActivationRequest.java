package com.deroyque.challenge.activationservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "activation_requests")
public class ActivationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "external_id", nullable = false, unique = true, updatable = false, length = 100)
    private String externalId;

    @Column(name = "customer_id", nullable = false, updatable = false, length = 100)
    private String customerId;

    @Column(name = "product_code", nullable = false, updatable = false, length = 100)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ActivationStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ActivationRequest(String externalId, String customerId, String productCode) {
        this.externalId = Objects.requireNonNull(externalId, "O campo externalId é obrigatório");
        this.customerId = Objects.requireNonNull(customerId, "O campo customerId é obrigatório");
        this.productCode = Objects.requireNonNull(productCode, "O campo productCode é obrigatório");
        this.status = ActivationStatus.PENDING;
        this.retryCount = 0;
        Instant agora = Instant.now();
        this.createdAt = agora;
        this.updatedAt = agora;
    }

    @PrePersist
    void aoCriar() {
        Instant agora = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = agora;
        }
        this.updatedAt = agora;
    }

    @PreUpdate
    void aoAtualizar() {
        this.updatedAt = Instant.now();
    }

    // Transiciona a solicitação de PENDING para PROCESSING antes da chamada ao provedor externo
    public void marcarComoProcessando() {
        if (this.status != ActivationStatus.PENDING) {
            throw new IllegalStateException(
                    "Não é possível alterar para PROCESSING a partir do status atual: " + this.status
            );
        }
        this.status = ActivationStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    // Marca a solicitação como COMPLETED quando o provedor responde com sucesso (2xx)
    public void marcarComoConcluido() {
        if (this.status != ActivationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Não é possível alterar para COMPLETED a partir do status atual: " + this.status
            );
        }
        this.status = ActivationStatus.COMPLETED;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    // Marca a solicitação como FAILED em caso de erro definitivo (ex.: HTTP 400)
    public void marcarComoFalha(String motivo) {
        if (this.status != ActivationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Não é possível alterar para FAILED a partir do status atual: " + this.status
            );
        }
        this.status = ActivationStatus.FAILED;
        this.errorMessage = motivo;
        this.updatedAt = Instant.now();
    }

    /**
     * Trata falhas temporárias na integração com o provedor externo (ex.: HTTP 5xx ou Timeout).
     * Incrementa o contador de tentativas; caso ainda esteja abaixo do limite máximo,
     * retorna para PENDING para nova tentativa no próximo ciclo. Caso contrário, define como FAILED.
     */
    public void tratarFalhaTemporaria(int limiteTentativas, String motivo) {
        if (this.status != ActivationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Não é possível tratar falha temporária a partir do status atual: " + this.status
            );
        }
        this.retryCount++;
        this.errorMessage = motivo;
        if (this.retryCount < limiteTentativas) {
            this.status = ActivationStatus.PENDING;
        } else {
            this.status = ActivationStatus.FAILED;
        }
        this.updatedAt = Instant.now();
    }

    // Verifica se uma nova requisição com o mesmo externalId possui exatamente os mesmos dados
    public boolean possuiMesmoPayload(String customerId, String productCode) {
        return Objects.equals(this.customerId, customerId)
                && Objects.equals(this.productCode, productCode);
    }
}
