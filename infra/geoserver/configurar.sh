#!/bin/sh
# Configura o GeoServer do mapa de cobertura (LIZARDS-35) pela API REST - nada e
# configurado na mao pelo painel. Roda sozinho no "docker compose up" (servico
# geoserver-config) e pode rodar de novo quantas vezes quiser: o que ja existe e
# atualizado, nao duplicado.
#
#   workspace "datagis"
#   datastore "gold"   -> Oracle, schema GOLD, usuario do backend (APP_USER)
#   7 camadas          -> as views gold.vw_mapa_* (V079), cada uma com o estilo
#                         estilos/<camada>.sld
#
# Camada nova no mapa = view nova na Gold + nome na lista CAMADAS + um .sld.
#
# Variaveis (vem do .env pelo compose): GEOSERVER_URL, GEOSERVER_ADMIN_PASSWORD,
# APP_USER, APP_USER_PASSWORD.
set -eu

GS="${GEOSERVER_URL:-http://geoserver:8080/geoserver}"
AUTH="admin:${GEOSERVER_ADMIN_PASSWORD:?defina GEOSERVER_ADMIN_PASSWORD no .env}"
: "${APP_USER:?defina APP_USER no .env}"
: "${APP_USER_PASSWORD:?defina APP_USER_PASSWORD no .env}"
DIR="$(dirname "$0")"
WS=datagis
DS=gold
CAMADAS="vw_mapa_limite_pr vw_mapa_municipio vw_mapa_imovel vw_mapa_area_protegida vw_mapa_embargo vw_mapa_desmatamento vw_mapa_foco_calor"
# Estilos que nao sao o padrao de nenhuma camada: a tela pede pelo parametro STYLES.
ESTILOS_EXTRAS="vw_mapa_municipio_com_dados vw_mapa_municipio_com_dados_contorno"

# Codigo HTTP de uma chamada REST (corpo descartado).
http() {
    curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" "$@"
}

# Chamada que precisa dar certo: mostra a resposta e para o script se nao for 2xx.
exigir() {
    descricao="$1"; shift
    resposta=$(curl -s -w '\n%{http_code}' -u "$AUTH" "$@")
    codigo=$(printf '%s' "$resposta" | tail -n 1)
    case "$codigo" in
        2??) echo "  ok   $descricao" ;;
        *) echo "  ERRO $descricao (HTTP $codigo)"; printf '%s\n' "$resposta" | sed '$d'; exit 1 ;;
    esac
}

xml_escape() {
    printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'
}

echo "[geoserver-config] aguardando o GeoServer em $GS (a 1a subida baixa as extensoes, leva uns minutos)..."
tentativas=0
until [ "$(http "$GS/rest/about/version.json")" = "200" ]; do
    tentativas=$((tentativas + 1))
    if [ "$tentativas" -gt 120 ]; then
        echo "[geoserver-config] GeoServer nao respondeu em 10 minutos."; exit 1
    fi
    sleep 5
done

echo "[geoserver-config] workspace"
if [ "$(http "$GS/rest/workspaces/$WS")" != "200" ]; then
    exigir "workspace $WS" -X POST -H 'Content-Type: text/xml' "$GS/rest/workspaces" \
        -d "<workspace><name>$WS</name></workspace>"
else
    echo "  ok   workspace $WS (ja existia)"
fi

echo "[geoserver-config] datastore"
# Sempre reenviado: se a senha do Oracle mudar no .env, o GeoServer acompanha.
DATASTORE="<dataStore><name>$DS</name><type>Oracle NG</type><enabled>true</enabled><connectionParameters>
<entry key=\"dbtype\">oracle</entry>
<entry key=\"host\">oracle</entry>
<entry key=\"port\">1521</entry>
<entry key=\"database\">/FREEPDB1</entry>
<entry key=\"schema\">GOLD</entry>
<entry key=\"user\">$(xml_escape "$APP_USER")</entry>
<entry key=\"passwd\">$(xml_escape "$APP_USER_PASSWORD")</entry>
<entry key=\"Loose bbox\">true</entry>
<entry key=\"Expose primary keys\">false</entry>
<entry key=\"namespace\">http://$WS</entry>
</connectionParameters></dataStore>"
if [ "$(http "$GS/rest/workspaces/$WS/datastores/$DS")" = "200" ]; then
    exigir "datastore $DS (atualizado)" -X PUT -H 'Content-Type: text/xml' \
        "$GS/rest/workspaces/$WS/datastores/$DS" -d "$DATASTORE"
else
    exigir "datastore $DS" -X POST -H 'Content-Type: text/xml' \
        "$GS/rest/workspaces/$WS/datastores" -d "$DATASTORE"
fi

# Cria ou atualiza o estilo estilos/<nome>.sld no workspace.
publicar_estilo() {
    sld="$DIR/estilos/$1.sld"
    # Consulta com .xml: sem extensao o GeoServer 3.0.2 responde 500 pra estilo que existe.
    if [ "$(http "$GS/rest/workspaces/$WS/styles/$1.xml")" != "200" ]; then
        exigir "estilo $1" -X POST -H 'Content-Type: application/vnd.ogc.sld+xml' \
            "$GS/rest/workspaces/$WS/styles?name=$1" --data-binary "@$sld"
    else
        # Reenviado sempre: editar o .sld no repositorio e rodar de novo ja aplica.
        exigir "estilo $1 (atualizado)" -X PUT -H 'Content-Type: application/vnd.ogc.sld+xml' \
            "$GS/rest/workspaces/$WS/styles/$1" --data-binary "@$sld"
    fi
}

echo "[geoserver-config] camadas e estilos"
for camada in $CAMADAS; do
    nativo=$(printf '%s' "$camada" | tr '[:lower:]' '[:upper:]')
    # Bbox fixa no Parana: o GeoServer nao precisa varrer a view pra calcular (e
    # camada vazia, como hoje embargo/desmatamento, nao teria bbox nenhuma).
    if [ "$(http "$GS/rest/workspaces/$WS/datastores/$DS/featuretypes/$camada")" != "200" ]; then
        exigir "camada $camada" -X POST -H 'Content-Type: text/xml' \
            "$GS/rest/workspaces/$WS/datastores/$DS/featuretypes" -d "<featureType>
<name>$camada</name><nativeName>$nativo</nativeName><title>$camada</title>
<srs>EPSG:4674</srs><nativeCRS>EPSG:4674</nativeCRS><projectionPolicy>FORCE_DECLARED</projectionPolicy>
<nativeBoundingBox><minx>-55</minx><maxx>-47.5</maxx><miny>-27.2</miny><maxy>-22</maxy><crs>EPSG:4674</crs></nativeBoundingBox>
<latLonBoundingBox><minx>-55</minx><maxx>-47.5</maxx><miny>-27.2</miny><maxy>-22</maxy><crs>EPSG:4326</crs></latLonBoundingBox>
</featureType>"
    else
        echo "  ok   camada $camada (ja existia)"
    fi

    publicar_estilo "$camada"
    exigir "estilo padrao de $camada" -X PUT -H 'Content-Type: text/xml' \
        "$GS/rest/layers/$WS:$camada" \
        -d "<layer><defaultStyle><name>$camada</name><workspace>$WS</workspace></defaultStyle></layer>"
done

for estilo in $ESTILOS_EXTRAS; do
    publicar_estilo "$estilo"
done

echo "[geoserver-config] conferencia (feicoes por camada, via WFS):"
for camada in $CAMADAS; do
    total=$(curl -s -u "$AUTH" "$GS/$WS/ows?service=WFS&version=2.0.0&request=GetFeature&typeNames=$WS:$camada&resultType=hits" \
        | sed -n 's/.*numberMatched="\([0-9]*\)".*/\1/p')
    echo "  $camada: ${total:-ERRO}"
done
echo "[geoserver-config] pronto."
