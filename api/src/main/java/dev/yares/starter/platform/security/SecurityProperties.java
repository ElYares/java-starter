package dev.yares.starter.platform.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parametros del modelo de sesion de la Decision 003.
 *
 * <p>Las vidas de los tokens son configuracion y no constantes porque son la
 * primera perilla que se toca al diagnosticar un problema de sesion: poder
 * bajar {@code ttl} a un minuto convierte una espera de quince en una prueba.
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue Refresh refresh,
        @DefaultValue Cookies cookies,
        @DefaultValue Login login) {

    /**
     * @param secret clave HS256 en Base64 o texto plano. Vacia significa
     *               "genera una al arrancar", que solo es aceptable fuera de
     *               produccion — ver {@link SecurityInProductionGuard}
     * @param ttl    vida del access token
     */
    public record Jwt(
            @DefaultValue("") String secret,
            @DefaultValue("15m") Duration ttl) {
    }

    /** @param ttl vida del refresh token */
    public record Refresh(@DefaultValue("14d") Duration ttl) {
    }

    /**
     * @param secure marca {@code Secure} en las cookies de sesion. Falso en
     *               desarrollo porque {@code java-starter.localhost} va por
     *               HTTP plano; obligatorio en produccion
     */
    public record Cookies(@DefaultValue("false") boolean secure) {
    }

    /**
     * Limite de intentos de login (CU-001 E1).
     *
     * <p>Los dos techos son distintos y tienen que serlo. Con el mismo numero,
     * cinco fallos de una sola cuenta agotarian tambien la cuota de su IP, y
     * entonces cualquiera podria dejar sin login a toda una oficina detras de un
     * NAT tecleando mal a proposito seis veces. El limite por IP no esta para
     * atrapar a quien olvida su contrasena, sino a quien prueba una contrasena
     * contra muchas cuentas: por eso es holgado.
     *
     * @param maxAttempts      fallos tolerados por email; el siguiente se rechaza
     * @param maxAttemptsPerIp fallos tolerados por direccion de origen
     * @param window           cuanto dura la memoria de un fallo
     */
    public record Login(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("20") int maxAttemptsPerIp,
            @DefaultValue("15m") Duration window) {
    }
}
