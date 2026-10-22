package br.gov.sp.cps.datagis.validacao;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Registrador em memória do progresso de lotes durante a transição Bronze→Silver
 * e revalidação da Quarentena.
 */
@Component
public class ProgressoTransicaoRegistry {

    private final Map<Long, ProgressoTransicao> estados = new ConcurrentHashMap<>();

    public void iniciar(Long loteId, int totalRegistros, String faseInicial) {
        estados.put(loteId, new ProgressoTransicao(
                loteId, faseInicial, 0, totalRegistros, 0, null, "EM_PROCESSAMENTO", LocalDateTime.now(), null));
    }

    public void atualizar(Long loteId, String fase, int processados, int totalRegistros, int erros, String detalhe) {
        ProgressoTransicao atual = estados.get(loteId);
        LocalDateTime inicio = atual != null ? atual.iniciadoEm() : LocalDateTime.now();
        estados.put(loteId, new ProgressoTransicao(
                loteId, fase, processados, totalRegistros, erros, detalhe, "EM_PROCESSAMENTO", inicio, null));
    }

    public void finalizar(Long loteId, String faseFinal, int processados, int totalRegistros, int erros, String statusFinal, String detalhe) {
        ProgressoTransicao atual = estados.get(loteId);
        LocalDateTime inicio = atual != null ? atual.iniciadoEm() : LocalDateTime.now();
        estados.put(loteId, new ProgressoTransicao(
                loteId, faseFinal, processados, totalRegistros, erros, detalhe, statusFinal, inicio, LocalDateTime.now()));
    }

    public Optional<ProgressoTransicao> obter(Long loteId) {
        return Optional.ofNullable(estados.get(loteId));
    }

    public Map<Long, ProgressoTransicao> obterTodos() {
        return Map.copyOf(estados);
    }

    public boolean isExecutando(Long loteId) {
        return obter(loteId)
                .map(p -> "EM_PROCESSAMENTO".equals(p.statusFinal())
                        && !"CONCLUIDO".equals(p.fase())
                        && !"ERRO".equals(p.fase())
                        && !"RETIDO_QUARENTENA".equals(p.fase()))
                .orElse(false);
    }
}
