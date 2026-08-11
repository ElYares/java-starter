package dev.yares.starter.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * La direccion del cliente real, no la del proxy.
 *
 * <p>Todo el trafico entra por el servicio {@code edge}, asi que
 * {@code getRemoteAddr()} devuelve siempre la IP del contenedor de Caddy. Usarla
 * para limitar intentos de login no protegeria nada: los seis intentos de
 * cualquiera agotarian la cuota de todos los usuarios a la vez.
 *
 * <p>Se toma el <strong>ultimo</strong> valor de {@code X-Forwarded-For} y no el
 * primero. La cabecera es una lista donde cada proxy agrega al final, asi que el
 * ultimo elemento es el que escribio nuestro propio {@code edge} y es el unico
 * que un cliente no puede falsificar: si alguien manda
 * {@code X-Forwarded-For: 1.2.3.4}, Caddy la deja pero agrega su IP real
 * despues. Leer el primero seria dejar que cada atacante elija su propia cuota.
 *
 * <p>Esto vale <strong>solo</strong> porque hay exactamente un proxy y nadie
 * llega al {@code api} sin pasar por el — el contenedor no publica puertos. Con
 * un segundo proxy delante habria que contar saltos.
 */
@Component
public class ClientIp {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    public String of(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);

        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            String last = hops[hops.length - 1].trim();
            if (!last.isBlank()) {
                return last;
            }
        }

        return request.getRemoteAddr();
    }
}
