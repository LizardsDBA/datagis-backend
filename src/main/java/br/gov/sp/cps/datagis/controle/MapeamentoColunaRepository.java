package br.gov.sp.cps.datagis.controle;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MapeamentoColunaRepository extends JpaRepository<MapeamentoColuna, Long> {

    List<MapeamentoColuna> findByFonteDadoId(Long fonteDadoId);

    void deleteByFonteDadoId(Long fonteDadoId);
}
