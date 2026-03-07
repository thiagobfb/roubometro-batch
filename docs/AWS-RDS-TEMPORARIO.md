# Banco de Dados Temporario na AWS (RDS MySQL)

> **Objetivo**: criar um MySQL na AWS para testar o roubometro-batch sem depender do Docker local ou do banco de producao.
>
> **Custo**: $0 no Free Tier (12 meses). Fora do Free Tier: ~$17/mes.
>
> **Importante**: lembre-se de destruir o banco quando nao estiver usando para nao gerar custo.

---

## Visao Geral

```
┌──────────────────┐          ┌──────────────────────────────┐
│  Sua maquina     │          │  AWS (us-east-1)             │
│  (IntelliJ/WSL)  │──3306──→│  RDS MySQL db.t3.micro       │
│                  │          │  roubometro-db               │
│  roubometro-batch│          │  Database: roubometro        │
└──────────────────┘          └──────────────────────────────┘
       seu IP                    Security Group libera seu IP
```

---

## Pre-requisitos

- [ ] AWS CLI instalada e configurada (`aws configure`)
- [ ] Cliente MySQL instalado (`sudo apt install mysql-client` no WSL)
- [ ] Projeto roubometro-batch compilando localmente

---

## Criar o Banco

### 1. Criar o Security Group

O Security Group funciona como um firewall. Vamos liberar a porta 3306 apenas para o seu IP.

```bash
# Descobrir o VPC ID default
VPC_ID=$(aws ec2 describe-vpcs --filters "Name=isDefault,Values=true" \
  --query "Vpcs[0].VpcId" --output text)
echo "VPC: $VPC_ID"

# Criar o Security Group
SG_ID=$(aws ec2 create-security-group \
  --group-name roubometro-rds-sg \
  --description "Acesso MySQL para roubometro-batch" \
  --vpc-id $VPC_ID \
  --query "GroupId" --output text)
echo "Security Group: $SG_ID"

# Descobrir seu IP publico atual
MEU_IP=$(curl -s https://checkip.amazonaws.com)
echo "Seu IP: $MEU_IP"

# Liberar porta 3306 apenas para seu IP
aws ec2 authorize-security-group-ingress \
  --group-id $SG_ID \
  --protocol tcp \
  --port 3306 \
  --cidr "$MEU_IP/32"
```

> Se o seu IP mudar (ex: reiniciou o roteador), atualize a regra:
> ```bash
> # Remover regra antiga
> aws ec2 revoke-security-group-ingress \
>   --group-id $SG_ID --protocol tcp --port 3306 --cidr "IP_ANTIGO/32"
>
> # Adicionar regra com novo IP
> MEU_IP=$(curl -s https://checkip.amazonaws.com)
> aws ec2 authorize-security-group-ingress \
>   --group-id $SG_ID --protocol tcp --port 3306 --cidr "$MEU_IP/32"
> ```

### 2. Criar a instancia RDS

```bash
aws rds create-db-instance \
  --db-instance-identifier roubometro-db \
  --db-instance-class db.t3.micro \
  --engine mysql \
  --engine-version 8.0 \
  --master-username admin \
  --master-user-password SuaSenhaSegura123! \
  --allocated-storage 20 \
  --db-name roubometro \
  --vpc-security-group-ids $SG_ID \
  --publicly-accessible \
  --backup-retention-period 0 \
  --no-multi-az \
  --storage-type gp2
```

**Explicacao dos parametros**:

| Parametro | Valor | Por que |
|-----------|-------|---------|
| `--db-instance-class` | `db.t3.micro` | Menor instancia, elegivel ao Free Tier |
| `--allocated-storage` | `20` | 20 GB (Free Tier cobre ate 20 GB) |
| `--publicly-accessible` | - | Permite conexao do seu computador |
| `--backup-retention-period 0` | - | Desabilita backups (economia, nao usar em prod) |
| `--no-multi-az` | - | Sem redundancia (economia, nao usar em prod) |
| `--storage-type gp2` | - | SSD padrao, coberto pelo Free Tier |

### 3. Aguardar ficar disponivel

Demora **5-10 minutos**. O comando abaixo espera automaticamente:

```bash
echo "Aguardando RDS ficar disponivel (5-10 min)..."
aws rds wait db-instance-available --db-instance-identifier roubometro-db
echo "RDS pronto!"
```

### 4. Obter o endpoint de conexao

```bash
ENDPOINT=$(aws rds describe-db-instances \
  --db-instance-identifier roubometro-db \
  --query "DBInstances[0].Endpoint.Address" \
  --output text)
echo "Endpoint: $ENDPOINT"
```

Resultado exemplo: `roubometro-db.abc123xyz.us-east-1.rds.amazonaws.com`

**Anote este valor** — voce vai usar em todos os comandos de conexao.

### 5. Testar a conexao

```bash
mysql -h $ENDPOINT -u admin -p'SuaSenhaSegura123!' roubometro -e "SELECT 1;"
```

Se aparecer `1`, a conexao esta funcionando.

### 6. Criar o schema e dados seed

Esses scripts criam as mesmas tabelas e dados que o Docker local usa:

```bash
# Tabelas da API (regions, states, municipalities, categories, monthly_stats, etc.)
mysql -h $ENDPOINT -u admin -p'SuaSenhaSegura123!' roubometro \
  < docker/init-db/01_api_schema.sql

# Dados seed (1 regiao, 1 estado, 10 municipios, 12 categorias)
mysql -h $ENDPOINT -u admin -p'SuaSenhaSegura123!' roubometro \
  < docker/init-db/02_seed_data.sql

# Verificar
mysql -h $ENDPOINT -u admin -p'SuaSenhaSegura123!' roubometro -e "SHOW TABLES;"
```

Resultado esperado: 8 tabelas (regions, states, municipalities, users, categories, monthly_stats, user_reports, refresh_tokens).

> As tabelas do batch (`batch_file_metadata`, `batch_job_execution_report`, `BATCH_*`) serao criadas automaticamente pelo Flyway e Spring Batch na primeira execucao.

---

## Executar o Batch

### Via Maven (terminal)

```bash
mvn spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="\
--spring.datasource.url=jdbc:mysql://$ENDPOINT:3306/roubometro?useSSL=true&requireSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo \
--spring.datasource.username=admin \
--spring.datasource.password=SuaSenhaSegura123!"
```

### Via IntelliJ

Em **Run > Edit Configurations**, na configuracao do Spring Boot:

- **Active profiles**: `local`
- **VM options**:
```
-Dspring.datasource.url=jdbc:mysql://roubometro-db.abc123xyz.us-east-1.rds.amazonaws.com:3306/roubometro?useSSL=true&requireSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo
-Dspring.datasource.username=admin
-Dspring.datasource.password=SuaSenhaSegura123!
```

> Substitua `roubometro-db.abc123xyz.us-east-1.rds.amazonaws.com` pelo seu endpoint real.

### Verificar os dados

```bash
mysql -h $ENDPOINT -u admin -p'SuaSenhaSegura123!' roubometro -e "
  SELECT COUNT(*) AS total FROM monthly_stats;
"
```

---

## Pausar o Banco (economia sem perder dados)

Se voce nao vai usar o banco por alguns dias mas **nao quer perder os dados**, pode parar a instancia. Enquanto parada, voce paga apenas pelo storage (~$2.30/mes), nao pela instancia.

### Parar

```bash
aws rds stop-db-instance --db-instance-identifier roubometro-db
echo "RDS parando... (leva ~2 min)"
```

### Iniciar novamente

```bash
aws rds start-db-instance --db-instance-identifier roubometro-db

echo "Aguardando RDS ficar disponivel..."
aws rds wait db-instance-available --db-instance-identifier roubometro-db
echo "RDS pronto!"
```

> **Atencao**: a AWS reinicia automaticamente instancias paradas apos **7 dias** (para aplicar patches de seguranca). Se voce precisa manter parada por mais tempo, configure um Lambda para re-parar automaticamente, ou simplesmente destrua e recrie quando precisar.

---

## Destruir o Banco (parar cobranca)

Quando nao precisar mais do banco, **destrua para nao gerar custo**:

### 1. Deletar a instancia RDS

```bash
aws rds delete-db-instance \
  --db-instance-identifier roubometro-db \
  --skip-final-snapshot
```

> `--skip-final-snapshot`: pula a criacao de snapshot final (que tambem custa). Use apenas para bancos de teste.

Aguardar a exclusao (~3-5 minutos):

```bash
echo "Aguardando exclusao do RDS..."
aws rds wait db-instance-deleted --db-instance-identifier roubometro-db
echo "RDS excluido!"
```

### 2. Deletar o Security Group

O Security Group so pode ser deletado apos a instancia RDS ser completamente removida:

```bash
aws ec2 delete-security-group --group-id $SG_ID
echo "Security Group excluido!"
```

> Se voce nao tem mais a variavel `$SG_ID`, descubra o ID:
> ```bash
> aws ec2 describe-security-groups \
>   --filters "Name=group-name,Values=roubometro-rds-sg" \
>   --query "SecurityGroups[0].GroupId" --output text
> ```

### 3. Verificar que nao ficou nada para tras

```bash
# Verificar instancias RDS ativas
aws rds describe-db-instances \
  --query "DBInstances[*].[DBInstanceIdentifier,DBInstanceStatus]" \
  --output table

# Verificar snapshots (podem gerar custo)
aws rds describe-db-snapshots \
  --query "DBSnapshots[?DBInstanceIdentifier=='roubometro-db'].[DBSnapshotIdentifier,Status]" \
  --output table
```

Se aparecer algum snapshot, delete:

```bash
aws rds delete-db-snapshot --db-snapshot-identifier NOME_DO_SNAPSHOT
```

---

## Recriar do zero (apos destruir)

Se voce destruiu e quer recriar, basta repetir os passos da secao "Criar o Banco" (1 a 6). Os dados serao zerados, mas o batch recria tudo na primeira execucao (Flyway + Spring Batch auto-schema + processamento do CSV).

---

## Resumo de custos

| Situacao | Custo mensal |
|----------|-------------|
| Instancia rodando 24/7 (Free Tier) | $0.00 |
| Instancia rodando 24/7 (pos Free Tier) | ~$17.00 |
| Instancia **parada** | ~$2.30 (so storage) |
| Instancia **destruida** | $0.00 |

> **Dica**: se voce usa o banco apenas para testes esporadicos, a melhor estrategia e **destruir** apos o teste e **recriar** quando precisar. Leva ~10 min para recriar e nao custa nada.

---

## Referencia rapida de comandos

| Acao | Comando |
|------|---------|
| **Ver status** | `aws rds describe-db-instances --db-instance-identifier roubometro-db --query "DBInstances[0].DBInstanceStatus"` |
| **Ver endpoint** | `aws rds describe-db-instances --db-instance-identifier roubometro-db --query "DBInstances[0].Endpoint.Address" --output text` |
| **Parar** | `aws rds stop-db-instance --db-instance-identifier roubometro-db` |
| **Iniciar** | `aws rds start-db-instance --db-instance-identifier roubometro-db` |
| **Destruir** | `aws rds delete-db-instance --db-instance-identifier roubometro-db --skip-final-snapshot` |
| **Conectar** | `mysql -h ENDPOINT -u admin -p'SuaSenhaSegura123!' roubometro` |
| **Atualizar IP** | Ver secao "Se o seu IP mudar" no Passo 1 |
