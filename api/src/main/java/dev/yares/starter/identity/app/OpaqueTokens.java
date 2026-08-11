package dev.yares.starter.identity.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * Genera y hashea el refresh token.
 *
 * <p>Opaco y no un JWT: un refresh JWT solo se invalida con una lista negra, que
 * es una tabla — la misma tabla que ya existe, con mas pasos y sin poder rotar.
 * Ver Decision 003.
 */
@Component
public class OpaqueTokens {

    /** 256 bits. Adivinar uno es tan improbable como adivinar la clave de firma. */
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public String mint() {
        byte[] material = new byte[TOKEN_BYTES];
        random.nextBytes(material);

        // Sin relleno y en alfabeto de URL: el valor viaja en una cookie, donde
        // '+', '/' y '=' obligan a comillas y a escapes que alguien olvidara.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /**
     * SHA-256, no bcrypt.
     *
     * <p>bcrypt existe para hacer lento el ataque de diccionario sobre algo que
     * un humano eligio y por tanto es adivinable. Esto son 256 bits de entropia
     * criptografica: no hay diccionario que lo alcance, y bcrypt solo agregaria
     * cientos de milisegundos a la operacion mas frecuente de la sesion. Ademas
     * SHA-256 es determinista, y sin eso no se podria buscar por hash.
     */
    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 es obligatorio en toda JVM desde hace decadas.
            throw new IllegalStateException("SHA-256 no disponible", impossible);
        }
    }
}
