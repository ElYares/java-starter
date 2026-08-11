package dev.yares.starter.platform.error;

import java.util.List;

import dev.yares.starter.platform.web.TraceIdProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Unico lugar donde una excepcion se convierte en respuesta.
 *
 * <p>Existe desde antes que el primer controlador a proposito: cada controlador
 * que se escriba copiara lo que encuentre, y sin esto lo que encuentra es el
 * error por omision de Spring Boot — sin {@code code}, sin {@code traceId} y,
 * con devtools activo, con cuatro kilobytes de stack trace viajando al cliente.
 *
 * <p>Extiende {@link ResponseEntityExceptionHandler} para quedarse tambien con
 * las excepciones que produce Spring MVC solo (404 de ruta inexistente, 405,
 * 415, validacion). Sin eso, esas se escapan al controlador de error por
 * omision y salen con otra forma.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final TraceIdProvider traceIds;

    public GlobalExceptionHandler(TraceIdProvider traceIds) {
        this.traceIds = traceIds;
    }

    /** Errores que el dominio lanza a proposito, con su codigo ya decidido. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.code().status())
                .body(problem(ex.code(), ex.getMessage()));
    }

    /**
     * Cualquier cosa no prevista.
     *
     * <p>El cuerpo no lleva stack trace, ni nombre de clase, ni el mensaje de la
     * excepcion: eso describe la implementacion a quien sea que este del otro
     * lado. El detalle va al log, indexado por el mismo {@code traceId} que si
     * viaja en la respuesta.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        ProblemDetail body = problem(ErrorCode.INTERNAL, "Algo salio mal. Reporta el traceId.");
        log.error("Error no controlado [traceId={}]", body.getProperties().get("traceId"), ex);

        return ResponseEntity.status(ErrorCode.INTERNAL.status()).body(body);
    }

    /** Bean Validation: el unico caso que lleva el detalle campo por campo. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        List<FieldIssue> issues = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldIssue(
                        error.getField(),
                        error.getCode(),
                        error.getDefaultMessage()))
                .toList();

        ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED, "Revisa los campos marcados");
        body.setProperty("errors", issues);

        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(body);
    }

    /**
     * Todo lo demas que produce Spring MVC: ruta inexistente, metodo no
     * permitido, cuerpo ilegible. Se le agrega {@code code} y {@code traceId}
     * para que no haya dos formas de error en el mismo API.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        ErrorCode code = ErrorCode.forStatus(statusCode);
        ProblemDetail problem = body instanceof ProblemDetail existing
                ? existing
                : ProblemDetail.forStatus(statusCode);

        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", traceIds.currentTraceId());

        if (statusCode.is5xxServerError()) {
            // Un 5xx que llega por esta via tampoco debe describir la
            // implementacion; el detalle de Spring puede nombrar clases.
            problem.setDetail(ErrorCode.INTERNAL.title());
        } else if (code == ErrorCode.NOT_FOUND) {
            // Spring redacta esto como "No static resource nada.", que es su
            // jerga interna y no significa nada para quien consume un API. La
            // ruta ya viaja en 'instance'.
            problem.setDetail("La ruta solicitada no existe");
        }

        return super.createResponseEntity(problem, headers, statusCode, request);
    }

    private ProblemDetail problem(ErrorCode code, String detail) {
        HttpStatus status = code.status();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", traceIds.currentTraceId());

        return problem;
    }
}
