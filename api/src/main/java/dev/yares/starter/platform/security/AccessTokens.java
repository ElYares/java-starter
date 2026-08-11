package dev.yares.starter.platform.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Emite el access token.
 *
 * <p>Lleva lo justo para autorizar sin volver a la base en cada peticion:
 * quien es y que puede. Nada mas — un JWT no esta cifrado, solo firmado, y
 * cualquiera que lo tenga puede leer sus claims con un decodificador de
 * Base64.
 */
@Component
public class AccessTokens {

    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder encoder;
    private final SecurityProperties properties;
    private final Clock clock;

    public AccessTokens(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(UUID userId, Collection<String> roles) {
        Instant now = clock.instant();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim(ROLES_CLAIM, List.copyOf(roles))
                .issuedAt(now)
                .expiresAt(now.plus(properties.jwt().ttl()))
                .build();

        // El algoritmo se fija aqui y el decodificador lo exige del otro lado.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
