package br.gov.sp.cps.datagis.gold;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de entrada para cadastro e atualização de tipos de indicador.
 */
public record TipoIndicadorRequest(
        @NotBlank(message = "Código é obrigatório")
        @Size(max = 20, message = "Código não pode ter mais de 20 caracteres")
        String codigo,

        @NotBlank(message = "Descrição é obrigatória")
        @Size(max = 200, message = "Descrição não pode ter mais de 200 caracteres")
        String descricao,

        Boolean temFonte,
        Boolean ativo
) {}
