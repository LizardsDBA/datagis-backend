package br.gov.sp.cps.datagis.controle;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FonteDadoRepository extends JpaRepository<FonteDado, Long> {

    Optional<FonteDado> findByNome(String nome);

    List<FonteDado> findByAtivoTrueAndCronExpressaoIsNotNull();
}