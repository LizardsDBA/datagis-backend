package br.gov.sp.cps.datagis.ingestao;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Guarda o progresso da execução mais recente de cada fonte, só em memória.
 *
 * <p><b>Por que não vem do banco:</b> a gravação inteira roda dentro de uma única
 * {@code @Transactional} (ver {@link LoteIngestaoWriter}), então nenhuma consulta
 * externa enxerga uma linha sequer até o commit final — confirmado ao vivo monitorando
 * o upload de 3,3GB do APPS (LIZARDS-3): uma sessão `sqlplus` separada mostrava sempre
 * zero registros, mesmo com a ingestão avançando de verdade. Progresso precisa vir de
 * um sinal que o próprio processo escreve enquanto roda, não de uma leitura do estado
 * final que só existe no fim.
 *
 * <p>Uma entrada por {@code fonteId}, sempre sobrescrita na próxima execução — não
 * cresce sem limite (o teto é o número de fontes cadastradas). Não sobrevive a restart
 * do backend, mas isso é só cosmético: a execução em si é atômica (tudo ou nada), não
 * há progresso "real" pra recuperar depois de uma queda.
 */
@Component
public class ProgressoExecucaoRegistry {

    private final Map<Long, ProgressoExecucao> estados = new ConcurrentHashMap<>();

    public void iniciar(Long fonteId) {
        estados.put(fonteId, new ProgressoExecucao(FaseExecucao.INICIANDO, 0, null, null, LocalDateTime.now()));
    }

    public void atualizar(Long fonteId, FaseExecucao fase, long registrosProcessados, Long totalEstimado, String detalhe) {
        estados.put(fonteId, new ProgressoExecucao(fase, registrosProcessados, totalEstimado, detalhe, LocalDateTime.now()));
    }

    /** Marca o fim (CONCLUIDO ou ERRO), preservando a última contagem/total conhecidos. */
    public void finalizar(Long fonteId, FaseExecucao faseFinal) {
        ProgressoExecucao atual = estados.get(fonteId);
        long registros = atual != null ? atual.registrosProcessados() : 0;
        Long total = atual != null ? atual.totalEstimado() : null;
        estados.put(fonteId, new ProgressoExecucao(faseFinal, registros, total, null, LocalDateTime.now()));
    }

    public Optional<ProgressoExecucao> obter(Long fonteId) {
        return Optional.ofNullable(estados.get(fonteId));
    }

    public Map<Long, ProgressoExecucao> obterTodos() {
        return Map.copyOf(estados);
    }

    /** Retorna true se a fonte possui execução ativa em andamento (nem CONCLUIDO nem ERRO). */
    public boolean isExecutando(Long fonteId) {
        return obter(fonteId)
                .map(p -> p.fase() != FaseExecucao.CONCLUIDO && p.fase() != FaseExecucao.ERRO)
                .orElse(false);
    }
}
