package dev.yares.starter.platform.security;

import java.time.Clock;

import dev.yares.starter.platform.error.ErrorCode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * La cadena de seguridad completa del modelo de la Decision 003.
 *
 * <p>Sin estado en el servidor: no hay {@code HttpSession}, no hay tabla de
 * sesiones. Lo unico que el servidor recuerda entre peticiones son los refresh
 * tokens, y esos existen para poder revocarlos.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

    /**
     * Coste 12, el mismo con el que se generaron los hashes del seed.
     *
     * <p>Es deliberadamente lento — unos cientos de milisegundos — y esa lentitud
     * es la defensa contra la fuerza bruta sobre una base filtrada. Tambien es
     * la razon por la que el limite de intentos de CU-001 E1 se evalua
     * <strong>antes</strong> de llegar aqui: si no, cada intento fallido le
     * regala al atacante ese tiempo de CPU del servidor.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * El reloj como dependencia y no {@code Instant.now()} suelto.
     *
     * <p>Es lo que permite que un test compruebe que un token vencido se
     * rechaza sin tener que esperar quince minutos.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder decoder,
            ProblemResponses problems) throws Exception {

        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        // Desactiva el enmascarado BREACH del manejador por omision. Con la SPA
        // leyendo la cookie y reenviandola tal cual en la cabecera, el valor de
        // una y otra tienen que coincidir literalmente.
        csrfHandler.setCsrfRequestAttributeName(null);

        return http
                .csrf(csrf -> csrf
                        // Legible por JavaScript a proposito: es la mitad del
                        // trato del patron double-submit. Lo que no puede leer
                        // el JavaScript de otro origen es *esta* cookie, y por
                        // eso el esquema funciona.
                        .csrfTokenRepository(cookieRepository())
                        .csrfTokenRequestHandler(csrfHandler))

                // Sin sesion de servlet. El estado de la sesion son las cookies
                // firmadas, no un mapa en memoria que se pierde al reiniciar y
                // que impide correr dos instancias.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Las rutas van sin '/api': el context-path ya lo quito antes de
                // que Spring Security vea la peticion.
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers(HttpMethod.POST, "/auth/login", "/auth/refresh",
                                "/auth/logout").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())

                .exceptionHandling(handling -> handling
                        // Sin esto, un 401 sale como la pagina de error de
                        // Spring y el molde de HU-002 se rompe justo en el error
                        // mas frecuente de todo el API.
                        .authenticationEntryPoint((request, response, ex) -> problems.write(
                                request, response, ErrorCode.UNAUTHENTICATED,
                                "No has iniciado sesion o tu sesion expiro"))
                        .accessDeniedHandler((request, response, ex) -> problems.write(
                                request, response, ErrorCode.FORBIDDEN,
                                "No tienes permiso para esta operacion")))

                .addFilterBefore(new CookieAuthenticationFilter(decoder),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)

                // Nada de formularios ni de Basic: este API no tiene paginas de
                // login y un dialogo del navegador no es una experiencia.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .anonymous(Customizer.withDefaults())
                .build();
    }

    /**
     * La cookie CSRF vive en {@code /}, no en {@code /api}.
     *
     * <p>Es el detalle que cuesta una tarde. El {@code Path} de una cookie no
     * solo decide a que peticiones se adjunta: tambien decide si
     * {@code document.cookie} la ve. La SPA se sirve desde {@code /}, asi que
     * una cookie con {@code Path=/api} le seria invisible y Axios mandaria la
     * cabecera vacia. El sintoma es un {@code 403} en todo metodo mutante, con
     * la cookie perfectamente presente en las herramientas del navegador.
     */
    private static CookieCsrfTokenRepository cookieRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // Via 'setCookieCustomizer' y no 'setCookiePath': el segundo esta
        // deprecado y, comprobado, no llega a la cookie emitida — sale con el
        // context path igual. El sintoma no se parece a la causa.
        repository.setCookieCustomizer(cookie -> cookie.path("/"));

        return repository;
    }
}
