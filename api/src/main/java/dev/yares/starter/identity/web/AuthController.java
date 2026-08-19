package dev.yares.starter.identity.web;

import java.util.UUID;

import dev.yares.starter.identity.app.AuthService;
import dev.yares.starter.identity.app.IssuedSession;
import dev.yares.starter.identity.infra.UserRepository;
import dev.yares.starter.platform.error.ApiException;
import dev.yares.starter.platform.security.SessionCookies;
import dev.yares.starter.platform.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los endpoints de sesion.
 *
 * <p>Todos viven bajo {@code /auth} y no bajo {@code /api/auth}: el
 * {@code context-path} ya agrega el prefijo. Que la ruta externa sea
 * {@code /api/auth} importa porque es exactamente el {@code Path} de la cookie
 * del refresh token.
 */
@RestController
@RequestMapping("/auth")
class AuthController {

    private final AuthService auth;
    private final UserRepository users;
    private final SessionCookies cookies;
    private final ClientIp clientIp;

    AuthController(AuthService auth, UserRepository users, SessionCookies cookies,
            ClientIp clientIp) {
        this.auth = auth;
        this.users = users;
        this.cookies = cookies;
        this.clientIp = clientIp;
    }

    /**
     * Responde {@code 204} y sin cuerpo.
     *
     * <p>No devuelve el perfil aunque lo tenga a mano: el perfil se pide con
     * {@code me}, para que haya un solo lugar que defina que sabe el frontend
     * del usuario. Tampoco devuelve los tokens — van en las cookies, y el
     * frontend no los toca nunca.
     */
    @PostMapping("/login")
    ResponseEntity<Void> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest http) {

        IssuedSession session = auth.login(
                request.email(),
                request.password(),
                http.getHeader(HttpHeaders.USER_AGENT),
                clientIp.of(http));

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.access(session.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.refresh(session.refreshToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.hint().toString())
                .build();
    }

    /**
     * Renueva la sesion sin que el usuario se entere.
     *
     * <p>El navegador manda aqui la cookie {@code rt} sola: su {@code Path} es
     * {@code /api/auth}, asi que no viaja en ninguna otra peticion. Responde
     * {@code 204} con las dos cookies rotadas y la pista reemitida, y el
     * interceptor reintenta lo que habia fallado.
     */
    @PostMapping("/refresh")
    ResponseEntity<Void> refresh(
            @CookieValue(name = SessionCookies.REFRESH, required = false) String refreshToken,
            HttpServletRequest http) {

        IssuedSession session = auth.refresh(
                refreshToken,
                http.getHeader(HttpHeaders.USER_AGENT),
                clientIp.of(http));

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.access(session.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.refresh(session.refreshToken()).toString())
                // Se reemite en cada rotacion, no solo en el login: su Max-Age
                // tiene que deslizarse con el del 'rt' o expira antes que la
                // sesion que anuncia.
                .header(HttpHeaders.SET_COOKIE, cookies.hint().toString())
                .build();
    }

    /**
     * Cierra esta sesion.
     *
     * <p>Responde {@code 204} y borra las cookies pase lo que pase, incluso sin
     * cookie que revocar. Un logout que puede fallar deja al usuario sin forma
     * de salir, y el resultado que el pidio — quedar fuera — se consigue igual
     * borrando las cookies.
     *
     * <p>Las sesiones del mismo usuario en otros dispositivos siguen vivas. El
     * cierre global existe, pero como consecuencia de detectar un robo, no como
     * funcion ofrecida.
     */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = SessionCookies.REFRESH, required = false) String refreshToken) {

        auth.logout(refreshToken);

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clearedAccess().toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearedRefresh().toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearedHint().toString())
                .build();
    }

    /**
     * Quien soy.
     *
     * <p>La SPA lo llama al arrancar, antes de saber si hay sesion: con cookies
     * {@code HttpOnly} no tiene otra forma de averiguarlo. De paso, esa llamada
     * es la que deja puesta la cookie {@code XSRF-TOKEN} que el login va a
     * necesitar — ver {@code CsrfCookieFilter}.
     */
    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal UUID userId) {
        return users.findById(userId)
                // El token es valido pero el usuario ya no esta: pasa si se
                // borro la cuenta dentro de la vida del access token.
                .map(MeResponse::of)
                .orElseThrow(() -> ApiException.notFound("El usuario de esta sesion ya no existe"));
    }
}
