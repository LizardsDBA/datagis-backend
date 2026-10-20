package br.gov.sp.cps.datagis.controle;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Catálogo de tabelas da camada Silver, com metadados de colunas canônicas
 * e tabelas de domínio aplicáveis a cada entidade.
 */
@Service
public class CatalogoSilverService {

    public record ColunaSilverDto(
            String nome,
            String label,
            String tipoDado,
            String destinoTipo,
            boolean obrigatorio,
            Double valorMinimo,
            String tabelaReferencia,
            boolean validarGeometria,
            boolean validarCpfCnpj,
            boolean validarRecortePr) {

        public static ColunaSilverDto coluna(String nome, String label, String tipoDado, boolean obrigatorio) {
            return new ColunaSilverDto(nome, label, tipoDado, "COLUNA", obrigatorio, null, null, false, false, false);
        }

        public static ColunaSilverDto colunaComDominio(String nome, String label, String tipoDado, boolean obrigatorio, String tabelaReferencia) {
            return new ColunaSilverDto(nome, label, tipoDado, "COLUNA", obrigatorio, null, tabelaReferencia, false, false, false);
        }

        public static ColunaSilverDto colunaNumero(String nome, String label, boolean obrigatorio, Double valorMinimo) {
            return new ColunaSilverDto(nome, label, "numero", "COLUNA", obrigatorio, valorMinimo, null, false, false, false);
        }

        public static ColunaSilverDto geometria(String nome, String label) {
            return new ColunaSilverDto(nome, label, "wkt", "GEOMETRIA", false, null, null, true, false, true);
        }

        public static ColunaSilverDto cpfCnpj(String nome, String label, boolean obrigatorio) {
            return new ColunaSilverDto(nome, label, "cpf_cnpj", "COLUNA", obrigatorio, null, null, false, true, false);
        }
    }

    public record DominioSilverDto(
            String tabela,
            String label,
            String descricao) {}

    public record EsquemaSilverDto(
            String id,
            String nome,
            String descricao,
            List<ColunaSilverDto> colunas,
            List<DominioSilverDto> dominios) {}

    private static final List<EsquemaSilverDto> ESQUEMAS = List.of(
        new EsquemaSilverDto(
            "imovel_rural",
            "Imóvel Rural (CAR)",
            "Cadastro Ambiental Rural - limites e situação cadastral de imóveis rurais",
            List.of(
                ColunaSilverDto.coluna("cod_imovel", "Código do Imóvel (Chave Natural)", "texto", true),
                ColunaSilverDto.colunaComDominio("situacao_imovel", "Situação do Imóvel", "texto", true, "situacao_imovel"),
                ColunaSilverDto.colunaComDominio("condicao_imovel", "Condição do Imóvel", "texto", false, "condicao_imovel"),
                ColunaSilverDto.colunaComDominio("tipo_imovel", "Tipo do Imóvel", "texto", false, "tipo_imovel"),
                ColunaSilverDto.colunaNumero("area_ha", "Área do Imóvel (ha)", false, 0.0),
                ColunaSilverDto.colunaNumero("modulos_fiscais", "Módulos Fiscais", false, 0.0),
                ColunaSilverDto.coluna("municipio", "Município (Código IBGE / Nome)", "texto", false),
                ColunaSilverDto.coluna("data_registro", "Data de Registro", "data_iso", false),
                ColunaSilverDto.geometria("geometria", "Geometria Espacial (Polígono)")
            ),
            List.of(
                new DominioSilverDto("situacao_imovel", "situacao_imovel (AT, PE, CA, SU...)", "Situação cadastral no SICAR"),
                new DominioSilverDto("condicao_imovel", "condicao_imovel (Aguardando análise, Em análise...)", "Condição do processo de análise"),
                new DominioSilverDto("tipo_imovel", "tipo_imovel (IRU, AST, PCT...)", "Tipo do imóvel rural")
            )
        ),
        new EsquemaSilverDto(
            "area_protegida",
            "Área Protegida (UC / TI)",
            "Unidades de Conservação e Terras Indígenas",
            List.of(
                ColunaSilverDto.coluna("codigo_origem", "Código de Origem (CNUC / FUNAI)", "texto", true),
                ColunaSilverDto.coluna("nome", "Nome da Unidade / Terra Indígena", "texto", true),
                ColunaSilverDto.colunaNumero("area_ha", "Área Total (ha)", false, 0.0),
                ColunaSilverDto.colunaComDominio("tipo_area_protegida", "Tipo de Área Protegida", "texto", false, "tipo_area_protegida"),
                ColunaSilverDto.colunaComDominio("categoria_area_protegida", "Categoria de Área Protegida", "texto", false, "categoria_area_protegida"),
                ColunaSilverDto.colunaComDominio("orgao", "Órgão Gestor Responsável", "texto", false, "orgao"),
                ColunaSilverDto.colunaComDominio("bioma", "Bioma Predominante", "texto", false, "bioma"),
                ColunaSilverDto.coluna("ato_legal", "Ato Legal de Criação", "texto", false),
                ColunaSilverDto.geometria("geometria", "Geometria Espacial (Polígono)")
            ),
            List.of(
                new DominioSilverDto("tipo_area_protegida", "tipo_area_protegida (Proteção Integral, Uso Sustentável, TI...)", "Classificação geral da área"),
                new DominioSilverDto("categoria_area_protegida", "categoria_area_protegida (PARNA, REBIO, FLONA, APA...)", "Categorias do SNUC"),
                new DominioSilverDto("orgao", "orgao (ICMBio, IBAMA, FUNAI, IAT, INPE...)", "Órgão gestor ou fiscalizador"),
                new DominioSilverDto("bioma", "bioma (Mata Atlântica, Cerrado...)", "Bioma brasileiro")
            )
        ),
        new EsquemaSilverDto(
            "embargo_ambiental",
            "Embargo Ambiental",
            "Termos de embargo aplicados por órgãos ambientais (IBAMA / ICMBio / IAT)",
            List.of(
                ColunaSilverDto.coluna("numero_termo", "Número do Termo de Embargo (Chave Natural)", "texto", true),
                ColunaSilverDto.coluna("data_embargo", "Data do Embargo", "data_iso", false),
                ColunaSilverDto.colunaNumero("area_embargada_ha", "Área Embargada (ha)", false, 0.0),
                ColunaSilverDto.colunaComDominio("orgao", "Órgão Emissor", "texto", true, "orgao"),
                ColunaSilverDto.colunaComDominio("tipo_area_embargo", "Tipo de Infração / Área Embargada", "texto", false, "tipo_area_embargo"),
                ColunaSilverDto.cpfCnpj("cpf_cnpj", "Documento do Infrator (CPF / CNPJ)", false),
                ColunaSilverDto.coluna("nome_autuado", "Nome ou Razão Social do Infrator/Autuado", "texto", false),
                ColunaSilverDto.coluna("municipio", "Município", "texto", false),
                ColunaSilverDto.coluna("descricao", "Descrição do Motivo do Embargo", "texto", false),
                ColunaSilverDto.coluna("num_processo", "Número do Processo Administrativo", "texto", false),
                ColunaSilverDto.coluna("num_auto_infracao", "Referência ao Auto de Infração", "texto", false),
                ColunaSilverDto.geometria("geometria", "Geometria do Embargo (Polígono)")
            ),
            List.of(
                new DominioSilverDto("tipo_area_embargo", "tipo_area_embargo (Desmatamento, Queimada, Exploração irregular...)", "Motivo ou tipo do embargo"),
                new DominioSilverDto("orgao", "orgao (IBAMA, ICMBio, IAT...)", "Órgão emissor do embargo")
            )
        ),
        new EsquemaSilverDto(
            "auto_infracao",
            "Auto de Infração",
            "Autos de infração e penalidades lavradas por fiscalização ambiental",
            List.of(
                ColunaSilverDto.coluna("numero_auto", "Número do Auto de Infração (Chave Natural)", "texto", true),
                ColunaSilverDto.coluna("data_auto", "Data da Lavratura do Auto", "data_iso", false),
                ColunaSilverDto.colunaNumero("valor_multa", "Valor da Multa (R$)", false, 0.0),
                ColunaSilverDto.colunaComDominio("orgao", "Órgão Autuador", "texto", true, "orgao"),
                ColunaSilverDto.colunaComDominio("tipo_auto_infracao", "Tipo de Auto de Infração", "texto", false, "tipo_auto_infracao"),
                ColunaSilverDto.colunaComDominio("tipo_infracao", "Tipo de Infração", "texto", false, "tipo_infracao"),
                ColunaSilverDto.colunaComDominio("tipo_pessoa", "Tipo de Pessoa (Física / Jurídica)", "texto", false, "tipo_pessoa"),
                ColunaSilverDto.colunaComDominio("status_auto_infracao", "Status do Auto", "texto", false, "status_auto_infracao"),
                ColunaSilverDto.cpfCnpj("cpf_cnpj_infrator", "CPF ou CNPJ do Autuado", false),
                ColunaSilverDto.coluna("nome_infrator", "Nome do Autuado", "texto", false),
                ColunaSilverDto.coluna("municipio", "Município", "texto", false),
                ColunaSilverDto.coluna("descricao", "Descrição da Infração", "texto", false),
                ColunaSilverDto.coluna("num_processo", "Número do Processo Administrativo", "texto", false),
                ColunaSilverDto.geometria("geometria", "Geometria Espacial (Ponto / Polígono)")
            ),
            List.of(
                new DominioSilverDto("tipo_auto_infracao", "tipo_auto_infracao (Multa simples, Advertência, Apreensão...)", "Modalidade da sanção"),
                new DominioSilverDto("tipo_infracao", "tipo_infracao (Contra a flora, fauna, poluição...)", "Tipologia ambiental da infração"),
                new DominioSilverDto("tipo_pessoa", "tipo_pessoa (PF, PJ)", "Natureza jurídica"),
                new DominioSilverDto("status_auto_infracao", "status_auto_infracao (Lavrado, Homologado, Julgado...)", "Situação processual"),
                new DominioSilverDto("orgao", "orgao (IBAMA, ICMBio, IAT...)", "Órgão emissor do auto")
            )
        ),
        new EsquemaSilverDto(
            "desmatamento",
            "Desmatamento (PRODES)",
            "Polígonos de detecção de desmatamento consolidado INPE",
            List.of(
                ColunaSilverDto.coluna("identificador_origem", "Identificador de Origem (Chave Natural)", "texto", true),
                ColunaSilverDto.colunaNumero("ano", "Ano da Detecção PRODES", false, 1980.0),
                ColunaSilverDto.colunaNumero("area_ha", "Área Desmatada (ha)", false, 0.0),
                ColunaSilverDto.colunaComDominio("classe_prodes", "Classe PRODES", "texto", false, "classe_prodes"),
                ColunaSilverDto.colunaComDominio("satelite", "Satélite", "texto", false, "satelite"),
                ColunaSilverDto.colunaComDominio("sensor", "Sensor", "texto", false, "sensor"),
                ColunaSilverDto.coluna("data_imagem", "Data da Imagem", "data_iso", false),
                ColunaSilverDto.geometria("geometria", "Geometria do Desmatamento (Polígono)")
            ),
            List.of(
                new DominioSilverDto("classe_prodes", "classe_prodes (Desmatamento, Não floresta, Floresta...)", "Classificação PRODES"),
                new DominioSilverDto("satelite", "satelite (LANDSAT, SENTINEL, CBERS...)", "Plataforma de satélite"),
                new DominioSilverDto("sensor", "sensor (OLI, MSI, WFI...)", "Sensor óptico")
            )
        ),
        new EsquemaSilverDto(
            "foco_calor",
            "Focos de Calor (Queimadas)",
            "Detecções de focos de queimada por satélite (INPE Queimadas)",
            List.of(
                ColunaSilverDto.coluna("identificador_origem", "Identificador de Origem (Chave Natural)", "texto", true),
                ColunaSilverDto.coluna("data_hora", "Data e Hora do Foco", "data_iso", false),
                ColunaSilverDto.colunaComDominio("satelite", "Satélite", "texto", false, "satelite"),
                ColunaSilverDto.colunaComDominio("bioma", "Bioma", "texto", false, "bioma"),
                ColunaSilverDto.coluna("municipio", "Município", "texto", false),
                ColunaSilverDto.colunaNumero("latitude", "Latitude", false, null),
                ColunaSilverDto.colunaNumero("longitude", "Longitude", false, null),
                ColunaSilverDto.colunaNumero("risco_fogo", "Risco de Fogo", false, null),
                ColunaSilverDto.colunaNumero("dias_sem_chuva", "Dias Sem Chuva", false, 0.0),
                ColunaSilverDto.colunaNumero("precipitacao_mm", "Precipitação (mm)", false, 0.0),
                ColunaSilverDto.geometria("geometria", "Geometria Espacial (Ponto)")
            ),
            List.of(
                new DominioSilverDto("satelite", "satelite (AQUA, TERRA, GOES-16, NOAA-20...)", "Satélite meteorológico"),
                new DominioSilverDto("bioma", "bioma (Mata Atlântica, Cerrado...)", "Bioma brasileiro")
            )
        ),
        new EsquemaSilverDto(
            "municipio",
            "Município (Malha IBGE)",
            "Malha territorial e limites municipais",
            List.of(
                ColunaSilverDto.coluna("cod_ibge", "Código IBGE (Chave Natural)", "texto", true),
                ColunaSilverDto.coluna("nome", "Nome do Município", "texto", true),
                ColunaSilverDto.colunaComDominio("unidade_federativa", "Unidade Federativa (UF)", "texto", false, "unidade_federativa"),
                ColunaSilverDto.colunaComDominio("bioma_predominante", "Bioma Predominante", "texto", false, "bioma"),
                ColunaSilverDto.colunaNumero("area_km2", "Área Total do Município (km²)", false, 0.0),
                ColunaSilverDto.geometria("geometria", "Geometria Limite Municipal (Polígono)")
            ),
            List.of(
                new DominioSilverDto("unidade_federativa", "unidade_federativa (PR, SP, SC...)", "Sigla do estado da federação"),
                new DominioSilverDto("bioma", "bioma (Mata Atlântica, Cerrado...)", "Bioma brasileiro")
            )
        ),
        new EsquemaSilverDto(
            "reserva_legal",
            "Reserva Legal (CAR)",
            "Polígonos de Reserva Legal declarados no CAR",
            List.of(
                ColunaSilverDto.coluna("identificador_origem", "Identificador de Origem (Chave Natural)", "texto", true),
                ColunaSilverDto.coluna("cod_imovel", "Código do Imóvel Vinculado", "texto", false),
                ColunaSilverDto.colunaNumero("area_ha", "Área da Reserva Legal (ha)", false, 0.0),
                ColunaSilverDto.colunaComDominio("situacao_bruto", "Situação do Imóvel", "texto", false, "situacao_imovel"),
                ColunaSilverDto.colunaComDominio("condicao_bruto", "Condição do Imóvel", "texto", false, "condicao_imovel"),
                ColunaSilverDto.geometria("geometria", "Geometria da Reserva Legal (Polígono)")
            ),
            List.of(
                new DominioSilverDto("situacao_imovel", "situacao_imovel (AT, PE, CA, SU...)", "Situação cadastral do imóvel"),
                new DominioSilverDto("condicao_imovel", "condicao_imovel (Aguardando análise, Em análise...)", "Condição de análise do imóvel")
            )
        ),
        new EsquemaSilverDto(
            "app_declarada",
            "Área de Preservação Permanente - APP (CAR)",
            "Polígonos de APP declarados no CAR",
            List.of(
                ColunaSilverDto.coluna("identificador_origem", "Identificador de Origem (Chave Natural)", "texto", true),
                ColunaSilverDto.coluna("cod_imovel", "Código do Imóvel Vinculado", "texto", false),
                ColunaSilverDto.colunaNumero("area_ha", "Área da APP (ha)", false, 0.0),
                ColunaSilverDto.colunaComDominio("situacao_bruto", "Situação do Imóvel", "texto", false, "situacao_imovel"),
                ColunaSilverDto.colunaComDominio("condicao_bruto", "Condição do Imóvel", "texto", false, "condicao_imovel"),
                ColunaSilverDto.geometria("geometria", "Geometria da APP (Polígono)")
            ),
            List.of(
                new DominioSilverDto("situacao_imovel", "situacao_imovel (AT, PE, CA, SU...)", "Situação cadastral do imóvel"),
                new DominioSilverDto("condicao_imovel", "condicao_imovel (Aguardando análise, Em análise...)", "Condição de análise do imóvel")
            )
        ),
        new EsquemaSilverDto(
            "vegetacao_nativa_declarada",
            "Vegetação Nativa (CAR)",
            "Polígonos de Vegetação Nativa remanescente declarados no CAR",
            List.of(
                ColunaSilverDto.coluna("identificador_origem", "Identificador de Origem (Chave Natural)", "texto", true),
                ColunaSilverDto.coluna("cod_imovel", "Código do Imóvel Vinculado", "texto", false),
                ColunaSilverDto.colunaNumero("area_ha", "Área de Vegetação Nativa (ha)", false, 0.0),
                ColunaSilverDto.colunaComDominio("situacao_bruto", "Situação do Imóvel", "texto", false, "situacao_imovel"),
                ColunaSilverDto.colunaComDominio("condicao_bruto", "Condição do Imóvel", "texto", false, "condicao_imovel"),
                ColunaSilverDto.geometria("geometria", "Geometria da Vegetação (Polígono)")
            ),
            List.of(
                new DominioSilverDto("situacao_imovel", "situacao_imovel (AT, PE, CA, SU...)", "Situação cadastral do imóvel"),
                new DominioSilverDto("condicao_imovel", "condicao_imovel (Aguardando análise, Em análise...)", "Condição de análise do imóvel")
            )
        ),
        new EsquemaSilverDto(
            "bioma",
            "Bioma (IBGE)",
            "Limites dos Biomas Brasileiros",
            List.of(
                ColunaSilverDto.coluna("codigo_bioma", "Código do Bioma", "texto", true),
                ColunaSilverDto.coluna("nome", "Nome do Bioma", "texto", true),
                ColunaSilverDto.geometria("geometria", "Geometria do Bioma (Polígono)")
            ),
            List.of(
                new DominioSilverDto("bioma", "bioma (Mata Atlântica, Cerrado...)", "Tabela de biomas")
            )
        )
    );

    public List<EsquemaSilverDto> listarEsquemas() {
        return ESQUEMAS;
    }

    public EsquemaSilverDto buscarPorId(String id) {
        if (id == null) return null;
        String idNormalizado = id.trim().toLowerCase();
        return ESQUEMAS.stream()
                .filter(e -> e.id().equalsIgnoreCase(idNormalizado))
                .findFirst()
                .orElse(null);
    }
}
