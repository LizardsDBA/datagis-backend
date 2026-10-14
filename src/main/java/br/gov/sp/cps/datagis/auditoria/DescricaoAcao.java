package br.gov.sp.cps.datagis.auditoria;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Traduz "metodo + rota" numa acao legivel pro log de auditoria ("Liberar lote para a
 * Silver" em vez de {@code POST /api/quarentena/lotes/45/liberar}).
 *
 * <p>O casamento e feito aqui mesmo, com os moldes dos controllers, e nao pelo atributo de
 * "melhor padrao" do Spring MVC: a requisicao recusada pela seguranca (403) nunca chega no
 * controller, e mesmo assim precisa aparecer no log dizendo o que se TENTOU fazer.
 *
 * <p>Endpoint novo que altera dado = linha nova aqui. O {@code DescricaoAcaoTest} varre os
 * controllers e falha se algum POST/PUT/DELETE ficou sem descricao.
 */
public final class DescricaoAcao {

    private static final Map<String, String> ACOES = new LinkedHashMap<>();

    static {
        // Fontes de dados, ingestao e mapeamento
        ACOES.put("POST /api/fontes", "Cadastrar fonte de dados");
        ACOES.put("PUT /api/fontes/{id}", "Editar fonte de dados");
        ACOES.put("POST /api/fontes/{id}/executar", "Executar ingestao da fonte");
        ACOES.put("POST /api/fontes/{id}/upload", "Enviar arquivo para ingestao");
        ACOES.put("POST /api/fontes/{fonteId}/mapeamentos", "Salvar mapeamento de colunas");
        ACOES.put("DELETE /api/fontes/{fonteId}/mapeamentos/{id}", "Excluir mapeamento de coluna");
        // Lotes e quarentena
        ACOES.put("POST /api/lotes/{id}/reprocessar", "Reprocessar lote");
        ACOES.put("POST /api/quarentena/lotes/{loteId}/liberar", "Liberar lote para a Silver");
        ACOES.put("POST /api/quarentena/lotes/{loteId}/descartar", "Descartar lote");
        ACOES.put("POST /api/quarentena/dominios", "Cadastrar valor de dominio");
        ACOES.put("POST /api/quarentena/{entidade}/{id}/descartar", "Descartar registro da quarentena");
        ACOES.put("POST /api/quarentena/{entidade}/{id}/iniciar-analise", "Iniciar analise de registro da quarentena");
        ACOES.put("POST /api/quarentena/{entidade}/{id}/reprocessar", "Corrigir e revalidar registro da quarentena");
        // Conjuntos, Gold e indicadores
        ACOES.put("POST /conjuntos", "Cadastrar conjunto de dados");
        ACOES.put("POST /api/gold/publicar", "Publicar indicadores na Gold");
        ACOES.put("POST /api/gold/imoveis/{codImovel}/recalcular", "Recalcular indicadores do imovel");
        ACOES.put("POST /api/gold/mapa-camadas/{codigo}/publicar", "Publicar camada de mapa");
        ACOES.put("POST /api/indicadores/tipos", "Cadastrar tipo de indicador");
        ACOES.put("PUT /api/indicadores/tipos/{id}", "Editar tipo de indicador");
        ACOES.put("DELETE /api/indicadores/tipos/{id}", "Desativar tipo de indicador");
        // Usuarios e acessos
        ACOES.put("POST /api/admin/usuarios", "Cadastrar usuario");
        ACOES.put("PUT /api/admin/usuarios/{id}", "Editar dados do usuario");
        ACOES.put("PUT /api/admin/usuarios/{id}/roles", "Alterar perfis e permissoes do usuario");
        ACOES.put("PUT /api/admin/usuarios/{id}/senha", "Redefinir senha do usuario");
    }

    private static final Map<String, PathPattern> MOLDES = new LinkedHashMap<>();

    static {
        ACOES.keySet().forEach(chave -> {
            String molde = chave.substring(chave.indexOf(' ') + 1);
            MOLDES.putIfAbsent(molde, PathPatternParser.defaultInstance.parse(molde));
        });
    }

    private DescricaoAcao() {
    }

    /** Molde e descricao da acao, quando a rota for conhecida. */
    public record Acao(String rotaPadrao, String descricao) {
    }

    public static Optional<Acao> identificar(String metodo, String rota) {
        PathContainer caminho = PathContainer.parsePath(rota);
        // Molde literal ganha de molde com variavel ("/lotes/{loteId}/liberar" antes de
        // "/{entidade}/{id}/descartar"): a ordem do mapa ja garante isso.
        for (Map.Entry<String, String> entrada : ACOES.entrySet()) {
            String chave = entrada.getKey();
            String metodoChave = chave.substring(0, chave.indexOf(' '));
            String molde = chave.substring(chave.indexOf(' ') + 1);
            if (metodoChave.equals(metodo) && MOLDES.get(molde).matches(caminho)) {
                return Optional.of(new Acao(molde, entrada.getValue()));
            }
        }
        return Optional.empty();
    }
}
