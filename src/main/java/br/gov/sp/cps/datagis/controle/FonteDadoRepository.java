package br.gov.sp.cps.datagis.controle;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FonteDadoRepository extends JpaRepository<FonteDado, Long> {

    Optional<FonteDado> findByNome(String nome);

    List<FonteDado> findByAtivoTrueAndCronExpressaoIsNotNull();

    /**
     * Grava só a próxima execução agendada (LIZARDS-54). Update pontual, e não save da
     * entidade inteira, pra não sobrescrever o que outra thread acabou de gravar na fonte
     * (ex.: o resultado da execução que acabou de rodar).
     */
    @Transactional
    @Modifying
    @Query("UPDATE FonteDado f SET f.proximaExecucaoEm = :proxima WHERE f.id = :id")
    int atualizarProximaExecucao(@Param("id") Long id, @Param("proxima") LocalDateTime proxima);
}
