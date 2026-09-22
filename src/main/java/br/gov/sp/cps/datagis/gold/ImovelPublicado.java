package br.gov.sp.cps.datagis.gold;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "IMOVEL_PUBLICADO", schema = "GOLD")
public class ImovelPublicado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "COD_IMOVEL")
    private String codImovel;

    @Column(name = "INDICADOR_VERSAO_ID")
    private Long indicadorVersaoId;

    @Column(name = "MUNICIPIO_COD_IBGE")
    private Long municipioCodIbge;

    @Column(name = "AREA_HA")
    private Double areaHa;

    @Column(name = "VALOR_ICV")
    private Double valorIcv;

    @Column(name = "VALOR_IRL")
    private Double valorIrl;

    @Column(name = "VALOR_IAPP")
    private Double valorIapp;

    @Column(name = "VALOR_ISAP")
    private Double valorIsap;

    @Column(name = "VALOR_IAE")
    private Double valorIae;

    @Column(name = "VALOR_IDESMAT")
    private Double valorIdesmat;

    @Column(name = "VALOR_IFC")
    private Double valorIfc;

    @Column(name = "DATA_PUBLICACAO")
    private LocalDateTime dataPublicacao;
}