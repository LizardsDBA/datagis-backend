package br.gov.sp.cps.datagis.gold;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ImovelPublicadoRepository extends JpaRepository<ImovelPublicado, Long> {
    Optional<ImovelPublicado> findByCodImovel(String codImovel);
}