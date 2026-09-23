package br.gov.sp.cps.datagis.validacao;

/**
 * Requisição para inclusão de novos valores de domínio em tabelas de referência da camada Silver.
 */
public record NovoDominioRequest(
        String tabela,
        String codigo,
        String descricao,
        String tipoAreaProtegida,
        Long tipoAreaProtegidaId,
        Boolean criarNovoTipo,
        String novoTipoAreaProtegidaCodigo,
        String novoTipoAreaProtegidaDescricao,
        String categoriaAreaProtegida,
        Long quarentenaItemId,
        String entidade,
        Long analistaId
) {
    public NovoDominioRequest(
            String tabela,
            String codigo,
            String descricao,
            String tipoAreaProtegida,
            String categoriaAreaProtegida,
            Long quarentenaItemId,
            String entidade,
            Long analistaId) {
        this(tabela, codigo, descricao, tipoAreaProtegida, null, false, null, null, categoriaAreaProtegida, quarentenaItemId, entidade, analistaId);
    }
}
