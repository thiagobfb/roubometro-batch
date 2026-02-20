# roubometro-batch
Rotina batch usada para preenchimento e atualização de registros criminais do RJ da fonte original para o site.

---

## Fluxo da Rotina Batch

O organograma abaixo descreve o fluxo base da rotina batch do sistema Roubômetro.

```mermaid
flowchart TD
    A([Início]) --> B[Acessar o portal\ndados.gov.br/dados/conjuntos-dados/\nisp-estatisticas-de-seguranca-publica]
    B --> C[Localizar o recurso:\nEstatísticas de segurança — série histórica\nanual por município desde 2014\ntaxas por 100 mil habitantes]
    C --> D[Ler a data de atualização\ndo arquivo no portal]
    D --> E{O arquivo já existe\nno sistema?}

    E -- Não --> F[Baixar o arquivo\nno botão 'Acessar o Recurso']
    E -- Sim --> G{A data do arquivo\nno portal é mais\nrecente que a do\narquivo armazenado?}

    G -- Não --> H[Manter o arquivo\nexistente]
    G -- Sim --> I[Baixar e substituir\no arquivo armazenado]

    F --> J[Ler o arquivo linha a linha]
    I --> J
    H --> J

    J --> K{Existem mais\nlinhas a processar?}
    K -- Não --> M([Fim da rotina])
    K -- Sim --> L{O registro já\nexiste no banco\nde dados?}

    L -- Sim --> K
    L -- Não --> N[Tratar os dados\nda linha]
    N --> O[Inserir o registro\nno banco de dados]
    O --> K
```
