package br.gov.sp.cps.datagis.controle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConjuntoRepository extends JpaRepository<Conjunto, Long> {
}
