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
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Um ou mais campos não passaram na validação."
        );
        problem.setTitle("Dados inválidos");
        problem.setInstance(URI.create(request.getRequestURI()));

        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> Map.of(
                        "field", err.getField(),
                        "message", err.getDefaultMessage() != null ? err.getDefaultMessage() : "Valor inválido"
                ))
                .toList();

        problem.setProperty("errors", errors);
        return problem;
    }

    // Trata parâmetros de URL inválidos (ex.: UUID malformado ou status inexistente) retornando HTTP 400
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail tratarParametroInvalido(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
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
        log.error("Erro inesperado ao processar requisição: {}", ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocorreu um erro interno inesperado."
        );
        problem.setTitle("Erro interno do servidor");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
