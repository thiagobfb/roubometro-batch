package br.com.roubometro.domain.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO representing one row of the ISP-RJ BaseMunicipioMensal.csv (62 columns).
 * All fields are String — conversion and validation happen in the ItemProcessor.
 */
@Getter
@Setter
@NoArgsConstructor
public class CsvEstatisticaRow {

    private String fmunCod;
    private String fmun;
    private String ano;
    private String mes;
    private String mesAno;
    private String regiao;
    private String homDoloso;
    private String lesaoCorpMorte;
    private String latrocinio;
    private String cvli;
    private String homPorIntervPolicial;
    private String feminicidio;
    private String letalidadeViolenta;
    private String tentatHom;
    private String tentativaFeminicidio;
    private String lesaoCorpDolosa;
    private String estupro;
    private String homCulposo;
    private String lesaoCorpCulposa;
    private String rouboTranseunte;
    private String rouboCelular;
    private String rouboEmColetivo;
    private String rouboRua;
    private String rouboVeiculo;
    private String rouboCarga;
    private String rouboComercio;
    private String rouboResidencia;
    private String rouboBanco;
    private String rouboCxEletronico;
    private String rouboConducaoSaque;
    private String rouboAposSaque;
    private String rouboBicicleta;
    private String outrosRoubos;
    private String totalRoubos;
    private String furtoVeiculos;
    private String furtoTranseunte;
    private String furtoColetivo;
    private String furtoCelular;
    private String furtoBicicleta;
    private String outrosFurtos;
    private String totalFurtos;
    private String sequestro;
    private String extorsao;
    private String sequestroRelampago;
    private String estelionato;
    private String apreensaoDrogas;
    private String posseDrogas;
    private String traficoDrogas;
    private String apreensaoDrogasSemAutor;
    private String recuperacaoVeiculos;
    private String apf;
    private String aaapai;
    private String cmp;
    private String cmba;
    private String ameaca;
    private String pessoasDesaparecidas;
    private String encontroCadaver;
    private String encontroOssada;
    private String polMilitaresMortosServ;
    private String polCivisMortosServ;
    private String registroOcorrencias;
    private String fase;
}
