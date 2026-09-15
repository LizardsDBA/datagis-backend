package br.gov.sp.cps.datagis.quarentena;

/**
 * Payload para ações do operador/analista sobre registros retidos na Quarentena.
 */
public record AcaoQuarentenaRequest(
        String motivo,
        String payloadCorrigido,
        Long analistaId
) {}
