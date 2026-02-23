package br.com.roubometro.domain.model;

/**
 * DTO representing one row of the ISP-RJ BaseMunicipioMensal.csv (62 columns).
 * All fields are String — conversion and validation happen in the ItemProcessor.
 */
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
    private String letalidadeViolenta;
    private String tentatHom;
    private String lesaoCorpDolosa;
    private String estupro;
    private String homCulposo;
    private String lesaoCorpCulposa;
    private String rouboTranseunte;
    private String rouboCelular;
    private String rouboEmColetivo;
    private String rouboRua;
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

    // --- Getters and Setters ---

    public String getFmunCod() { return fmunCod; }
    public void setFmunCod(String fmunCod) { this.fmunCod = fmunCod; }

    public String getFmun() { return fmun; }
    public void setFmun(String fmun) { this.fmun = fmun; }

    public String getAno() { return ano; }
    public void setAno(String ano) { this.ano = ano; }

    public String getMes() { return mes; }
    public void setMes(String mes) { this.mes = mes; }

    public String getMesAno() { return mesAno; }
    public void setMesAno(String mesAno) { this.mesAno = mesAno; }

    public String getRegiao() { return regiao; }
    public void setRegiao(String regiao) { this.regiao = regiao; }

    public String getHomDoloso() { return homDoloso; }
    public void setHomDoloso(String homDoloso) { this.homDoloso = homDoloso; }

    public String getLesaoCorpMorte() { return lesaoCorpMorte; }
    public void setLesaoCorpMorte(String lesaoCorpMorte) { this.lesaoCorpMorte = lesaoCorpMorte; }

    public String getLatrocinio() { return latrocinio; }
    public void setLatrocinio(String latrocinio) { this.latrocinio = latrocinio; }

    public String getCvli() { return cvli; }
    public void setCvli(String cvli) { this.cvli = cvli; }

    public String getHomPorIntervPolicial() { return homPorIntervPolicial; }
    public void setHomPorIntervPolicial(String homPorIntervPolicial) { this.homPorIntervPolicial = homPorIntervPolicial; }

    public String getLetalidadeViolenta() { return letalidadeViolenta; }
    public void setLetalidadeViolenta(String letalidadeViolenta) { this.letalidadeViolenta = letalidadeViolenta; }

    public String getTentatHom() { return tentatHom; }
    public void setTentatHom(String tentatHom) { this.tentatHom = tentatHom; }

    public String getLesaoCorpDolosa() { return lesaoCorpDolosa; }
    public void setLesaoCorpDolosa(String lesaoCorpDolosa) { this.lesaoCorpDolosa = lesaoCorpDolosa; }

    public String getEstupro() { return estupro; }
    public void setEstupro(String estupro) { this.estupro = estupro; }

    public String getHomCulposo() { return homCulposo; }
    public void setHomCulposo(String homCulposo) { this.homCulposo = homCulposo; }

    public String getLesaoCorpCulposa() { return lesaoCorpCulposa; }
    public void setLesaoCorpCulposa(String lesaoCorpCulposa) { this.lesaoCorpCulposa = lesaoCorpCulposa; }

    public String getRouboTranseunte() { return rouboTranseunte; }
    public void setRouboTranseunte(String rouboTranseunte) { this.rouboTranseunte = rouboTranseunte; }

    public String getRouboCelular() { return rouboCelular; }
    public void setRouboCelular(String rouboCelular) { this.rouboCelular = rouboCelular; }

    public String getRouboEmColetivo() { return rouboEmColetivo; }
    public void setRouboEmColetivo(String rouboEmColetivo) { this.rouboEmColetivo = rouboEmColetivo; }

    public String getRouboRua() { return rouboRua; }
    public void setRouboRua(String rouboRua) { this.rouboRua = rouboRua; }

    public String getRouboCarga() { return rouboCarga; }
    public void setRouboCarga(String rouboCarga) { this.rouboCarga = rouboCarga; }

    public String getRouboComercio() { return rouboComercio; }
    public void setRouboComercio(String rouboComercio) { this.rouboComercio = rouboComercio; }

    public String getRouboResidencia() { return rouboResidencia; }
    public void setRouboResidencia(String rouboResidencia) { this.rouboResidencia = rouboResidencia; }

    public String getRouboBanco() { return rouboBanco; }
    public void setRouboBanco(String rouboBanco) { this.rouboBanco = rouboBanco; }

    public String getRouboCxEletronico() { return rouboCxEletronico; }
    public void setRouboCxEletronico(String rouboCxEletronico) { this.rouboCxEletronico = rouboCxEletronico; }

    public String getRouboConducaoSaque() { return rouboConducaoSaque; }
    public void setRouboConducaoSaque(String rouboConducaoSaque) { this.rouboConducaoSaque = rouboConducaoSaque; }

    public String getRouboAposSaque() { return rouboAposSaque; }
    public void setRouboAposSaque(String rouboAposSaque) { this.rouboAposSaque = rouboAposSaque; }

    public String getRouboBicicleta() { return rouboBicicleta; }
    public void setRouboBicicleta(String rouboBicicleta) { this.rouboBicicleta = rouboBicicleta; }

    public String getOutrosRoubos() { return outrosRoubos; }
    public void setOutrosRoubos(String outrosRoubos) { this.outrosRoubos = outrosRoubos; }

    public String getTotalRoubos() { return totalRoubos; }
    public void setTotalRoubos(String totalRoubos) { this.totalRoubos = totalRoubos; }

    public String getFurtoVeiculos() { return furtoVeiculos; }
    public void setFurtoVeiculos(String furtoVeiculos) { this.furtoVeiculos = furtoVeiculos; }

    public String getFurtoTranseunte() { return furtoTranseunte; }
    public void setFurtoTranseunte(String furtoTranseunte) { this.furtoTranseunte = furtoTranseunte; }

    public String getFurtoColetivo() { return furtoColetivo; }
    public void setFurtoColetivo(String furtoColetivo) { this.furtoColetivo = furtoColetivo; }

    public String getFurtoCelular() { return furtoCelular; }
    public void setFurtoCelular(String furtoCelular) { this.furtoCelular = furtoCelular; }

    public String getFurtoBicicleta() { return furtoBicicleta; }
    public void setFurtoBicicleta(String furtoBicicleta) { this.furtoBicicleta = furtoBicicleta; }

    public String getOutrosFurtos() { return outrosFurtos; }
    public void setOutrosFurtos(String outrosFurtos) { this.outrosFurtos = outrosFurtos; }

    public String getTotalFurtos() { return totalFurtos; }
    public void setTotalFurtos(String totalFurtos) { this.totalFurtos = totalFurtos; }

    public String getSequestro() { return sequestro; }
    public void setSequestro(String sequestro) { this.sequestro = sequestro; }

    public String getExtorsao() { return extorsao; }
    public void setExtorsao(String extorsao) { this.extorsao = extorsao; }

    public String getSequestroRelampago() { return sequestroRelampago; }
    public void setSequestroRelampago(String sequestroRelampago) { this.sequestroRelampago = sequestroRelampago; }

    public String getEstelionato() { return estelionato; }
    public void setEstelionato(String estelionato) { this.estelionato = estelionato; }

    public String getApreensaoDrogas() { return apreensaoDrogas; }
    public void setApreensaoDrogas(String apreensaoDrogas) { this.apreensaoDrogas = apreensaoDrogas; }

    public String getPosseDrogas() { return posseDrogas; }
    public void setPosseDrogas(String posseDrogas) { this.posseDrogas = posseDrogas; }

    public String getTraficoDrogas() { return traficoDrogas; }
    public void setTraficoDrogas(String traficoDrogas) { this.traficoDrogas = traficoDrogas; }

    public String getApreensaoDrogasSemAutor() { return apreensaoDrogasSemAutor; }
    public void setApreensaoDrogasSemAutor(String apreensaoDrogasSemAutor) { this.apreensaoDrogasSemAutor = apreensaoDrogasSemAutor; }

    public String getRecuperacaoVeiculos() { return recuperacaoVeiculos; }
    public void setRecuperacaoVeiculos(String recuperacaoVeiculos) { this.recuperacaoVeiculos = recuperacaoVeiculos; }

    public String getApf() { return apf; }
    public void setApf(String apf) { this.apf = apf; }

    public String getAaapai() { return aaapai; }
    public void setAaapai(String aaapai) { this.aaapai = aaapai; }

    public String getCmp() { return cmp; }
    public void setCmp(String cmp) { this.cmp = cmp; }

    public String getCmba() { return cmba; }
    public void setCmba(String cmba) { this.cmba = cmba; }

    public String getAmeaca() { return ameaca; }
    public void setAmeaca(String ameaca) { this.ameaca = ameaca; }

    public String getPessoasDesaparecidas() { return pessoasDesaparecidas; }
    public void setPessoasDesaparecidas(String pessoasDesaparecidas) { this.pessoasDesaparecidas = pessoasDesaparecidas; }

    public String getEncontroCadaver() { return encontroCadaver; }
    public void setEncontroCadaver(String encontroCadaver) { this.encontroCadaver = encontroCadaver; }

    public String getEncontroOssada() { return encontroOssada; }
    public void setEncontroOssada(String encontroOssada) { this.encontroOssada = encontroOssada; }

    public String getPolMilitaresMortosServ() { return polMilitaresMortosServ; }
    public void setPolMilitaresMortosServ(String polMilitaresMortosServ) { this.polMilitaresMortosServ = polMilitaresMortosServ; }

    public String getPolCivisMortosServ() { return polCivisMortosServ; }
    public void setPolCivisMortosServ(String polCivisMortosServ) { this.polCivisMortosServ = polCivisMortosServ; }

    public String getRegistroOcorrencias() { return registroOcorrencias; }
    public void setRegistroOcorrencias(String registroOcorrencias) { this.registroOcorrencias = registroOcorrencias; }

    public String getFase() { return fase; }
    public void setFase(String fase) { this.fase = fase; }
}
