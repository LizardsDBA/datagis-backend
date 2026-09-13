package br.gov.sp.cps.datagis.ingestao;

/**
 * Fases pelas quais uma execução de ingestão passa, só para dar feedback de progresso
 * na tela — nenhuma regra de negócio depende deste enum. Existe porque o upload de
 * 3,3GB do layer APPS (LIZARDS-3) rodou minutos a fio sem nenhum sinal além de um
 * cronômetro contando segundos, e o usuário não tinha como saber se o processo estava
 * de fato avançando ou tinha travado.
 *
 * <p>Nem toda fonte passa por todas as fases (ex.: CSV/GeoJSON não descompactam nada;
 * fonte via URL não "envia arquivo") — cada {@link IngestaoExecutor} só reporta as que
 * fazem sentido pro próprio formato.
 */
public enum FaseExecucao {
    /** Intervalo curto entre a chegada da requisição e o primeiro reporte real de fase. */
    INICIANDO,
    /** Requisição HTTP à fonte (CSV, GeoJSON via WFS/ArcGIS) — inclui paginação. */
    BUSCANDO_DADOS,
    /** Só Shapefile: abrindo o .zip enviado por upload. */
    DESCOMPACTANDO,
    /** Convertendo cada registro (serialização JSON, geometria→WKT) antes de gravar. */
    LENDO_REGISTROS,
    /** Gravando em {@code bronze.registro_bruto}. */
    GRAVANDO,
    CONCLUIDO,
    ERRO
}
