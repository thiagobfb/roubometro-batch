package br.com.roubometro.application.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static mapping from CSV column getter names to human-readable category names.
 * Based on Appendix B of ARCHITECTURE.md.
 */
public final class CategoryColumnMapping {

    private CategoryColumnMapping() {}

    private static final Map<String, String> COLUMN_TO_CATEGORY;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("homDoloso", "Homicidio doloso");
        map.put("lesaoCorpMorte", "Lesao corporal seguida de morte");
        map.put("latrocinio", "Latrocinio");
        map.put("cvli", "CVLI");
        map.put("homPorIntervPolicial", "Homicidio por intervencao policial");
        map.put("letalidadeViolenta", "Letalidade violenta");
        map.put("tentatHom", "Tentativa de homicidio");
        map.put("lesaoCorpDolosa", "Lesao corporal dolosa");
        map.put("estupro", "Estupro");
        map.put("homCulposo", "Homicidio culposo");
        map.put("lesaoCorpCulposa", "Lesao corporal culposa");
        map.put("rouboTranseunte", "Roubo a transeunte");
        map.put("rouboCelular", "Roubo de celular");
        map.put("rouboEmColetivo", "Roubo em coletivo");
        map.put("rouboRua", "Roubo de rua");
        map.put("rouboCarga", "Roubo de carga");
        map.put("rouboComercio", "Roubo a comercio");
        map.put("rouboResidencia", "Roubo a residencia");
        map.put("rouboBanco", "Roubo a banco");
        map.put("rouboCxEletronico", "Roubo a caixa eletronico");
        map.put("rouboConducaoSaque", "Roubo com conducao para saque");
        map.put("rouboAposSaque", "Roubo apos saque");
        map.put("rouboBicicleta", "Roubo de bicicleta");
        map.put("outrosRoubos", "Outros roubos");
        map.put("totalRoubos", "Total de roubos");
        map.put("furtoVeiculos", "Furto de veiculos");
        map.put("furtoTranseunte", "Furto a transeunte");
        map.put("furtoColetivo", "Furto em coletivo");
        map.put("furtoCelular", "Furto de celular");
        map.put("furtoBicicleta", "Furto de bicicleta");
        map.put("outrosFurtos", "Outros furtos");
        map.put("totalFurtos", "Total de furtos");
        map.put("sequestro", "Sequestro");
        map.put("extorsao", "Extorsao");
        map.put("sequestroRelampago", "Sequestro relampago");
        map.put("estelionato", "Estelionato");
        map.put("apreensaoDrogas", "Apreensao de drogas");
        map.put("posseDrogas", "Posse de drogas");
        map.put("traficoDrogas", "Trafico de drogas");
        map.put("apreensaoDrogasSemAutor", "Apreensao de drogas sem autor");
        map.put("recuperacaoVeiculos", "Recuperacao de veiculos");
        map.put("apf", "Auto de prisao em flagrante");
        map.put("aaapai", "Autos de apreensao de adolescentes");
        map.put("cmp", "Cumprimento de mandado de prisao");
        map.put("cmba", "Cumprimento de mandado de busca e apreensao");
        map.put("ameaca", "Ameaca");
        map.put("pessoasDesaparecidas", "Pessoas desaparecidas");
        map.put("encontroCadaver", "Encontro de cadaver");
        map.put("encontroOssada", "Encontro de ossada");
        map.put("polMilitaresMortosServ", "Policiais militares mortos em servico");
        map.put("polCivisMortosServ", "Policiais civis mortos em servico");
        map.put("registroOcorrencias", "Total de registros de ocorrencia");
        COLUMN_TO_CATEGORY = Collections.unmodifiableMap(map);
    }

    public static Map<String, String> getColumnToCategory() {
        return COLUMN_TO_CATEGORY;
    }

    public static List<String> getCrimeColumns() {
        return List.copyOf(COLUMN_TO_CATEGORY.keySet());
    }

    public static String getCategoryName(String columnName) {
        return COLUMN_TO_CATEGORY.get(columnName);
    }
}
