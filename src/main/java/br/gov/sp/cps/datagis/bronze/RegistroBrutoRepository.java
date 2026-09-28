package br.gov.sp.cps.datagis.bronze;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistroBrutoRepository extends JpaRepository<RegistroBruto, Long> {

    List<RegistroBruto> findByLoteIngestaoIdOrderByIdAsc(Long loteIngestaoId);

    List<RegistroBruto> findByLoteIngestaoId(Long loteIngestaoId);

    /** Usado pela tela de Conjunto de Dados — pagina os registros de um lote específico. */
    Page<RegistroBruto> findByLoteIngestaoId(Long loteIngestaoId, Pageable pageable);

    /**
     * Usado por {@code LoteIngestaoWriter#gravarStreaming} quando descobre, só depois
     * de gravar tudo, que o conteúdo não mudou desde o último lote — apaga em bloco em
     * vez de linha a linha.
     */
    long deleteByLoteIngestaoId(Long loteIngestaoId);
}
