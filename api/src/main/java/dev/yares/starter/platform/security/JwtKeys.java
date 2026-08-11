package dev.yares.starter.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * La clave con la que se firman y verifican los access tokens.
 *
 * <p>HS256 es simetrico: la misma clave firma y verifica. Alcanza porque el
 * unico emisor y el unico verificador son este mismo servicio. El dia que un
 * tercero necesite verificar sin poder emitir, esto tiene que pasar a RS256.
 */
@Configuration(proxyBeanMethods = false)
class JwtKeys {

    /** HS256 exige al menos 256 bits de clave; menos que eso Nimbus lo rechaza. */
    private static final int MIN_KEY_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    @Bean
    SecretKey jwtSecretKey(SecurityProperties properties) {
        String configured = properties.jwt().secret();

        if (configured == null || configured.isBlank()) {
            // Sin clave configurada se genera una por arranque. Las sesiones no
            // sobreviven a un reinicio, que es exactamente el aviso que uno
            // quiere recibir en desarrollo y una catastrofe silenciosa en
            // produccion: de ahi la guarda.
            byte[] generated = new byte[MIN_KEY_BYTES];
            new SecureRandom().nextBytes(generated);
            log.warn("app.security.jwt.secret no esta configurado: se genero una clave efimera. "
                    + "Las sesiones caducan en cada reinicio.");

            return new SecretKeySpec(generated, "HmacSHA256");
        }

        byte[] material = decode(configured);
        if (material.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "app.security.jwt.secret tiene %d bytes y HS256 exige al menos %d."
                            .formatted(material.length, MIN_KEY_BYTES));
        }

        return new SecretKeySpec(material, "HmacSHA256");
    }

    /** Acepta Base64 y tambien texto plano, para que un secreto a mano no obligue a codificar. */
    private static byte[] decode(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        // macAlgorithm fijo a proposito: sin esto el token declara su propio
        // algoritmo en la cabecera y aceptar lo que diga el token es la familia
        // de ataques 'alg confusion'.
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
