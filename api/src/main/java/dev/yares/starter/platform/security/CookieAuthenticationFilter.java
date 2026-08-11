package dev.yares.starter.platform.security;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica la peticion leyendo el access token de la cookie {@code at}.
 *
 * <p>Sustituye al filtro de {@code Bearer} de OAuth2: el token no llega en la
 * cabecera {@code Authorization} sino en una cookie, porque el frontend nunca
 * lo toca (Decision 003).
 *
 * <p>Un token invalido o vencido <strong>no</strong> produce un error aqui: se
 * deja la peticion sin autenticar y sigue su camino. Quien decide si eso es un
 * problema es la autorizacion, mas adelante en la cadena — una ruta publica con
 * una cookie vencida tiene que funcionar igual.
 */
// Deliberadamente sin @Component: lo construye SecurityConfig. Un filtro que
// es bean lo recoge tambien @WebMvcTest, y entonces cualquier rebanada de un
// controlador arrastra el JwtDecoder y media cadena de seguridad con el.
class CookieAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CookieAuthenticationFilter.class);

    private final JwtDecoder decoder;

    CookieAuthenticationFilter(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            readAccessToken(request).ifPresent(token -> authenticate(token, request));
        }

        chain.doFilter(request, response);
    }

    private java.util.Optional<String> readAccessToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return java.util.Optional.empty();
        }

        for (Cookie cookie : cookies) {
            if (SessionCookies.ACCESS.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return java.util.Optional.of(cookie.getValue());
            }
        }

        return java.util.Optional.empty();
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            Jwt jwt = decoder.decode(token);
            UUID userId = UUID.fromString(jwt.getSubject());

            var authentication = new UsernamePasswordAuthenticationToken(
                    userId, null, authorities(jwt));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException invalid) {
            // Vencido, firmado con otra clave o manipulado. Es trafico normal —
            // un access token vive quince minutos — asi que va a 'debug' y no a
            // 'warn': registrarlo mas alto llenaria el log de ruido y escondería
            // lo que si importa.
            log.debug("Access token inservible: {}", invalid.getMessage());
            SecurityContextHolder.clearContext();
        }
    }

    private static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(AccessTokens.ROLES_CLAIM);
        if (roles == null) {
            return List.of();
        }

        // El prefijo 'ROLE_' es lo que espera hasRole(). Se agrega aqui y no se
        // guarda en la base: en 'user_roles' el rol es 'ADMIN', que es como se
        // lee y como se escribe en un seed.
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
