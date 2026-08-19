package dev.yares.starter.platform.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Las dos cookies que sostienen la sesion, la pista que no sostiene nada, y los
 * atributos que las hacen seguras.
 *
 * <p>{@code HttpOnly} es el punto entero de la Decision 003: un XSS no puede
 * leer {@code at} ni {@code rt}, asi que no hay token expuesto a JavaScript. El
 * frontend no guarda, no lee y no adjunta nada; el navegador las manda solo
 * porque todo vive en el mismo origen gracias al servicio {@code edge}.
 *
 * <p>{@link #hint()} es la excepcion deliberada, y la unica. Se lee desde JS
 * porque no es una credencial: ver su javadoc antes de tocarla.
 */
@Component
public class SessionCookies {

    public static final String ACCESS = "at";
    public static final String REFRESH = "rt";
    public static final String HINT = "has_session";

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
     * La pista de sesion: la unica cookie que JavaScript si puede leer.
     *
     * <p><strong>No es una credencial y no autoriza nada.</strong> El servidor
     * jamas la mira, asi que falsificarla no consigue nada — quien la ponga a
     * mano solo logra que su propio navegador intente un refresh que va a
     * fallar. Toda la autorizacion sigue viviendo en el {@code at} firmado.
     *
     * <p>Existe porque con cookies {@code HttpOnly} la SPA no puede saber si
     * alguna vez hubo sesion. Sin saberlo, el interceptor dispara un refresh en
     * cada visita anonima: dos peticiones perdidas por visita, y un {@code 401}
     * por visitante en el log que se parece a un ataque y no lo es. Con la
     * pista, el visitante anonimo no pide nada. Ver Decision 014.
     *
     * <p>Su {@code Max-Age} es el del refresh token, no otro. Una pista que
     * sobreviva al {@code rt} devuelve exactamente el refresh perdido que vino a
     * evitar.
     *
     * <p>{@code Path=/} porque {@code document.cookie} solo expone las cookies
     * cuyo path cubre la ruta actual, y la SPA la lee desde cualquiera. Es la
     * misma razon por la que la cookie de CSRF tambien va en la raiz.
     */
    public ResponseCookie hint() {
        return hint("1", properties.refresh().ttl());
    }

    /**
     * Borra la pista.
     *
     * <p>La emite el logout. El otro camino que la mata — un refresh que
     * responde {@code 401} — lo cierra el frontend por su cuenta: ahi ya sabe
     * que la sesion murio, y la pista es suya de leer y de borrar.
     */
    public ResponseCookie clearedHint() {
        return hint("", Duration.ZERO);
    }

    private ResponseCookie hint(String value, Duration maxAge) {
        return ResponseCookie.from(HINT, value)
                // La excepcion, y el unico lugar de este archivo donde esto es
                // 'false'. Si alguna vez esta cookie lleva algo que no sea "1",
                // esta linea es la que hay que volver a discutir.
                .httpOnly(false)
                .secure(properties.cookies().secure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
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
