package br.gov.sp.cps.datagis.seguranca;

/**
 * Catalogo de roles de permissao do RBAC. Cada role libera UMA coisa so, e e so isso que
 * o codigo confere ({@code hasAuthority(...)} no {@code SecurityConfig}, {@code meta.permissao}
 * nas rotas do frontend) - perfil nenhum aparece em regra de acesso.
 *
 * <p>O {@code codigo} e o nome da role no Keycloak (realm role) e chega no JWT em
 * {@code realm_access.roles}. Esta enum e a fonte de verdade: o {@link KeycloakProvisionador}
 * cria no Keycloak qualquer role daqui que ainda nao exista, na subida do backend.
 * Role nova = constante nova aqui + regra no {@code SecurityConfig}.
 */
public enum Permissao {

    FONTE_VISUALIZAR("fonte:visualizar", "Listar e ver fontes de dados e mapeamentos de colunas"),
    FONTE_GERENCIAR("fonte:gerenciar", "Cadastrar, editar, executar e fazer upload de fontes; editar mapeamentos"),
    LOTE_VISUALIZAR("lote:visualizar", "Listar lotes de ingestao e acompanhar o pipeline"),
    LOTE_GERENCIAR("lote:gerenciar", "Liberar, descartar e reprocessar lotes"),
    QUARENTENA_VISUALIZAR("quarentena:visualizar", "Ver a fila da quarentena e o detalhe dos registros"),
    QUARENTENA_TRATAR("quarentena:tratar", "Corrigir, revalidar e descartar registros; cadastrar valores de dominio"),
    DADOS_VISUALIZAR("dados:visualizar", "Comparar Bronze, Silver e Quarentena registro a registro"),
    CONJUNTO_VISUALIZAR("conjunto:visualizar", "Ver conjuntos de dados e cobertura territorial"),
    CONJUNTO_GERENCIAR("conjunto:gerenciar", "Cadastrar conjuntos de dados"),
    INDICADOR_VISUALIZAR("indicador:visualizar", "Ver indicadores, imoveis publicados e status da Gold"),
    INDICADOR_PUBLICAR("indicador:publicar", "Publicar ou recalcular a Gold; configurar tipos de indicador"),
    AUDITORIA_VISUALIZAR("auditoria:visualizar", "Consultar os logs de auditoria"),
    USUARIO_GERENCIAR("usuario:gerenciar", "Cadastrar usuarios e atribuir perfis e permissoes");

    private final String codigo;
    private final String descricao;

    Permissao(String codigo, String descricao) {
        this.codigo = codigo;
        this.descricao = descricao;
    }

    public String codigo() {
        return codigo;
    }

    public String descricao() {
        return descricao;
    }

    public static boolean ehPermissao(String codigoRole) {
        for (Permissao p : values()) {
            if (p.codigo.equals(codigoRole)) {
                return true;
            }
        }
        return false;
    }
}
