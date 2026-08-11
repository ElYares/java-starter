package dev.yares.starter.platform.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.yares.starter.platform.web.TraceIdProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ejercita el molde de errores con un controlador ficticio.
 *
 * <p>Se prueba asi porque HU-002 va **antes** del primer controlador real: lo
 * que se verifica es la forma de la respuesta, no un endpoint concreto.
 */
@WebMvcTest(controllers = ErrorMoldTest.Fixture.class)
@Import({ ErrorMoldTest.Fixture.class, TraceIdProvider.class, Problems.class,
        GlobalExceptionHandler.class })
// Sin la cadena de filtros: esta rebanada mide la forma del error, no quien
// tiene permiso de provocarlo. Con Spring Security en el classpath, el
// comportamiento por omision seria responder 401 a todo y no se probaria nada.
@AutoConfigureMockMvc(addFilters = false)
class ErrorMoldTest {

    private static final String SECRETO = "contrasena-en-el-mensaje-de-la-excepcion";

    @Autowired
    MockMvc mvc;

    @Test
    void laValidacionDevuelveElDetalleCampoPorCampo() throws Exception {
        mvc.perform(post("/fixture/validar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no-es-un-email\",\"nombre\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='nombre')].code").value("NotBlank"))
                .andExpect(jsonPath("$.errors[?(@.field=='email')].code").value("Email"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void todaRespuestaDeErrorTraeTraceId() throws Exception {
        for (String ruta : new String[] { "/fixture/no-encontrado", "/fixture/explota", "/fixture/prohibido" }) {
            mvc.perform(get(ruta))
                    .andExpect(jsonPath("$.traceId").isNotEmpty())
                    .andExpect(jsonPath("$.code").isNotEmpty());
        }
    }

    @Test
    void el500NoFiltraLaExcepcionAlCliente() throws Exception {
        mvc.perform(get("/fixture/explota"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString(SECRETO))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("at dev.yares"))));
    }

    @Test
    void unRecursoAjenoDevuelve404YNo403() throws Exception {
        mvc.perform(get("/fixture/no-encontrado"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void faltaDeRolDevuelve403() throws Exception {
        mvc.perform(get("/fixture/prohibido"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void loQueProduceSpringMvcSoloTambienLlevaCodeYTraceId() throws Exception {
        // El metodo no coincide: lo rechaza Spring MVC, no el codigo de dominio.
        mvc.perform(post("/fixture/no-encontrado"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unaRutaInexistenteNoHablaDeRecursosEstaticos() throws Exception {
        mvc.perform(get("/fixture/esta-ruta-no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("La ruta solicitada no existe"))
                .andExpect(content().string(not(containsString("static resource"))));
    }

    @Test
    void elTipoEsRelativoYNoHorneaElDominioDeDesarrollo() throws Exception {
        mvc.perform(get("/fixture/no-encontrado"))
                .andExpect(jsonPath("$.type").value("/errors/not-found"));
    }

    @RestController
    static class Fixture {

        record Entrada(@Email String email, @NotBlank String nombre) {
        }

        @PostMapping("/fixture/validar")
        String validar(@Valid @RequestBody Entrada entrada) {
            return entrada.nombre();
        }

        @GetMapping("/fixture/no-encontrado")
        String noEncontrado() {
            throw ApiException.notFound("El dataset no existe, o no es tuyo");
        }

        @GetMapping("/fixture/prohibido")
        String prohibido() {
            throw ApiException.forbidden("Necesitas el rol ADMIN");
        }

        @GetMapping("/fixture/explota")
        String explota() {
            throw new IllegalStateException(SECRETO);
        }
    }
}
