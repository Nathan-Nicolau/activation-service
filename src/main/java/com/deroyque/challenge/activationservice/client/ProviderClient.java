package com.deroyque.challenge.activationservice.client;

import com.deroyque.challenge.activationservice.domain.ActivationRequest;
import com.deroyque.challenge.activationservice.dto.ProviderActivationRequestDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ProviderClient {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final RestClient restClient;

    public ProviderClient(
            @Value("${activation.provider.base-url:http://localhost:8090}") String baseUrl,
            @Value("${activation.provider.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${activation.provider.read-timeout-ms:2000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    // Envia a requisição de ativação para o provedor externo incluindo chave de idempotência
    public void ativarProduto(ActivationRequest request) {
        ProviderActivationRequestDto payload = new ProviderActivationRequestDto(
                request.getId(),
                request.getExternalId(),
                request.getCustomerId(),
                request.getProductCode()
        );

        //Esse endpoint de provider aponta para o Mock estabelecido na API
        restClient.post()
                .uri("/provider/v1/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_HEADER, request.getExternalId())
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }
}
