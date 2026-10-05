-- pkg_calculo_conservacao: ICV, unico indicador da task "Conservacao" (LIZARDS-31).
-- STUB - depende de poligono de vegetacao nativa, que nenhuma fonte cadastrada hoje
-- traz (API SICAR Temas do CAR, bloqueada; Plano B via IAT investigado e nao
-- funciona ainda - docs/tutorial-acesso-fontes-pendentes.md secao 2). Ver
-- docs/gold-procedures-calculo-publicacao.md secao 2.
CREATE OR REPLACE PACKAGE ${goldSchema}.pkg_calculo_conservacao AS

    -- ICV = area de vegetacao nativa dentro do imovel / area do imovel. Sem fonte
    -- hoje - nao grava nada em indicador_resultado, p_indicador_versao_id fica NULL.
    PROCEDURE calcular_icv(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    );

END pkg_calculo_conservacao;
/

CREATE OR REPLACE PACKAGE BODY ${goldSchema}.pkg_calculo_conservacao AS

    PROCEDURE calcular_icv(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    ) IS
    BEGIN
        p_indicador_versao_id := NULL; -- sem fonte (vegetacao nativa) - stub
    END calcular_icv;

END pkg_calculo_conservacao;
/
