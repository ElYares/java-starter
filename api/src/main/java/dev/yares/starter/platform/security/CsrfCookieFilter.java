package dev.yares.starter.platform.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fuerza que la cookie {@code XSRF-TOKEN} se emita.
 *
 * <p>Spring Security 6 difiere la resolucion del token: si nadie lo pide
 * durante la peticion, nunca se genera y por tanto nunca se manda la cookie.
 * Para un formulario servido por el propio backend eso da igual, porque la
 * plantilla lo pide al renderizar. Para una SPA no hay plantilla, y sin este
 * filtro el navegador jamas recibe la cookie.
 *
 * <p>Es la pieza que resuelve el arranque en frio de CU-001 E2: el primer
 * {@code GET /api/auth/me} de la SPA responde {@code 401} — todavia no hay
 * sesion — pero deja la cookie puesta, y con eso el {@code POST} de login ya
 * puede mandar la cabecera {@code X-XSRF-TOKEN}. Sin esto, el login seria la
 * unica ruta mutante que habria que exceptuar de CSRF, y esa excepcion es la
 * primera de las tres que uno encuentra seis meses despues.
 */
class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {

        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            // Basta con pedir el valor: eso dispara la generacion diferida y,
            // con ella, el Set-Cookie del repositorio.
            token.getToken();
        }

        chain.doFilter(request, response);
    }
}
