package dev.yares.starter.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param email    validado con {@code @Email} para rechazar basura evidente
 *                 antes de gastar una consulta; no para adivinar si existe
 * @param password sin {@code @Size} maximo bajo: bcrypt trunca a 72 bytes, pero
 *                 rechazar una frase larga por politica es empujar a la gente
 *                 hacia contrasenas peores
 */
public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 200) String password) {
}
