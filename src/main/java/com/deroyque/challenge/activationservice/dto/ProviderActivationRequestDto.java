package com.deroyque.challenge.activationservice.dto;

import java.util.UUID;

public record ProviderActivationRequestDto(
        UUID requestId,
        String externalId,
        String customerId,
        String productCode
) {
}
