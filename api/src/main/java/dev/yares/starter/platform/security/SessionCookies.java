package dev.yares.starter.platform.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Las dos cookies que sostienen la sesion, y los tres atributos que las hacen
 * seguras.
 *
 * <p>{@code HttpOnly} es el punto entero de la Decision 003: un XSS no puede
 * leer estas cookies, asi que no hay token expuesto a JavaScript. El frontend
 * no guarda, no lee y no adjunta nada; el navegador las manda solo porque todo
 * vive en el mismo origen gracias al servicio {@code edge}.
 */
@Component
public class SessionCookies {

    public static final String ACCESS = "at";
    public static final String REFRESH = "rt";

    /**
     * Rutas absolutas y con el {@code /api} escrito a mano.
     *
     * <p>{@code server.servlet.context-path} es {@code /api}, pero el atributo
     * {@code Path} de una cookie lo interpreta el navegador, que no sabe nada
     * de context paths: tiene que ser la ruta tal como se ve desde fuera.
     *
     * <p>Que el refresh viva en {@code /api/auth} no es cosmetico. Significa que
     * el navegador <strong>no</strong> lo adjunta a las peticiones normales del
     * API: viaja solo cuando se lo pide, en refresh y en logout. Una credencial
     * de catorce dias que viajara en cada peticion tendria catorce dias de
     * oportunidades de filtrarse en un log.
     */
    private static final String ACCESS_PATH = "/api";
    private static final String REFRESH_PATH = "/api/auth";

    private final SecurityProperties properties;

    public SessionCookies(SecurityProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie access(String value) {
        return base(ACCESS, value, ACCESS_PATH, properties.jwt().ttl());
    }

    public ResponseCookie refresh(String value) {
        return base(REFRESH, value, REFRESH_PATH, properties.refresh().ttl());
    }

    /**
     * Cookies de borrado.
     *
     * <p>Mismo nombre, mismo {@code Path} y {@code Max-Age} cero. Si el
     * {@code Path} no coincide exactamente, el navegador crea una cookie nueva
     * y deja viva la original: el logout parece funcionar y no funciona.
     */
    public ResponseCookie clearedAccess() {
        return base(ACCESS, "", ACCESS_PATH, Duration.ZERO);
    }

    public ResponseCookie clearedRefresh() {
        return base(REFRESH, "", REFRESH_PATH, Duration.ZERO);
    }

    private ResponseCookie base(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.cookies().secure())
                // 'Lax' y no 'Strict': con 'Strict' la cookie no viaja cuando el
                // usuario llega siguiendo un enlace externo, y entonces aterriza
                // deslogueado en su propia sesion viva.
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
