package br.gov.sp.cps.datagis.armazenamento;

import java.io.InputStream;

/**
 * Guarda o arquivo bruto ORIGINAL enviado por upload (ex.: o .zip de Shapefile do CAR
 * baixado manualmente pelo usuário) — não é o mecanismo de armazenamento da zona
 * Bronze em si (essa continua em tabela Oracle, {@code bronze.registro_bruto}); é só
 * pra guardar o arquivo cru até ele ser lido e transformado em registro_bruto.
 *
 * <p>Interface fina de propósito (CLAUDE.md §5.4) — hoje só existe a implementação
 * local-FS. Se um dia precisar de bucket (OCI Object Storage / GCS), troca a
 * implementação sem mexer em quem usa esta interface.
 */
public interface ArmazenamentoArquivoBruto {

    /** Salva o conteúdo e devolve um "endereço" opaco — quem chamou não precisa saber o que é. */
    String salvar(String nomeOriginal, InputStream conteudo);

    /** Lê de volta o que {@link #salvar} guardou, a partir do endereço que ele devolveu. */
    InputStream ler(String endereco);
}
