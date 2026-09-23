package br.gov.sp.cps.datagis.gold;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositório Spring Data JPA para a entidade {@link TipoIndicador} na camada Gold.
 */
@Repository
public interface TipoIndicadorRepository extends JpaRepository<TipoIndicador, Long> {

    boolean existsByCodigo(String codigo);

    Optional<TipoIndicador> findByCodigo(String codigo);
}
