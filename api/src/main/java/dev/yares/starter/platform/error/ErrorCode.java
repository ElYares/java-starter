package dev.yares.starter.platform.error;

import java.net.URI;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Catalogo cerrado de codigos de error.
 *
 * <p>El cliente decide con {@code code} y nunca con {@code title} ni
 * {@code detail}, que son texto para humanos y pueden cambiar sin aviso. Por eso
 * es un enum y no una cadena suelta: un codigo nuevo obliga a tocar este archivo,
 * que es exactamente donde uno quiere que se note.
 *
 * <p>La tabla base esta en {@code docs/04-contratos-api.md}. Aqui hay tres
 * codigos de mas ({@code BAD_REQUEST}, {@code METHOD_NOT_ALLOWED},
 * {@code NOT_ACCEPTABLE}) porque Spring MVC puede producir esos estados por su
 * cuenta y toda respuesta necesita un codigo; sin ellos habria errores sin
 * {@code code}, que es justo lo que este molde viene a evitar.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "La peticion no es valida"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "La peticion no se pudo procesar"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "No has iniciado sesion"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "No tienes permiso para esta operacion"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "No se encontro el recurso"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "El metodo no esta permitido"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "No se puede responder en el formato pedido"),
    CONFLICT(HttpStatus.CONFLICT, "La operacion entra en conflicto con el estado actual"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo excede el limite"),
    UNSUPPORTED_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "El tipo de archivo no esta soportado"),
    UNPROCESSABLE(HttpStatus.UNPROCESSABLE_ENTITY, "La peticion es valida pero no se puede cumplir"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /**
     * URI de tipo, relativa a proposito.
     *
     * <p>{@code docs/04-contratos-api.md} la muestra absoluta contra
     * {@code java-starter.localhost}, pero hornear el dominio de desarrollo en
     * las respuestas de produccion es un error que se descubre tarde. Relativa
     * es valida segun RFC 7807 y no miente en ningun entorno.
     */
    public URI type() {
        return URI.create("/errors/" + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /**
     * Codigo para un estado que produjo Spring MVC, no el codigo de dominio.
     *
     * <p>Nunca devuelve nulo: una respuesta de error sin {@code code} rompe el
     * contrato con el cliente.
     */
    public static ErrorCode forStatus(HttpStatusCode status) {
        for (ErrorCode candidate : values()) {
            if (candidate != VALIDATION_FAILED && candidate.status.value() == status.value()) {
                return candidate;
            }
        }

        return status.is4xxClientError() ? BAD_REQUEST : INTERNAL;
    }
}
