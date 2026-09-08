package io.omnirec.web.dto;

/** The only context fields the client is allowed to propose — everything else is server-derived. */
public record ClientEventContextDto(String season) {
}
