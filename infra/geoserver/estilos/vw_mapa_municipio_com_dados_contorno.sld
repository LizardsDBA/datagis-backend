<?xml version="1.0" encoding="UTF-8"?>
<!-- Municipios com imoveis publicados - CONTORNO (estilo extra, par do
     vw_mapa_municipio_com_dados). A tela desenha este acima dos imoveis e abaixo das
     camadas de alerta, para a divisa do municipio aparecer mesmo onde os imoveis cobrem
     o municipio quase inteiro. -->
<StyledLayerDescriptor version="1.0.0" xmlns="http://www.opengis.net/sld" xmlns:ogc="http://www.opengis.net/ogc">
  <NamedLayer>
    <Name>vw_mapa_municipio_com_dados_contorno</Name>
    <UserStyle>
      <FeatureTypeStyle>
        <Rule>
          <LineSymbolizer>
            <Stroke>
              <CssParameter name="stroke">#7c3aed</CssParameter>
              <CssParameter name="stroke-width">2</CssParameter>
              <CssParameter name="stroke-opacity">0.95</CssParameter>
            </Stroke>
          </LineSymbolizer>
        </Rule>
      </FeatureTypeStyle>
    </UserStyle>
  </NamedLayer>
</StyledLayerDescriptor>
