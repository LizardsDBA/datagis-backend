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
        List<RegistroRejeitadoQuarentena> descartadosForaDoRecorte,
        int totalRecebidos,
        int totalAprovados,
        int totalRejeitados,
        int totalDuplicatas
) {
    /** Compatibilidade com chamadores que ainda não conhecem o descarte por recorte territorial. */
    public ResultadoOrquestracao(
            List<RegistroAprovadoSilver> aprovados,
            List<RegistroRejeitadoQuarentena> rejeitados,
            List<DeduplicadorRegistros.DuplicataDescartada<RegistroBruto>> duplicatasDescartadas,
            int totalRecebidos,
            int totalAprovados,
            int totalRejeitados,
            int totalDuplicatas) {
        this(aprovados, rejeitados, duplicatasDescartadas, List.of(),
                totalRecebidos, totalAprovados, totalRejeitados, totalDuplicatas);
    }

    public boolean temInconsistencias() {
        return rejeitados != null && !rejeitados.isEmpty();
    }

    public boolean temErros() {
        return temInconsistencias();
    }

    public List<DeduplicadorRegistros.DuplicataDescartada<RegistroBruto>> descartadosDedup() {
        return duplicatasDescartadas;
    }

    public boolean temDuplicatas() {
        return duplicatasDescartadas != null && !duplicatasDescartadas.isEmpty();
    }

    /**
     * Registros fora do recorte territorial (Paraná) não são inconsistência de dado -
     * a fonte é nacional/sem filtro geográfico no servidor (ex.: INPE Queimadas Brasil).
     * Por isso NÃO entram em {@link #rejeitados} (não bloqueiam o lote pela regra
     * All-or-Nothing, não viram linha de Quarentena) - são só contados aqui pra
     * auditoria/log.
     */
    public int totalDescartadosForaDoRecorte() {
        return descartadosForaDoRecorte != null ? descartadosForaDoRecorte.size() : 0;
    }
}
