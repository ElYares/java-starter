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
     * Marca el token como rotado, y solo si nadie se le adelanto.
     *
     * <p>Es un compare-and-set, no un {@code save}: el {@code where} es la
     * defensa, no un filtro. Devuelve {@code 0} cuando otra transaccion ya roto
     * este mismo token, y ese cero es la unica senal de la carrera de CU-002 A1
     * -- cinco peticiones fallando a la vez y disparando cinco refresh.
     *
     * <p>Hasta la Decision 012 esa carrera la cerraba un indice {@code UNIQUE}
     * sobre {@code replaced_by}. Con la columna invertida ya no puede: las dos
     * rotaciones escriben la <strong>misma</strong> fila con sucesores
     * distintos, y la segunda pisaria a la primera sin violar nada. Bajo
     * {@code READ COMMITTED} la segunda transaccion se bloquea en la fila, y al
     * soltarse reevalua el predicado contra la version ya escrita: encuentra
     * {@code replaced_by} lleno y no toca nada.
     *
     * <p>Tambien exige {@code revoked_at is null}, asi que un logout concurrente
     * gana la carrera contra el refresh. Es el orden correcto: si el usuario
     * cerro la sesion, renovarla no deberia resucitarla.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.replacedBy = :successorId,
                   t.revokedAt = :moment
             where t.id = :id
               and t.replacedBy is null
               and t.revokedAt is null""")
    int markRotated(@Param("id") UUID id, @Param("successorId") UUID successorId,
            @Param("moment") Instant moment);

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
