-- Seed de gold.regra_calculo: 1 linha por tipo_indicador (versao 1), com o texto
-- oficial da formula (desafio, pag. 6) em 'expressao'. Pre-requisito de qualquer
-- pkg_calculo_*.calcular_* - a procedure busca a regra vigente por tipo_indicador
-- antes de gravar qualquer indicador_versao.
--
-- IDesmat na v1 calcula o total agregado (todos os poligonos de desmatamento, sem
-- quebrar pelos 3 marcos temporais pedidos no desafio) - a quebra por marco fica pra
-- v2 desta regra_calculo quando o schema evoluir pra guardar 3 valores por indicador
-- em vez de 1 (ver docs/gold-procedures-calculo-publicacao.md).
INSERT INTO ${goldSchema}.regra_calculo (tipo_indicador_id, versao, expressao, descricao, vigente_de)
SELECT id, 1,
       'Sobreposicao do imovel com unidades de conservacao, terras indigenas, ' ||
       'quilombolas e florestas publicas (silver.area_protegida). ' ||
       'ISAP = (area de intersecao dissolvida / area total do imovel) x 100.',
       'Indice de Sobreposicao com Areas Protegidas - v1', TRUNC(SYSDATE)
FROM ${goldSchema}.tipo_indicador WHERE codigo = 'ISAP';

INSERT INTO ${goldSchema}.regra_calculo (tipo_indicador_id, versao, expressao, descricao, vigente_de)
SELECT id, 1,
       'Sobreposicao do imovel com embargos ambientais federais (silver.embargo_ambiental). ' ||
       'IAE = (area de intersecao dissolvida / area total do imovel) x 100.',
       'Indice de Area Embargada - v1', TRUNC(SYSDATE)
FROM ${goldSchema}.tipo_indicador WHERE codigo = 'IAE';

INSERT INTO ${goldSchema}.regra_calculo (tipo_indicador_id, versao, expressao, descricao, vigente_de)
SELECT id, 1,
       'Area (ha) e percentual de sobreposicao do imovel com supressao de ' ||
       'vegetacao (silver.desmatamento). v1 calcula o total agregado; quebra pelos ' ||
       '3 marcos temporais do desafio (22/07/2008, 31/07/2019, 31/12/2020) fica pra v2.',
       'Indice de Desmatamento - v1 (total agregado)', TRUNC(SYSDATE)
FROM ${goldSchema}.tipo_indicador WHERE codigo = 'IDESMAT';

INSERT INTO ${goldSchema}.regra_calculo (tipo_indicador_id, versao, expressao, descricao, vigente_de)
SELECT id, 1,
       'Focos de calor detectados dentro do imovel (silver.foco_calor) dividido pela ' ||
       'area do imovel em hectares, multiplicado por 1.000. IFC = (contagem / area_ha) x 1000.',
       'Indice de Focos de Calor - v1', TRUNC(SYSDATE)
FROM ${goldSchema}.tipo_indicador WHERE codigo = 'IFC';

-- Stubs (sem fonte - CLAUDE.md / docs/checklist-cobertura-fontes.md): regra_calculo
-- existe pra reservar o id/versao, mas a procedure correspondente nao grava
-- indicador_resultado nenhum ate a fonte aparecer.
INSERT INTO ${goldSchema}.regra_calculo (tipo_indicador_id, versao, expressao, descricao, vigente_de)
SELECT id, 1,
       '[SEM FONTE] Area de vegetacao nativa dentro do imovel / area do imovel. ' ||
       'Depende de poligono de vegetacao nativa (CAR, API SICAR Temas, ou alternativa) - ' ||
       'nao implementado ate a fonte existir.',
       'Indice de Cobertura Vegetal Nativa - stub', TRUNC(SYSDATE)
FROM ${goldSchema}.tipo_indicador WHERE codigo = 'ICV';

INSERT INTO ${goldSchema}.regra_calculo (tipo_indicador_id, versao, expressao, descricao, vigente_de)
SELECT id, 1,
       '[SEM FONTE] Area de Reserva Legal / area do imovel, comparada ao minimo do ' ||
       'bioma (silver.bioma, ja disponivel). Falta o dado declarado de RL do imovel ' ||
       '(CAR, API SICAR Temas) - nao implementado ate a fonte existir.',
       'Indice de Reserva Legal - stub', TRUNC(SYSDATE)
FROM ${goldSchema}.tipo_indicador WHERE codigo = 'IRL';

INSERT INTO ${goldSchema}.regra_calculo (tipo_indicador_id, versao, expressao, descricao, vigente_de)
SELECT id, 1,
       '[SEM FONTE] Area de APP com vegetacao nativa / APP total do imovel. Depende ' ||
       'de APP declarada do CAR (API SICAR Temas) - nao implementado ate a fonte existir.',
       'Indice de Area de Preservacao Permanente - stub', TRUNC(SYSDATE)
FROM ${goldSchema}.tipo_indicador WHERE codigo = 'IAPP';
