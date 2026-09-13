package br.gov.sp.cps.datagis.validacao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import java.util.List;

/**
 * Resultado completo da orquestração de um lote bruto:
 * registros aprovados para a Silver, inconsistências retidas na Quarentena
 * e duplicatas descartadas na deduplicação temporal.
 */
public record ResultadoOrquestracao(
        List<RegistroAprovadoSilver> aprovados,
        List<RegistroRejeitadoQuarentena> rejeitados,
        List<DeduplicadorRegistros.DuplicataDescartada<RegistroBruto>> duplicatasDescartadas,
        int totalRecebidos,
        int totalAprovados,
        int totalRejeitados,
        int totalDuplicatas
) {
    public boolean temInconsistencias() {
        return rejeitados != null && !rejeitados.isEmpty();
    }

    public boolean temDuplicatas() {
        return duplicatasDescartadas != null && !duplicatasDescartadas.isEmpty();
    }
}
