# roubometro-batch
Rotina batch usada para preenchimento e atualização de registros criminais do RJ da fonte original para o site.

---

## Fluxo da Rotina Batch

O organograma abaixo descreve o fluxo base da rotina batch do sistema Roubômetro.

```mermaid
flowchart TD
    A([Início]) --> B[Acessar o portal dados.gov.br/dados/conjuntos-dados/isp-estatisticas-de-seguranca-publica]
    B --> C[Localizar o recurso: Estatísticas de segurança — série histórica anual por município desde 2014 - taxas por 100 mil habitantes]
    C --> D[Ler a data de atualização do arquivo no portal]
    D --> E{O arquivo já existe no sistema?}
    E -- Não --> F[Baixar o arquivo no botão Acessar o Recurso]
    E -- Sim --> G{A data do arquivo no portal é mais recente que a do arquivo armazenado?}
    G -- Não --> H[Manter o arquivo existente]
    G -- Sim --> I[Baixar e substituir o arquivo armazenado]
    F --> J[Ler o arquivo linha a linha]
    I --> J
    H --> J
    J --> K{Existem mais linhas a processar?}
    K -- Não --> M([Fim da rotina])
    K -- Sim --> L{O registro já existe no banco de dados?}
    L -- Sim --> K
    L -- Não --> N[Tratar os dados da linha]
    N --> O[Inserir o registro no banco de dados]
    O --> K
```
