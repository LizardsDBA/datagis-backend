package br.gov.sp.cps.datagis.bronze;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistroBrutoRepository extends JpaRepository<RegistroBruto, Long> {

    /**
     * Usado por {@code LoteIngestaoWriter#gravarStreaming} quando descobre, só depois
     * de gravar tudo, que o conteúdo não mudou desde o último lote — apaga em bloco em
     * vez de linha a linha.
     */
    long deleteByLoteIngestaoId(Long loteIngestaoId);
}
