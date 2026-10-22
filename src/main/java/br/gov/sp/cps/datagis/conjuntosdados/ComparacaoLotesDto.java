package br.gov.sp.cps.datagis.conjuntosdados;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Resultado da comparação entre dois lotes consecutivos de uma mesma fonte (similar
 * ao "git diff" entre commits). Cada registro é classificado como adicionado, removido,
 * modificado ou inalterado, com base no {@code hash_registro} e, quando necessário,
 * comparação campo a campo do payload.
 */
public record ComparacaoLotesDto(
        Long loteAtualId,
        LocalDateTime dataLoteAtual,
        Long loteAnteriorId,
        LocalDateTime dataLoteAnterior,
        Long fonteDadoId,
        String fonteDadoNome,
        boolean loteAnteriorEncontrado,
        String mensagem,
        ResumoLotes resumo,
        List<DiferencaRegistro> registros,
        long totalRegistrosFiltrados,
        int pagina,
        int tamanho) {

    public static final String ADICIONADO = "ADICIONADO";
    public static final String REMOVIDO = "REMOVIDO";
    public static final String MODIFICADO = "MODIFICADO";
    public static final String INALTERADO = "INALTERADO";

    public record ResumoLotes(
            int totalAtual,
            int totalAnterior,
            int adicionados,
            int removidos,
            int modificados,
            int inalterados) {
    }

    public record DiferencaRegistro(
            Long registroBrutoIdAtual,
            Long registroBrutoIdAnterior,
            Integer numeroLinhaAtual,
            Integer numeroLinhaAnterior,
            String situacao,
            Map<String, Object> dadosAtual,
            Map<String, Object> dadosAnterior,
            List<CampoAlterado> camposAlterados) {
    }

    public record CampoAlterado(String campo, Object valorAnterior, Object valorAtual) {
    }

    public static ComparacaoLotesDto semLoteAnterior(
            Long loteAtualId, LocalDateTime dataAtual, Long fonteDadoId, String fonteDadoNome) {
        return new ComparacaoLotesDto(
                loteAtualId, dataAtual, null, null, fonteDadoId, fonteDadoNome,
                false,
                "Este é o primeiro lote da fonte — não há lote anterior para comparar.",
                new ResumoLotes(0, 0, 0, 0, 0, 0),
                List.of(), 0, 0, 0);
    }
}
