package dev.yares.starter.platform.error;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Un problema de validacion sobre un campo concreto.
 *
 * <p>{@code code} es el nombre de la restriccion ({@code NotBlank},
 * {@code Email}) para que el cliente pueda decidir sin leer el mensaje.
 */
@Schema(name = "FieldIssue", description = "Un campo que no paso la validacion.")
public record FieldIssue(

        @Schema(description = "Nombre del campo, tal como viaja en el JSON", example = "email",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String field,

        @Schema(description = "Nombre de la restriccion que fallo. Es lo que el cliente mira "
                + "para decidir que mensaje mostrar", example = "NotBlank",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String code,

        @Schema(description = "Mensaje de Bean Validation. Hoy sale en ingles: falta el "
                + "MessageSource del backend", example = "must not be blank",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String message) {
}
