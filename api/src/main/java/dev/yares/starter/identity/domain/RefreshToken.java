package dev.yares.starter.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un refresh token vivo o historico.
 *
 * <p>Es opaco y consultable, no un JWT: un refresh JWT solo se invalida con una
 * lista negra, que es una tabla — la misma tabla, con mas pasos y sin poder
 * rotar. Ver Decision 003.
 *
 * <p>Del valor que recibio el navegador aqui solo vive su SHA-256. Una fuga de
 * la base no entrega sesiones activas.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * El token que <strong>sucede</strong> a este: apunta hacia adelante, al que
     * se emitio al rotarlo. Nulo mientras no se haya rotado.
     *
     * <p>Es la propiedad que responde "este token ya fue usado?" con una lectura
     * de la fila que ya se tiene en la mano. Hasta la Decision 012 apuntaba al
     * reves y habia que preguntarle a la base quien apunta a mi, con su indice
     * detras, en la operacion mas frecuente de toda la sesion.
     *
     * <p>Ojo con el orden al escribirlo: la columna es una clave foranea contra
     * la misma tabla, asi que la fila sucesora tiene que existir antes de que
     * esta pueda apuntarla.
     */
    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "user_agent")
    private String userAgent;

    /**
     * {@code inet} nativo de Postgres, no {@code text}: la base valida que sea
     * una direccion y no una cadena cualquiera. Hibernate necesita que se lo
     * digan — sin {@code @JdbcTypeCode} intenta enviar un {@code varchar} y
     * Postgres rechaza la conversion implicita.
     */
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip")
    private String ip;

    protected RefreshToken() {
        // Requerido por JPA.
    }

    private RefreshToken(UUID id, User user, String tokenHash, Instant issuedAt,
            Instant expiresAt, String userAgent, String ip) {
        this.id = id;
        this.user = user;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.ip = ip;
    }

    /**
     * Un token recien emitido, ya sea al abrir sesion o al rotar otro.
     *
     * <p>No hay una fabrica aparte para la rotacion porque el sucesor no se
     * distingue en nada de un token nuevo: el vinculo entre los dos vive en el
     * predecesor, no aqui.
     */
    public static RefreshToken issue(User user, String tokenHash, Instant issuedAt,
            Instant expiresAt, String userAgent, String ip) {
        return new RefreshToken(UUID.randomUUID(), user, tokenHash, issuedAt, expiresAt,
                userAgent, ip);
    }

    public void revokeAt(Instant moment) {
        if (revokedAt == null) {
            revokedAt = moment;
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant moment) {
        return !expiresAt.isAfter(moment);
    }

    public UUID id() {
        return id;
    }

    public User user() {
        return user;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public UUID replacedBy() {
        return replacedBy;
    }

    public String userAgent() {
        return userAgent;
    }

    public String ip() {
        return ip;
    }
}
