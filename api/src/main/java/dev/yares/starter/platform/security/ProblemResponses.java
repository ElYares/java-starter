package dev.yares.starter.platform.security;

import java.io.IOException;
import java.net.URI;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.yares.starter.platform.error.ErrorCode;
import dev.yares.starter.platform.error.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Escribe un error del molde de HU-002 directamente sobre la respuesta.
 *
 * <p>Hace falta porque los rechazos de Spring Security ocurren en la cadena de
 * filtros, antes de que exista un controlador y por tanto fuera del alcance de
 * {@code @RestControllerAdvice}. Sin esto, un {@code 401} saldria como la
 * pagina de error por omision y el API tendria dos formas de error segun donde
 * se rechace la peticion, que es exactamente lo que HU-002 vino a eliminar.
 */
@Component
class ProblemResponses {

    private final Problems problems;
    private final ObjectMapper json;

    ProblemResponses(Problems problems, ObjectMapper json) {
        this.problems = problems;
        this.json = json;
    }

    void write(HttpServletRequest request, HttpServletResponse response,
            ErrorCode code, String detail) throws IOException {

        if (response.isCommitted()) {
            return;
        }

        ProblemDetail body = problems.of(code, detail);
        // 'instance' lo pone el controlador cuando hay uno; aqui no lo hay.
        body.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }
}
