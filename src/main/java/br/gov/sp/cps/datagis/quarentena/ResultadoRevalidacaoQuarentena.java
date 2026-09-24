package br.gov.sp.cps.datagis.quarentena;

import java.util.List;

/**
 * Representa o resultado de uma operação de revalidação e promoção de registro da Quarentena.
 *
 * @param sucesso se o registro inspecionado foi validado e atualizado para CORRIGIDO_REPROCESSADO com sucesso
 * @param idOriginal ID do registro da quarentena inspecionado
 * @param totalColetivoAprovados quantidade de outros registros de domínio também promovidos em cascata
 * @param totalColetivoMantidos quantidade de outros registros de domínio que continuaram pendentes na quarentena
 * @param lotesLiberados IDs dos lotes que tiveram todas as pendências resolvidas e foram promovidos à Silver
 * @param mensagem descrição informativa do resultado para exibição ao usuário
 */
public record ResultadoRevalidacaoQuarentena(
        boolean sucesso,
        Long idOriginal,
        int totalColetivoAprovados,
        int totalColetivoMantidos,
        List<Long> lotesLiberados,
        String mensagem
) {
    public static ResultadoRevalidacaoQuarentena individual(Long idOriginal, List<Long> lotesLiberados) {
        return new ResultadoRevalidacaoQuarentena(
                true,
                idOriginal,
                0,
                0,
                lotesLiberados != null ? lotesLiberados : List.of(),
                "Registro revalidado com sucesso."
        );
    }

    public static ResultadoRevalidacaoQuarentena coletivo(
            Long idOriginal,
            int aprovados,
            int mantidos,
            List<Long> lotesLiberados) {
        String msg;
        if (aprovados > 0) {
            msg = "Registro revalidado com sucesso! Outros " + aprovados +
                  " registro(s) com erro de domínio também foram revalidados e promovidos.";
        } else {
            msg = "Registro revalidado com sucesso.";
        }
        return new ResultadoRevalidacaoQuarentena(
                true,
                idOriginal,
                aprovados,
                mantidos,
                lotesLiberados != null ? lotesLiberados : List.of(),
                msg
        );
    }
}
