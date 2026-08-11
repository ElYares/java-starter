package dev.yares.starter.platform.error;

/**
 * Un problema de validacion sobre un campo concreto.
 *
 * <p>{@code code} es el nombre de la restriccion ({@code NotBlank},
 * {@code Email}) para que el cliente pueda decidir sin leer el mensaje.
 */
public record FieldIssue(String field, String code, String message) {
}
