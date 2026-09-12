package br.gov.sp.cps.datagis.ingestao;

import java.util.function.Consumer;

/**
 * Fonte de {@link RegistroPreparado} que entrega um registro de cada vez pro
 * {@code consumidor}, em vez de devolver uma {@code List} pronta — usado quando
 * materializar tudo em memória antes de gravar não é viável.
 *
 * <p>Achado ao vivo (2026-09-11, `docs/upload-shapefile-car.md` §3.4): um Shapefile de
 * 3,24 GB fazia o {@code IngestaoShapefileExecutor} estourar o heap só juntando os
 * registros numa lista, antes mesmo de gravar a primeira linha no Bronze. Quem
 * implementa esta interface decide como iterar a fonte (arquivo, resposta HTTP, etc.)
 * e chama {@code consumidor.accept(...)} um registro por vez; quem consome (
 * {@link LoteIngestaoWriter#gravarStreaming}) grava e libera cada um antes de pedir o
 * próximo, então a memória usada não cresce com o tamanho da fonte.
 */
@FunctionalInterface
public interface FornecedorRegistros {
    void fornecer(Consumer<RegistroPreparado> consumidor);
}
