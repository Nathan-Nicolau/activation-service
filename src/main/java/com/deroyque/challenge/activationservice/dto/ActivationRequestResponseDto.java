package com.deroyque.challenge.activationservice.dto;

import com.deroyque.challenge.activationservice.domain.ActivationRequest;
import com.deroyque.challenge.activationservice.domain.ActivationStatus;

import java.time.Instant;
import java.util.UUID;

public record ActivationRequestResponseDto(
        UUID id,
        String externalId,
        String customerId,
        String productCode,
        ActivationStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    // Converte a entidade de domínio para o DTO de resposta da API
    public static ActivationRequestResponseDto converterDeEntidade(ActivationRequest entity) {
        return new ActivationRequestResponseDto(
                entity.getId(),
                entity.getExternalId(),
                entity.getCustomerId(),
                entity.getProductCode(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
