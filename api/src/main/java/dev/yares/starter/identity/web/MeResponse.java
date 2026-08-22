package dev.yares.starter.identity.web;

import java.util.Set;
import java.util.UUID;

import dev.yares.starter.identity.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lo unico que el frontend sabe del usuario.
 *
 * <p>Existe como un solo lugar a proposito: con cookies {@code HttpOnly} el
 * frontend no puede leer los claims del token, asi que este endpoint es su
 * unica fuente. Si el perfil viajara ademas en la respuesta del login, habria
 * dos definiciones que se separarian a la primera de cambio.
 *
 * <p>No lleva {@code passwordHash} ni {@code enabled}: el primero no sale nunca
 * de la base, y el segundo no le sirve a quien ya inicio sesion.
 */
public record MeResponse(

        // Los cuatro van marcados como obligatorios porque los cuatro viajan
        // siempre: 'of' los copia de un User que existe. Sin esta marca springdoc
        // los declara opcionales, y entonces el cliente generado tipa cada uno
        // como 'string | undefined' — un tipo mas debil que la realidad, que
        // obliga a comprobar en cada uso algo que no puede pasar.
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<String> roles) {

    static MeResponse of(User user) {
        return new MeResponse(user.id(), user.email(), user.displayName(), user.roles());
    }
}
