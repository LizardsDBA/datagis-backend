<?xml version="1.0" encoding="UTF-8"?>
<!-- Municipios com imoveis publicados - PREENCHIMENTO (estilo extra da camada
     vw_mapa_municipio, nao e o padrao dela). A tela pede este estilo filtrando so os
     municipios que tem dado, para o usuario ver onde da para clicar, e desenha ABAIXO dos
     imoveis. O contorno vai num estilo separado (vw_mapa_municipio_com_dados_contorno),
     desenhado ACIMA dos imoveis: onde os imoveis cobrem quase o municipio todo (Maringa,
     Ponta Grossa), so o preenchimento sumia. Violeta: nenhuma camada de indicador usa. -->
<StyledLayerDescriptor version="1.0.0" xmlns="http://www.opengis.net/sld" xmlns:ogc="http://www.opengis.net/ogc">
  <NamedLayer>
    <Name>vw_mapa_municipio_com_dados</Name>
    <UserStyle>
      <FeatureTypeStyle>
        <Rule>
          <PolygonSymbolizer>
            <Fill>
              <CssParameter name="fill">#7c3aed</CssParameter>
              <CssParameter name="fill-opacity">0.14</CssParameter>
            </Fill>
          </PolygonSymbolizer>
        </Rule>
      </FeatureTypeStyle>
    </UserStyle>
  </NamedLayer>
</StyledLayerDescriptor>
