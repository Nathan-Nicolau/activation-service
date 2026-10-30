package com.deroyque.challenge.activationservice.repository;

import com.deroyque.challenge.activationservice.domain.ActivationRequest;
import com.deroyque.challenge.activationservice.domain.ActivationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID> {

    // Busca solicitação pelo identificador externo (usado para controle de duplicidade/idempotência)
    Optional<ActivationRequest> findByExternalId(String externalId);

    // Busca solicitações pendentes ordenadas por data de criação para processamento em lote pelo Worker
    List<ActivationRequest> findByStatusOrderByCreatedAtAsc(ActivationStatus status, Pageable pageable);

    // Consulta paginada com filtros opcionais de status e customerId
    @Query("""
            SELECT r FROM ActivationRequest r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:customerId IS NULL OR r.customerId = :customerId)
            """)
    Page<ActivationRequest> buscarPorFiltros(
            @Param("status") ActivationStatus status,
            @Param("customerId") String customerId,
            Pageable pageable
    );
}
