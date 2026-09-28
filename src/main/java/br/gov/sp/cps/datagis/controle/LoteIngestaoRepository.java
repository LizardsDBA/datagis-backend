package br.gov.sp.cps.datagis.controle;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoteIngestaoRepository extends JpaRepository<LoteIngestao, Long> {

    Optional<LoteIngestao> findFirstByFonteDadoIdOrderByIdDesc(Long fonteDadoId);

    /** Usado pela tela de Conjunto de Dados — último lote que efetivamente chegou na Silver. */
    Optional<LoteIngestao> findFirstByFonteDadoIdAndStatusOrderByIdDesc(Long fonteDadoId, String status);

    @Query("SELECT l FROM LoteIngestao l LEFT JOIN FETCH l.fonteDado ORDER BY l.id DESC")
    List<LoteIngestao> findAllComFonteOrderByIdDesc();

    @Query("SELECT l FROM LoteIngestao l LEFT JOIN FETCH l.fonteDado WHERE l.id = :id")
    Optional<LoteIngestao> findByIdComFonte(@Param("id") Long id);

    List<LoteIngestao> findAllByOrderByIdDesc();
}

