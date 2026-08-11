package dev.yares.starter.identity.infra;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import dev.yares.starter.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** El valor en claro nunca llega aqui: se busca por su SHA-256. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * "Alguien sucede a este token", es decir: este token ya fue rotado.
     *
     * <p>Recibir un token asi es la firma de un robo — el legitimo y el ladron
     * tienen copias del mismo valor y ambos lo usaron. Ver CU-002 E2.
     */
    boolean existsByReplacedBy(UUID replacedBy);

    /**
     * Revoca de un golpe toda la cadena viva del usuario.
     *
     * <p>Una consulta masiva y no un recorrido en memoria: es la reaccion a un
     * robo detectado y cada milisegundo extra es una peticion mas que el ladron
     * alcanza a hacer.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :moment
             where t.user.id = :userId
               and t.revokedAt is null""")
    int revokeAllOfUser(@Param("userId") UUID userId, @Param("moment") Instant moment);
}
