package com.deroyque.challenge.activationservice.controller;

import com.deroyque.challenge.activationservice.domain.ActivationStatus;
import com.deroyque.challenge.activationservice.dto.ActivationRequestResponseDto;
import com.deroyque.challenge.activationservice.dto.CreateActivationRequestDto;
import com.deroyque.challenge.activationservice.service.ActivationRequestService;
import com.deroyque.challenge.activationservice.service.CreationResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
@RequiredArgsConstructor
public class ActivationRequestController {

    private final ActivationRequestService service;

    // Cria uma solicitação de ativação ou retorna o registro existente em caso de repetição idempotente
    @PostMapping
    public ResponseEntity<ActivationRequestResponseDto> criarSolicitacao(
            @Valid @RequestBody CreateActivationRequestDto dto
    ) {
        CreationResult resultado = service.criarOuObterSolicitacao(dto);
        ActivationRequestResponseDto resposta = ActivationRequestResponseDto.converterDeEntidade(resultado.request());

        if (resultado.createdNow()) {
            URI location = URI.create("/api/v1/requests/" + resposta.id());
            return ResponseEntity.created(location).body(resposta);
        }

        return ResponseEntity.ok(resposta);
    }

    // Consulta uma solicitação e seu status atual pelo identificador único
    @GetMapping("/{id}")
    public ResponseEntity<ActivationRequestResponseDto> buscarPorId(@PathVariable UUID id) {
        return ResponseEntity.ok(service.buscarPorId(id));
    }

    // Lista solicitações de forma paginada permitindo filtrar por status e customerId
    @GetMapping
    public ResponseEntity<Page<ActivationRequestResponseDto>> listarSolicitacoes(
            @RequestParam(required = false) ActivationStatus status,
            @RequestParam(required = false) String customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        int tamanhoSeguro = Math.clamp(size, 1, 100);
        Pageable pageable = PageRequest.of(Math.max(page, 0), tamanhoSeguro, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(service.listarPorFiltros(status, customerId, pageable));
    }
}
