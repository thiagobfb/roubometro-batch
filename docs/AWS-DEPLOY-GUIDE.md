# Guia de Deploy na AWS — roubometro-batch

> **Publico-alvo**: desenvolvedor com conta AWS criada, familiaridade basica com o console, primeiro deploy real.
>
> **Objetivo**: colocar o roubometro-batch rodando na AWS como tarefa agendada via EventBridge Scheduler (sem cron interno), conectando ao banco MySQL existente (Locaweb ou outro hosting externo).
>
> **Tempo estimado**: 2-3 horas na primeira vez.

---

## Visao Geral

```
┌──────────────────────────────────────────────────────────────────────┐
│                              AWS                                     │
│                                                                      │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────────────┐ │
│  │ EventBridge  │────→│  ECS Fargate  │────→│  CloudWatch Logs     │ │
│  │ (cron)       │     │  (batch task) │     │  (logs da aplicacao) │ │
│  └──────────────┘     └──────┬───────┘     └──────────────────────┘ │
│                              │                                       │
│  ┌──────────────┐            │                                       │
│  │ ECR          │            │ (imagem Docker)                       │
│  │ (registry)   │────────────┘                                       │
│  └──────────────┘                                                    │
│                              │                                       │
│  ┌──────────────┐            │ (credenciais DB)                      │
│  │ Secrets      │────────────┘                                       │
│  │ Manager      │                                                    │
│  └──────────────┘                                                    │
└──────────────────────────────────────────────────────────────────────┘
                               │
                               │ Internet (HTTPS / MySQL over SSL)
                               ▼
                  ┌──────────────────────┐
                  │  Hosting externo     │
                  │  (Locaweb / outro)   │
                  │  MySQL: roubometro   │
                  └──────────────────────┘
```

**Servicos AWS utilizados**:

| Servico | Para que serve | Custo estimado |
|---------|---------------|----------------|
| **ECR** (Elastic Container Registry) | Armazena a imagem Docker do batch | Free Tier: 500 MB/mes |
| **ECS Fargate** | Executa o container sem servidor | ~$0.01-0.03 por execucao (~2 min) |
| **EventBridge Scheduler** | Agenda a execucao (cron) | Gratuito (ate 14M invocacoes/mes) |
| **Secrets Manager** | Armazena credenciais do banco | ~$0.40/mes por secret |
| **CloudWatch Logs** | Armazena logs da aplicacao | Free Tier: 5 GB/mes |
| **IAM** | Permissoes entre servicos | Gratuito |

**Custo mensal estimado**: **< $2/mes** (dentro do Free Tier na maioria dos itens).

---

## Pre-requisitos

Antes de comecar, certifique-se de ter:

- [ ] Conta AWS ativa (com cartao cadastrado)
- [ ] **AWS CLI** instalada e configurada no seu computador
- [ ] **Docker** instalado e rodando
- [ ] Projeto **roubometro-batch** compilando localmente (`mvn clean package -DskipTests`)
- [ ] Acesso ao banco MySQL de producao (host, porta, usuario, senha)

### Instalar e configurar a AWS CLI

Se ainda nao tem a AWS CLI:

```bash
# Linux/WSL
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install

# Verificar instalacao
aws --version
```

Configurar com suas credenciais (Access Key + Secret Key, criadas no IAM Console):

```bash
aws configure
# AWS Access Key ID: <sua-access-key>
# AWS Secret Access Key: <sua-secret-key>
# Default region name: us-east-1
# Default output format: json
```

> **Dica**: use a regiao `us-east-1` (N. Virginia) — e a mais barata e tem todos os servicos.

> **Seguranca**: nunca use as credenciais do usuario root da conta. Crie um usuario IAM com permissoes administrativas. No console AWS, va em **IAM > Users > Create User**.

---

## Passo 1: Criar o repositorio de imagens (ECR)

O ECR e o "Docker Hub da AWS". E onde a imagem Docker do batch vai ficar armazenada.

### 1.1 Criar o repositorio

```bash
aws ecr create-repository \
  --repository-name roubometro-batch \
  --region us-east-1
```

Resposta (anote o `repositoryUri`):
```json
{
    "repository": {
        "repositoryUri": "SEU_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/roubometro-batch",
        ...
    }
}
```

> **O que e `repositoryUri`?** E o endereco da sua imagem na AWS. Voce vai usar esse valor em varios passos. Substitua `SEU_ACCOUNT_ID` pelo seu Account ID real (12 digitos, aparece no canto superior direito do console AWS).

### 1.2 Fazer login no ECR

```bash
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  SEU_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com
```

Resultado esperado: `Login Succeeded`

### 1.3 Construir e enviar a imagem

Na raiz do projeto `roubometro-batch`:

```bash
# Compilar o JAR
mvn clean package -DskipTests

# Construir a imagem Docker
docker build -t roubometro-batch .

# Taguear para o ECR
docker tag roubometro-batch:latest \
  SEU_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/roubometro-batch:latest

# Enviar para o ECR (push)
docker push SEU_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/roubometro-batch:latest
```

> **Primeira vez**: o push pode demorar alguns minutos (a imagem tem ~200 MB). Pushes seguintes sao incrementais e rapidos.

### 1.4 Verificar no console

Abra o console AWS: **ECR > Repositories > roubometro-batch**. Voce deve ver a imagem com a tag `latest`.

---

## Passo 2: Armazenar credenciais no Secrets Manager

O Secrets Manager guarda informacoes sensiveis (senhas, chaves) de forma criptografada. O ECS vai ler esses secrets automaticamente ao iniciar o container.

### 2.1 Criar o secret

```bash
aws secretsmanager create-secret \
  --name roubometro-batch/db-credentials \
  --description "Credenciais MySQL do roubometro-batch" \
  --secret-string '{
    "DB_HOST": "seu-host-mysql.locaweb.com.br",
    "DB_PORT": "3306",
    "DB_NAME": "roubometro",
    "DB_USER": "seu-usuario",
    "DB_PASSWORD": "sua-senha"
  }'
```

> **Substitua** os valores acima pelas credenciais reais do seu banco de producao.

### 2.2 Anotar o ARN do secret

A resposta contem um `ARN` (Amazon Resource Name). Anote-o:

```
arn:aws:secretsmanager:us-east-1:SEU_ACCOUNT_ID:secret:roubometro-batch/db-credentials-XXXXXX
```

> **O que e ARN?** E o identificador unico de qualquer recurso na AWS. Funciona como um "caminho absoluto" para o recurso.

---

## Passo 3: Criar as roles IAM

O ECS precisa de permissoes para: puxar imagens do ECR, ler secrets, e escrever logs. Vamos criar duas roles:

- **Task Execution Role**: usada pelo *agente ECS* para preparar o container (puxar imagem, injetar secrets)
- **Task Role**: usada *pela sua aplicacao* dentro do container (neste caso, nao precisa de nada extra)

### 3.1 Criar a Trust Policy

Crie um arquivo temporario `trust-policy.json`:

```bash
cat > /tmp/trust-policy.json << 'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
```

> **O que e Trust Policy?** Define *quem* pode assumir essa role. Aqui, estamos dizendo: "o servico ECS pode usar essa role".

### 3.2 Criar a Execution Role

```bash
aws iam create-role \
  --role-name roubometro-batch-execution-role \
  --assume-role-policy-document file:///tmp/trust-policy.json

# Anexar a politica padrao do ECS (permite puxar imagens e escrever logs)
aws iam attach-role-policy \
  --role-name roubometro-batch-execution-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

### 3.3 Dar acesso ao Secrets Manager

A politica padrao do ECS nao inclui acesso a secrets. Crie uma politica inline:

```bash
cat > /tmp/secrets-policy.json << 'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue"
      ],
      "Resource": "arn:aws:secretsmanager:us-east-1:SEU_ACCOUNT_ID:secret:roubometro-batch/*"
    }
  ]
}
EOF

aws iam put-role-policy \
  --role-name roubometro-batch-execution-role \
  --policy-name SecretsManagerAccess \
  --policy-document file:///tmp/secrets-policy.json
```

> **Substitua** `SEU_ACCOUNT_ID` pelo seu Account ID.

---

## Passo 4: Configurar o ECS (cluster + task definition)

### 4.1 Conceitos-chave do ECS

Antes de prosseguir, entenda os termos:

| Termo | Analogia | O que faz |
|-------|----------|-----------|
| **Cluster** | Um "grupo de trabalho" | Agrupa suas tasks e servicos |
| **Task Definition** | Uma "receita" | Define qual imagem usar, quanta CPU/memoria, variaveis de ambiente |
| **Task** | Uma "execucao" | Uma instancia rodando da task definition (como um `docker run`) |
| **Fargate** | "Serverless para containers" | A AWS gerencia o servidor; voce so define CPU e memoria |

### 4.2 Criar o cluster

```bash
aws ecs create-cluster --cluster-name roubometro
```

> Voce pode usar o mesmo cluster para outros servicos futuros (ex: roubometro-back).

### 4.3 Criar o log group no CloudWatch

```bash
aws logs create-log-group --log-group-name /ecs/roubometro-batch
```

### 4.4 Criar a Task Definition

Crie o arquivo `ecs-task-definition.json`:

```bash
cat > /tmp/ecs-task-definition.json << 'TASKEOF'
{
  "family": "roubometro-batch",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "executionRoleArn": "arn:aws:iam::SEU_ACCOUNT_ID:role/roubometro-batch-execution-role",
  "containerDefinitions": [
    {
      "name": "roubometro-batch",
      "image": "SEU_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/roubometro-batch:latest",
      "essential": true,
      "environment": [
        { "name": "SPRING_PROFILES_ACTIVE", "value": "prod" },
        { "name": "JAVA_OPTS", "value": "-Xmx768m -Xms256m" }
      ],
      "secrets": [
        { "name": "DB_HOST",     "valueFrom": "arn:aws:secretsmanager:us-east-1:SEU_ACCOUNT_ID:secret:roubometro-batch/db-credentials-XXXXXX:DB_HOST::" },
        { "name": "DB_PORT",     "valueFrom": "arn:aws:secretsmanager:us-east-1:SEU_ACCOUNT_ID:secret:roubometro-batch/db-credentials-XXXXXX:DB_PORT::" },
        { "name": "DB_NAME",     "valueFrom": "arn:aws:secretsmanager:us-east-1:SEU_ACCOUNT_ID:secret:roubometro-batch/db-credentials-XXXXXX:DB_NAME::" },
        { "name": "DB_USER",     "valueFrom": "arn:aws:secretsmanager:us-east-1:SEU_ACCOUNT_ID:secret:roubometro-batch/db-credentials-XXXXXX:DB_USER::" },
        { "name": "DB_PASSWORD", "valueFrom": "arn:aws:secretsmanager:us-east-1:SEU_ACCOUNT_ID:secret:roubometro-batch/db-credentials-XXXXXX:DB_PASSWORD::" }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/roubometro-batch",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "batch"
        }
      }
    }
  ]
}
TASKEOF
```

> **Sobre CPU e memoria**:
> - `"cpu": "512"` = 0.5 vCPU
> - `"memory": "1024"` = 1 GB RAM
>
> Isso e suficiente para processar ~12K rows do CSV. O batch roda em ~1-2 minutos.
>
> **Sobre `secrets`**: o ECS le os valores do Secrets Manager e injeta como variaveis de ambiente no container. A aplicacao Spring Boot le via `${DB_HOST}`, `${DB_PASSWORD}`, etc. (ja configurado no `application-prod.yml`).

Registrar a task definition:

```bash
aws ecs register-task-definition \
  --cli-input-json file:///tmp/ecs-task-definition.json
```

> **Importante**: toda vez que voce alterar a task definition (ex: nova imagem, mais memoria), registre uma nova revisao com o mesmo comando. O ECS versiona automaticamente (`roubometro-batch:1`, `roubometro-batch:2`, etc.).

---

## Passo 5: Configurar a rede (VPC e Security Group)

O Fargate precisa de uma subnet com acesso a internet (para conectar ao MySQL externo e ao portal ISP-RJ).

### 5.1 Identificar sua VPC e subnets

Toda conta AWS ja vem com uma VPC default. Vamos usa-la:

```bash
# Listar VPCs
aws ec2 describe-vpcs --filters "Name=isDefault,Values=true" \
  --query "Vpcs[0].VpcId" --output text
```

Anote o VPC ID (ex: `SEU_VPC_ID`).

```bash
# Listar subnets publicas da VPC default
aws ec2 describe-subnets \
  --filters "Name=vpc-id,Values=SEU_VPC_ID" "Name=default-for-az,Values=true" \
  --query "Subnets[*].[SubnetId,AvailabilityZone]" --output table
```

Anote pelo menos **uma** Subnet ID (ex: `SEU_SUBNET_ID`).

> **O que e VPC?** Virtual Private Cloud — e a rede virtual privada da sua conta. Tudo que roda na AWS vive dentro de uma VPC.
>
> **O que e Subnet?** Uma subdivisao da VPC, associada a uma zona de disponibilidade (data center fisico). Subnets publicas tem acesso a internet.

### 5.2 Criar o Security Group

O Security Group funciona como um firewall. O batch precisa de:
- **Saida**: acesso ao MySQL (porta 3306) e ao portal ISP-RJ (porta 443/HTTPS)
- **Entrada**: nada (o batch nao recebe conexoes, ele so faz conexoes de saida)

```bash
# Criar o Security Group
aws ec2 create-security-group \
  --group-name roubometro-batch-sg \
  --description "Security Group para roubometro-batch (somente saida)" \
  --vpc-id SEU_VPC_ID
```

Anote o Security Group ID (ex: `SEU_SG_ID`).

> **Por padrao**, Security Groups na AWS ja permitem todo trafego de saida (egress) e bloqueiam todo trafego de entrada (ingress). Isso e exatamente o que precisamos — nao e necessario adicionar nenhuma regra extra.

---

## Passo 6: Testar a execucao manual

Antes de agendar o cron, vamos rodar o batch uma vez manualmente para confirmar que tudo funciona:

```bash
aws ecs run-task \
  --cluster roubometro \
  --task-definition roubometro-batch \
  --launch-type FARGATE \
  --network-configuration '{
    "awsvpcConfiguration": {
      "subnets": ["SEU_SUBNET_ID"],
      "securityGroups": ["SEU_SG_ID"],
      "assignPublicIp": "ENABLED"
    }
  }'
```

> **`assignPublicIp: ENABLED`** e necessario porque o batch precisa de internet para acessar o MySQL externo e o portal ISP-RJ. Se voce configurar um NAT Gateway no futuro, pode desabilitar isso.

### 6.1 Acompanhar a execucao

A task demora ~30s para iniciar (pull da imagem) + ~1-2 min para processar.

```bash
# Listar tasks rodando no cluster
aws ecs list-tasks --cluster roubometro --query "taskArns" --output table
```

### 6.2 Ver os logs

```bash
# Ver logs no CloudWatch (ultimas 50 linhas)
aws logs tail /ecs/roubometro-batch --since 10m
```

Ou no console AWS: **CloudWatch > Log groups > /ecs/roubometro-batch**

### 6.3 Verificar o resultado

Procure no log por:

```
Job: [FlowJob: [name=roubometroDataSyncJob]] completed with status: [COMPLETED]
```

Se aparecer `COMPLETED`, o batch rodou com sucesso.

### 6.4 Solucao de problemas comuns

| Erro no log | Causa | Solucao |
|-------------|-------|---------|
| `Communications link failure` | Batch nao consegue conectar ao MySQL | Verificar se o hosting permite conexoes externas; verificar host/porta/usuario no secret |
| `Access denied for user` | Credenciais erradas | Atualizar o secret no Secrets Manager |
| `Task stopped: Essential container exited` | Container crashou | Ver logs no CloudWatch para o erro real |
| Task fica `PENDING` por muito tempo | Sem capacidade Fargate | Verificar se a subnet tem acesso a internet (IGW) |
| `CannotPullContainerError` | ECS nao consegue puxar imagem | Verificar permissoes da execution role e se a imagem existe no ECR |

---

## Passo 7: Agendar a execucao com EventBridge

O EventBridge Scheduler substitui o cron do Linux. Vamos agendar o batch para rodar semanalmente (suficiente para capturar as atualizacoes mensais do ISP-RJ com no maximo ~7 dias de atraso).

### 7.1 Criar a role para o EventBridge

O EventBridge precisa de permissao para executar tasks no ECS:

```bash
cat > /tmp/eventbridge-trust.json << 'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "scheduler.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

aws iam create-role \
  --role-name roubometro-batch-scheduler-role \
  --assume-role-policy-document file:///tmp/eventbridge-trust.json

cat > /tmp/ecs-run-policy.json << 'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "ecs:RunTask",
      "Resource": "arn:aws:ecs:us-east-1:SEU_ACCOUNT_ID:task-definition/roubometro-batch:*"
    },
    {
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": "arn:aws:iam::SEU_ACCOUNT_ID:role/roubometro-batch-execution-role"
    }
  ]
}
EOF

aws iam put-role-policy \
  --role-name roubometro-batch-scheduler-role \
  --policy-name ECSRunTaskAccess \
  --policy-document file:///tmp/ecs-run-policy.json
```

### 7.2 Criar o agendamento

```bash
aws scheduler create-schedule \
  --name roubometro-batch-biweekly \
  --schedule-expression "cron(0 3 ? * SUN *)" \
  --schedule-expression-timezone "America/Sao_Paulo" \
  --flexible-time-window '{"Mode": "OFF"}' \
  --target '{
    "Arn": "arn:aws:ecs:us-east-1:SEU_ACCOUNT_ID:cluster/roubometro",
    "RoleArn": "arn:aws:iam::SEU_ACCOUNT_ID:role/roubometro-batch-scheduler-role",
    "EcsParameters": {
      "TaskDefinitionArn": "arn:aws:ecs:us-east-1:SEU_ACCOUNT_ID:task-definition/roubometro-batch",
      "TaskCount": 1,
      "LaunchType": "FARGATE",
      "NetworkConfiguration": {
        "AwsvpcConfiguration": {
          "Subnets": ["SEU_SUBNET_ID"],
          "SecurityGroups": ["SEU_SG_ID"],
          "AssignPublicIp": "ENABLED"
        }
      }
    }
  }'
```

> **`cron(0 3 ? * SUN *)`** = todo domingo as 3h da manha (horario de Sao Paulo).
>
> **Por que semanal e nao quinzenal?** O cron padrao nao suporta "a cada 2 semanas" nativamente. Opcoes:
>
> | Opcao | Expressao | Comportamento |
> |-------|-----------|---------------|
> | **Semanal (recomendado)** | `cron(0 3 ? * SUN *)` | Todo domingo 3h. Custo extra: ~$0.04/mes |
> | Quinzenal aproximado | `rate(14 days)` | A cada 14 dias, mas nao garante cair no domingo |
>
> **Recomendacao**: use semanal. O batch e idempotente — se nao houver dados novos, termina em segundos sem custo significativo. A diferenca de custo entre semanal e quinzenal e ~$0.04/mes (4 execucoes vs 2).
>
> **Por que domingo 3h?** Horario de baixa atividade, menor chance de conflito com outros processos, e o CSV do ISP-RJ e atualizado durante a semana.

### 7.3 Verificar no console

**EventBridge > Schedules > roubometro-batch-weekly** — mostra a proxima execucao prevista.

---

## Passo 8: Monitoramento e alertas

### 8.1 Ver historico de execucoes

No console: **ECS > Clusters > roubometro > Tasks (aba Stopped)** — mostra as ultimas execucoes com status e tempo.

### 8.2 Criar alerta de falha (opcional, recomendado)

Para receber e-mail se o batch falhar:

```bash
# Criar topico SNS para alertas
aws sns create-topic --name roubometro-batch-alerts

# Inscrever e-mails (repita para cada destinatario)
aws sns subscribe \
  --topic-arn arn:aws:sns:us-east-1:SEU_ACCOUNT_ID:roubometro-batch-alerts \
  --protocol email \
  --notification-endpoint seu-email@example.com
```

> **Confirme a inscricao** clicando no link do e-mail que a AWS envia. Cada e-mail precisa confirmar individualmente.

### 8.3 Criar metric filter nos logs

O ECS nao emite metrica nativa de falha por task. A abordagem mais precisa e criar um **metric filter** no CloudWatch Logs que detecta quando o Spring Batch loga status `FAILED`:

```bash
aws logs put-metric-filter \
  --log-group-name /ecs/roubometro-batch \
  --filter-name BatchJobFailed \
  --filter-pattern '"completed with status" "FAILED"' \
  --metric-transformations \
    metricName=BatchJobFailure,metricNamespace=Roubometro,metricValue=1,defaultValue=0
```

> **Como funciona**: o Spring Batch sempre loga `completed with status: [COMPLETED]` ou `completed with status: [FAILED]`. O metric filter monitora o log group e incrementa a metrica `BatchJobFailure` quando encontra `FAILED`.

### 8.4 Criar alarme no CloudWatch

```bash
aws cloudwatch put-metric-alarm \
  --alarm-name roubometro-batch-failure \
  --alarm-description "Alerta quando o batch job falha (status FAILED nos logs)" \
  --namespace Roubometro \
  --metric-name BatchJobFailure \
  --statistic Sum \
  --period 300 \
  --threshold 1 \
  --comparison-operator GreaterThanOrEqualToThreshold \
  --evaluation-periods 1 \
  --treat-missing-data notBreaching \
  --alarm-actions arn:aws:sns:us-east-1:SEU_ACCOUNT_ID:roubometro-batch-alerts
```

> **`treat-missing-data notBreaching`**: quando o batch nao esta rodando (99.9% do tempo), nao ha dados na metrica. Essa configuracao evita alarmes falsos nesses periodos.

---

## Passo 9: Atualizar o batch (deploy de nova versao)

Quando voce fizer alteracoes no codigo e quiser atualizar o batch na AWS:

```bash
# 1. Compilar
mvn clean package -DskipTests

# 2. Construir imagem
docker build -t roubometro-batch .

# 3. Taguear e enviar para o ECR
docker tag roubometro-batch:latest \
  SEU_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/roubometro-batch:latest

docker push \
  SEU_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/roubometro-batch:latest

# 4. (Opcional) Forcar uma execucao imediata para testar
aws ecs run-task \
  --cluster roubometro \
  --task-definition roubometro-batch \
  --launch-type FARGATE \
  --network-configuration '{
    "awsvpcConfiguration": {
      "subnets": ["SEU_SUBNET_ID"],
      "securityGroups": ["SEU_SG_ID"],
      "assignPublicIp": "ENABLED"
    }
  }'
```

> **Nao precisa** atualizar a task definition se so mudou o codigo. O ECS sempre puxa a tag `latest` do ECR. Se voce alterou CPU, memoria ou variaveis de ambiente, registre uma nova revisao da task definition (Passo 4.4).

---

## Resumo dos recursos criados

Anote esses valores para referencia. Eles sao necessarios para futuras operacoes:

| Recurso | Valor | Onde usar | Status |
|---------|-------|-----------|--------|
| **Account ID** | `SEU_ACCOUNT_ID` | Em todos os ARNs | ✅ |
| **ECR Repository URI** | `SEU_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/roubometro-batch` | Push/pull de imagens | ✅ (imagem `latest`) |
| **Secret ARN** | `arn:aws:secretsmanager:us-east-1:SEU_ACCOUNT_ID:secret:roubometro-batch/db-credentials-XXXXXX` | Task definition | ✅ |
| **VPC ID** | `SEU_VPC_ID` | Security Group | ✅ |
| **Subnet ID** | `SEU_SUBNET_ID` (us-east-1a) | Task execution | ✅ |
| **Security Group ID** | `SEU_SG_ID` | Task execution | ✅ |
| **Cluster Name** | `roubometro` | Todos os comandos ECS | ✅ (ACTIVE) |
| **Task Definition** | `roubometro-batch` rev.2 | ECS run-task | ✅ (ACTIVE) |
| **Execution Role ARN** | `arn:aws:iam::SEU_ACCOUNT_ID:role/roubometro-batch-execution-role` | Task definition | ✅ |
| **Scheduler Role ARN** | `arn:aws:iam::SEU_ACCOUNT_ID:role/roubometro-batch-scheduler-role` | EventBridge | ✅ |
| **Log Group** | `/ecs/roubometro-batch` | CloudWatch | ✅ |
| **SNS Topic** | `roubometro-batch-alerts` | Alertas de falha | ✅ |
| **CloudWatch Alarm** | `roubometro-batch-failure` | Dispara SNS ao detectar FAILED | ✅ |
| **Schedule Name** | `roubometro-batch-biweekly` | EventBridge | ❌ Nao criado |

---

## Checklist final

- [x] **ECR**: imagem `roubometro-batch:latest` enviada
- [x] **Secrets Manager**: credenciais do banco armazenadas
- [x] **IAM**: execution role com permissoes para ECR + Secrets Manager + CloudWatch
- [x] **ECS**: cluster `roubometro` criado, task definition registrada (rev.2)
- [x] **Rede**: VPC, subnet e security group configurados
- [ ] **Teste manual**: `run-task` executado com sucesso, logs mostram `COMPLETED`
- [ ] **EventBridge**: schedule `roubometro-batch-biweekly` criado
- [x] **Monitoramento**: SNS + CloudWatch metric filter + alarme configurados
- [ ] **Banco**: `monthly_stats` com dados apos a primeira execucao

---

## Por que EventBridge + Fargate e mais barato que aplicacao 24/7

Esta arquitetura usa o modelo **"on-demand"**: o container so existe enquanto o job esta rodando.

### Comparacao de custos mensais

| Arquitetura | Como funciona | Custo mensal |
|-------------|---------------|--------------|
| **EventBridge + Fargate (este guia)** | Container inicia, executa job (~2 min), morre | **~$0.08 - $0.15** |
| EC2 t3.micro 24/7 | VM rodando o tempo todo, job executa via cron | **~$8.50** |
| ECS Service (always running) | Container rodando 24/7 esperando o scheduler | **~$15.00** |
| Lambda (se o job coubesse em 15 min) | Funcao serverless | **~$0.02** |

### Por que a diferenca e tao grande?

1. **Tempo de execucao**: O batch roda ~2 minutos por semana. Em um mes, sao ~8 minutos de computacao.

2. **Modelo de cobranca do Fargate**:
   - Cobra por **segundo** de uso
   - 0.5 vCPU + 1 GB RAM = ~$0.03/hora
   - 8 minutos/mes = ~$0.004 de Fargate
   - O resto do custo vem de CloudWatch Logs (~$0.10) e Secrets Manager (~$0.40)

3. **EC2/ECS 24/7**: Paga mesmo quando nao esta fazendo nada
   - 720 horas/mes x $0.0116/hora (t3.micro) = $8.35
   - Voce paga 720 horas para usar 0.13 horas (8 min)

### Recomendacao

Para jobs batch que rodam periodicamente (diario, semanal, mensal):
- **Use EventBridge + Fargate** (ou Lambda se couber em 15 min)
- **Nunca** mantenha um servidor/container rodando 24/7 so para executar um cron

> **Analogia**: E como alugar um carro so para as horas que voce usa, vs comprar um carro que fica 99% do tempo parado na garagem.

---

## Troubleshooting — Problemas comuns e solucoes

Esta secao documenta problemas reais encontrados durante o deploy e como resolve-los.

### 1. Container nao consegue conectar ao MySQL externo

**Sintoma**: Logs mostram `Communications link failure` ou `Connection timed out`

**Causas possiveis**:

| Causa | Como verificar | Solucao |
|-------|---------------|---------|
| Security Group bloqueando saida | Verificar regras de egress do SG | SG default permite todo egress. Se customizou, adicione regra para porta 3306 |
| Subnet sem internet | Task fica PENDING ou falha ao iniciar | Use subnet publica com `assignPublicIp: ENABLED` |
| Hosting bloqueia IPs externos | Tente conectar do seu PC | Liberar range de IPs da AWS ou usar IP fixo (NAT Gateway) |
| Firewall do hosting | Contatar suporte do hosting | Pedir liberacao da porta 3306 para conexoes externas |

**Teste de conectividade** (rodar localmente):
```bash
# Verificar se a porta esta acessivel
nc -zv seu-host-mysql.com 3306

# Testar conexao MySQL
mysql -h seu-host-mysql.com -u usuario -p -e "SELECT 1;"
```

### 2. Erro de SSL/TLS com MySQL

**Sintoma**: `SSL connection error` ou `unable to find valid certification path`

**Causa**: MySQL externo exige SSL mas o certificado nao e reconhecido

**Solucao**: No `application-prod.yml`, a URL ja esta configurada com:
```
useSSL=true&requireSSL=true&verifyServerCertificate=false
```

Se ainda falhar, verificar se o hosting suporta SSL. Alguns hostings baratos nao suportam.

### 3. Task ECS falha com "CannotPullContainerError"

**Sintoma**: Task vai para STOPPED com erro de pull da imagem

**Causas e solucoes**:

| Causa | Solucao |
|-------|---------|
| Imagem nao existe no ECR | Verificar nome/tag: `aws ecr describe-images --repository-name roubometro-batch` |
| Execution Role sem permissao ECR | Anexar policy `AmazonECSTaskExecutionRolePolicy` a role |
| ECR em regiao diferente | Usar o mesmo region no ECR e ECS |

### 4. Secrets nao sao injetados no container

**Sintoma**: Aplicacao falha com `DB_HOST is null` ou similar

**Causas e solucoes**:

| Causa | Solucao |
|-------|---------|
| ARN do secret incorreto na task definition | Copiar ARN exato: `aws secretsmanager describe-secret --secret-id roubometro-batch/db-credentials` |
| Formato do valueFrom incorreto | Deve ser `ARN:campo::` (com dois-pontos no final) |
| Execution Role sem permissao | Verificar policy `SecretsManagerAccess` na role |

**Formato correto do secret na task definition**:
```json
{
  "name": "DB_HOST",
  "valueFrom": "arn:aws:secretsmanager:us-east-1:SEU_ACCOUNT_ID:secret:roubometro-batch/db-credentials-XXXXXX:DB_HOST::"
}
```

### 5. Job executa mas nao insere dados

**Sintoma**: Logs mostram `COMPLETED` mas tabela `monthly_stats` esta vazia

**Causas e solucoes**:

| Causa | Solucao |
|-------|---------|
| Municipios nao cadastrados | Rodar seed: `mysql < docker/init-db/02_seed_data.sql` |
| Categorias nao cadastradas | Verificar tabela `categories` |
| CSV vazio ou URL mudou | Verificar `roubometro.portal.csv-url` no application.yml |
| Job ja rodou com esse arquivo | Verificar `batch_file_metadata` — batch e idempotente |

### 6. EventBridge nao dispara a task

**Sintoma**: Schedule existe mas task nunca executa

**Causas e solucoes**:

| Causa | Solucao |
|-------|---------|
| Schedule desabilitado | `aws scheduler get-schedule --name roubometro-batch-biweekly` — verificar `State` |
| Role sem permissao ecs:RunTask | Verificar policy na `roubometro-batch-scheduler-role` |
| Role sem permissao iam:PassRole | Adicionar permissao para passar a execution role |
| Timezone incorreto | Verificar `ScheduleExpressionTimezone` |

**Verificar proxima execucao**:
```bash
aws scheduler get-schedule --name roubometro-batch-biweekly \
  --query "[State, ScheduleExpression, ScheduleExpressionTimezone]"
```

### 7. Logs nao aparecem no CloudWatch

**Sintoma**: Task executa mas `/ecs/roubometro-batch` esta vazio

**Causas e solucoes**:

| Causa | Solucao |
|-------|---------|
| Log group nao existe | `aws logs create-log-group --log-group-name /ecs/roubometro-batch` |
| Execution Role sem permissao logs | Policy `AmazonECSTaskExecutionRolePolicy` ja inclui isso |
| Container crashou antes de logar | Verificar eventos da task no console ECS |

### 8. Custo inesperado na fatura AWS

**Sintoma**: Cobranca maior que o esperado (~$2/mes)

**Onde verificar**:
```bash
# Listar recursos que podem gerar custo

# RDS (se criou para teste e esqueceu de destruir)
aws rds describe-db-instances --query "DBInstances[*].[DBInstanceIdentifier,DBInstanceStatus]"

# NAT Gateway (caro! ~$32/mes)
aws ec2 describe-nat-gateways --query "NatGateways[*].[NatGatewayId,State]"

# Elastic IPs nao associados (~$3.65/mes cada)
aws ec2 describe-addresses --query "Addresses[?AssociationId==null]"

# Snapshots RDS
aws rds describe-db-snapshots --query "DBSnapshots[*].[DBSnapshotIdentifier,AllocatedStorage]"
```

**Dica**: Use o **AWS Cost Explorer** para ver o breakdown por servico.

---

## Glossario

| Termo | Explicacao |
|-------|-----------|
| **ARN** | Amazon Resource Name — identificador unico de qualquer recurso na AWS. Formato: `arn:aws:servico:regiao:conta:recurso` |
| **ECR** | Elastic Container Registry — Docker Hub privado da AWS |
| **ECS** | Elastic Container Service — servico que roda containers Docker |
| **Fargate** | Modo serverless do ECS — a AWS gerencia os servidores, voce so paga pelo tempo de uso |
| **IAM** | Identity and Access Management — controle de permissoes (quem pode fazer o que) |
| **VPC** | Virtual Private Cloud — sua rede privada na AWS |
| **Subnet** | Subdivisao da VPC, associada a uma zona de disponibilidade |
| **Security Group** | Firewall virtual que controla trafego de entrada e saida |
| **EventBridge** | Servico de eventos e agendamento (substitui o cron) |
| **CloudWatch** | Servico de monitoramento: logs, metricas e alarmes |
| **Secrets Manager** | Cofre digital para senhas e chaves |
| **SNS** | Simple Notification Service — envia notificacoes (e-mail, SMS, etc.) |
| **Free Tier** | Nivel gratuito da AWS para novos usuarios (12 meses para a maioria dos servicos) |

---

## Sobre o agendamento (cron interno vs EventBridge)

A aplicacao **nao possui cron interno** (`@Scheduled` foi removido). O batch segue o modelo **"run and die"**:

1. O container inicia
2. O Spring Boot roda o job automaticamente (`spring.batch.job.enabled: true` no profile `prod`)
3. O job executa (download CSV → processamento → gravacao no banco)
4. O container encerra

O agendamento e responsabilidade **exclusiva da AWS** via **EventBridge Scheduler** (configurado no Passo 7). Isso significa:

- **Nao ha processo rodando 24/7** esperando o horario do cron
- **Custo minimo**: paga apenas pelos ~2 minutos de execucao
- **Facil de alterar**: mude o schedule no EventBridge sem rebuild/deploy da aplicacao
- **Execucao manual**: a qualquer momento via `aws ecs run-task` (Passo 6)

### Alterar a frequencia de execucao

Para mudar o horario ou frequencia, atualize o schedule no EventBridge:

```bash
aws scheduler update-schedule \
  --name roubometro-batch-biweekly \
  --schedule-expression "cron(0 3 ? * SUN *)" \
  --schedule-expression-timezone "America/Sao_Paulo" \
  --flexible-time-window '{"Mode": "OFF"}' \
  --target '{
    "Arn": "arn:aws:ecs:us-east-1:SEU_ACCOUNT_ID:cluster/roubometro",
    "RoleArn": "arn:aws:iam::SEU_ACCOUNT_ID:role/roubometro-batch-scheduler-role",
    "EcsParameters": {
      "TaskDefinitionArn": "arn:aws:ecs:us-east-1:SEU_ACCOUNT_ID:task-definition/roubometro-batch",
      "TaskCount": 1,
      "LaunchType": "FARGATE",
      "NetworkConfiguration": {
        "AwsvpcConfiguration": {
          "Subnets": ["SUA_SUBNET_ID"],
          "SecurityGroups": ["SEU_SG_ID"],
          "AssignPublicIp": "ENABLED"
        }
      }
    }
  }'
```

Exemplos de expressoes cron do EventBridge:

| Frequencia | Expressao | Descricao |
|------------|-----------|-----------|
| Semanal (domingo 3h) | `cron(0 3 ? * SUN *)` | Recomendado |
| Diario (3h) | `cron(0 3 * * ? *)` | Para testes |
| Mensal (dia 1, 3h) | `cron(0 3 1 * ? *)` | Economia maxima |
| A cada 14 dias | `rate(14 days)` | Nao garante dia da semana |

> **Nota**: o batch e idempotente — rodar varias vezes com o mesmo CSV nao duplica dados. Portanto, aumentar a frequencia nao causa problemas, apenas custo marginalmente maior (~$0.02 por execucao extra).

---

## Proximos passos (opcionalidades para o futuro)

1. **CI/CD**: automatizar o deploy com GitHub Actions (build → push ECR → run task)
2. **Infraestrutura como codigo**: usar Terraform ou AWS CDK para versionar todos os recursos criados
3. **NAT Gateway**: se quiser rodar o batch em subnet privada (sem IP publico), configure um NAT Gateway (~$32/mes)
4. **RDS dedicado**: se decidir separar as tabelas do Spring Batch (ver ADR-001)
5. **Dashboard CloudWatch**: criar painel visual com metricas do batch (duracao, sucesso/falha)
