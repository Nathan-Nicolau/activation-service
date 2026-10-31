package com.deroyque.challenge.activationservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Trata erros de validação dos campos da requisição (@Valid) retornando HTTP 400
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail tratarErroDeValidacao(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> Map.of(
                        "field", err.getField(),
                        "message", err.getDefaultMessage() != null ? err.getDefaultMessage() : "Valor inválido"
                ))
                .toList();

        log.warn("[SERVICE][ERRO] Falha de validação em {}: campos inválidos={}", request.getRequestURI(), errors);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Um ou mais campos não passaram na validação."
        );
        problem.setTitle("Dados inválidos");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errors", errors);
        return problem;
    }

    // Trata parâmetros de URL inválidos (ex.: UUID malformado ou status inexistente) retornando HTTP 400
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail tratarParametroInvalido(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.warn("[SERVICE][ERRO] Parâmetro inválido '{}' na rota {}: {}", ex.getName(), request.getRequestURI(), ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Parâmetro inválido na requisição: " + ex.getName()
        );
        problem.setTitle("Parâmetro inválido");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    // Trata consulta de registro inexistente retornando HTTP 404
    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail tratarRecursoNaoEncontrado(ResourceNotFoundException ex, HttpServletRequest request) {
        log.warn("[SERVICE][ERRO] Recurso não encontrado em {}: {}", request.getRequestURI(), ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage()
        );
        problem.setTitle("Registro não encontrado");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    // Trata conflito de duplicidade/idempotência (mesmo externalId com dados diferentes) retornando HTTP 409
    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail tratarConflitoDeIdempotencia(IdempotencyConflictException ex, HttpServletRequest request) {
        log.warn("[SERVICE][ERRO] Conflito de duplicidade em {}: {}", request.getRequestURI(), ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
        problem.setTitle("Conflito de duplicidade");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    // Trata qualquer erro inesperado sem expor stack trace nem detalhes internos ao consumidor (HTTP 500)
    @ExceptionHandler(Exception.class)
    public ProblemDetail tratarErroInesperado(Exception ex, HttpServletRequest request) {
        log.error("[SERVICE][ERRO] Erro interno inesperado em {}: {}", request.getRequestURI(), ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocorreu um erro interno inesperado."
        );
        problem.setTitle("Erro interno do servidor");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
