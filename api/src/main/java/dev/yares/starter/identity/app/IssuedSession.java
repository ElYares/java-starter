package dev.yares.starter.identity.app;

/**
 * Los dos valores en claro que hay que poner en cookies.
 *
 * <p>Del refresh token esta es la unica vez que existe el valor legible: en la
 * base solo queda su SHA-256, asi que si no se escribe en la respuesta ahora,
 * no hay forma de recuperarlo.
 */
public record IssuedSession(String accessToken, String refreshToken) {
}
