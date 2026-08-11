package dev.yares.starter.platform.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Aborta el arranque en produccion si el modelo de sesion quedo en modo
 * desarrollo.
 *
 * <p>Hermana de {@code SeedInProductionGuard} y por la misma razon: los dos
 * fallos que cubre no rompen nada al arrancar, se descubren cuando ya hubo
 * dano. Una clave efimera cierra la sesion de todos en cada despliegue; una
 * cookie sin {@code Secure} viaja en claro en el primer request por HTTP.
 *
 * <p>Falla durante la inicializacion del contexto y no despues de arrancado: un
 * servicio que reporta sano y luego se cae alcanza a recibir trafico.
 */
@Component
@Profile("prod")
class SecurityInProductionGuard implements InitializingBean {

    private final SecurityProperties properties;

    SecurityInProductionGuard(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();

        String secret = properties.jwt().secret();
        if (secret == null || secret.isBlank()) {
            problems.add("app.security.jwt.secret esta vacio: se firmaria con una clave "
                    + "efimera y toda sesion moriria en cada reinicio");
        }

        if (!properties.cookies().secure()) {
            problems.add("app.security.cookies.secure es false: las cookies de sesion "
                    + "viajarian por HTTP en claro");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Configuracion de seguridad invalida para el perfil 'prod': " + problems);
        }
    }
}
