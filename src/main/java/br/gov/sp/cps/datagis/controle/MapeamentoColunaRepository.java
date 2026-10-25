package br.gov.sp.cps.datagis.controle;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface MapeamentoColunaRepository extends JpaRepository<MapeamentoColuna, Long> {

    List<MapeamentoColuna> findByFonteDadoId(Long fonteDadoId);

    List<MapeamentoColuna> findByFonteDadoIdAndAtributoDestinoIgnoreCase(Long fonteDadoId, String atributoDestino);

    void deleteByFonteDadoId(Long fonteDadoId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM MapeamentoColuna m WHERE m.fonteDadoId = :fonteDadoId AND LOWER(m.atributoDestino) = LOWER(:atributoDestino)")
    void deleteByFonteDadoIdAndAtributoDestinoIgnoreCase(@Param("fonteDadoId") Long fonteDadoId, @Param("atributoDestino") String atributoDestino);
}
