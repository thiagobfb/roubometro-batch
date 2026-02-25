CP# CI/CD com GitHub Actions + AWS — roubometro-batch

> **Publico-alvo**: desenvolvedor que ja seguiu o [AWS-DEPLOY-GUIDE.md](AWS-DEPLOY-GUIDE.md) e quer automatizar o deploy.
>
> **Pre-requisito**: os recursos da AWS ja devem estar criados (ECR, ECS, Secrets Manager, IAM roles, etc.).
>
> **Objetivo**: a cada push na branch `main`, o GitHub Actions automaticamente compila, testa, builda a imagem Docker, envia ao ECR e executa o batch no ECS.

---

## Visao Geral

```
┌────────────────────────────────────────────────────────────────────────────┐
│                            GitHub Actions                                  │
│                                                                            │
│  push na main                                                              │
│      │                                                                     │
│      ▼                                                                     │
│  ┌─────────┐     ┌─────────┐     ┌─────────┐     ┌─────────┐             │
│  │  build   │────→│  test   │────→│  docker  │────→│ deploy  │             │
│  │(compile) │     │(verify) │     │(push ECR)│     │(run ECS)│             │
│  └─────────┘     └─────────┘     └─────────┘     └─────────┘             │
│                                       │                │                   │
└───────────────────────────────────────┼────────────────┼───────────────────┘
                                        │                │
                              OIDC (sem senha)    OIDC (sem senha)
                                        │                │
                                        ▼                ▼
                                   ┌─────────┐     ┌─────────┐
                                   │   ECR   │     │   ECS   │
                                   │(imagem) │     │(executa)│
                                   └─────────┘     └─────────┘
```

### Glossario rapido

| Termo GitHub Actions | O que e | Analogia |
|---------------------|---------|----------|
| **Workflow** | Arquivo YAML que define o fluxo de CI/CD | O "pipeline" completo |
| **Job** | Grupo de steps que roda em uma maquina virtual | Uma "fase" do pipeline |
| **Step** | Uma acao individual dentro de um job | Um "comando" |
| **Action** | Bloco reutilizavel criado pela comunidade | Um "plugin" |
| **Runner** | A maquina virtual que executa os jobs | O "servidor de build" |
| **Secret** | Variavel criptografada configurada no repositorio | Uma "env var segura" |
| **OIDC** | OpenID Connect — autenticacao sem senha fixa | "Login temporario" |

---

## Passo 1: Configurar OIDC entre GitHub e AWS

### Por que OIDC e nao Access Keys?

O metodo antigo exigia salvar `AWS_ACCESS_KEY_ID` e `AWS_SECRET_ACCESS_KEY` como secrets do GitHub. O problema: essas chaves **nunca expiram** e, se vazarem, dao acesso permanente a sua conta AWS.

Com **OIDC** (OpenID Connect), o GitHub pede credenciais **temporarias** a cada execucao do workflow. Nao ha chaves fixas para vazar.

```
GitHub Actions                          AWS
     │                                   │
     │  "Sou o repo X, branch main"      │
     ├──────────────────────────────────→ │
     │                                   │
     │  "OK, aqui estao credenciais      │
     │   temporarias (15 min)"           │
     │ ←────────────────────────────────┤
     │                                   │
     │  (usa credenciais para ECR/ECS)   │
     ├──────────────────────────────────→ │
     │                                   │
```

### 1.1 Criar o Identity Provider no IAM

Isso diz a AWS: "confie em tokens vindos do GitHub Actions".

**Via console AWS** (mais visual para iniciantes):

1. Abra **IAM > Identity providers > Add provider**
2. Selecione **OpenID Connect**
3. Provider URL: `https://token.actions.githubusercontent.com`
4. Clique **Get thumbprint**
5. Audience: `sts.amazonaws.com`
6. Clique **Add provider**

**Via CLI** (se preferir):

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 1c58a3a8518e8759bf075b76b750d4f2df264fcd
```

> **Thumbprint**: e uma "impressao digital" do certificado SSL do GitHub. A AWS usa isso para verificar que o token realmente veio do GitHub. Os dois valores acima cobrem os certificados intermediarios atuais do GitHub.

### 1.2 Criar a Role para o GitHub Actions

Essa role define **o que** o GitHub Actions pode fazer na sua conta AWS.

```bash
# Substitua "thiagobfb/roubometro-batch" pelo seu usuario/repo real
cat > /tmp/github-oidc-trust.json << 'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:thiagobfb/roubometro-batch:ref:refs/heads/main"
        }
      }
    }
  ]
}
EOF

aws iam create-role \
  --role-name github-actions-roubometro-batch \
  --assume-role-policy-document file:///tmp/github-oidc-trust.json
```

> **Seguranca**: a condicao `StringLike` com `ref:refs/heads/main` garante que **somente** workflows disparados pela branch `main` podem assumir essa role. Um push em outra branch ou um fork nao consegue usar essas permissoes.

### 1.3 Dar permissoes a role

A role precisa de acesso a: ECR (push de imagem), ECS (executar task) e IAM (passar a execution role para o ECS).

```bash
cat > /tmp/github-actions-policy.json << 'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ECRAuth",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "ECRPush",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "ecr:PutImage",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload"
      ],
      "Resource": "arn:aws:ecr:us-east-1:123456789012:repository/roubometro-batch"
    },
    {
      "Sid": "ECSRunTask",
      "Effect": "Allow",
      "Action": [
        "ecs:RunTask",
        "ecs:DescribeTasks"
      ],
      "Resource": "arn:aws:ecs:us-east-1:123456789012:task-definition/roubometro-batch:*"
    },
    {
      "Sid": "PassRole",
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": "arn:aws:iam::123456789012:role/roubometro-batch-execution-role"
    }
  ]
}
EOF

aws iam put-role-policy \
  --role-name github-actions-roubometro-batch \
  --policy-name DeployAccess \
  --policy-document file:///tmp/github-actions-policy.json
```

### 1.4 Anotar o ARN da role

```bash
aws iam get-role --role-name github-actions-roubometro-batch \
  --query "Role.Arn" --output text
```

Resultado (anote): `arn:aws:iam::123456789012:role/github-actions-roubometro-batch`

---

## Passo 2: Configurar secrets no GitHub

O workflow precisa saber alguns valores especificos da sua conta AWS. Vamos salva-los como secrets do repositorio.

### 2.1 No GitHub

1. Abra o repositorio no GitHub
2. Va em **Settings > Secrets and variables > Actions**
3. Clique **New repository secret** para cada um:

| Nome do Secret | Valor | Exemplo |
|----------------|-------|---------|
| `AWS_ACCOUNT_ID` | Seu Account ID (12 digitos) | `123456789012` |
| `AWS_ROLE_ARN` | ARN da role criada no Passo 1 | `arn:aws:iam::123456789012:role/github-actions-roubometro-batch` |
| `ECS_SUBNET` | Subnet ID (do AWS-DEPLOY-GUIDE) | `subnet-0abc123` |
| `ECS_SECURITY_GROUP` | Security Group ID | `sg-0abc123` |

> **Por que nao salvar a senha do banco?** Porque ela ja esta no AWS Secrets Manager. O GitHub Actions nao precisa saber a senha — ele so dispara a task no ECS, e o ECS busca as credenciais do Secrets Manager automaticamente.

### 2.2 Verificar

Em **Settings > Secrets and variables > Actions**, voce deve ver 4 secrets listados (os valores ficam ocultos apos salvar).

---

## Passo 3: Criar o workflow

O workflow e um arquivo YAML que fica dentro do repositorio, no diretorio `.github/workflows/`. O GitHub detecta automaticamente qualquer arquivo `.yml` nesse diretorio.

### 3.1 Estrutura de diretorios

```
roubometro-batch/
├── .github/
│   └── workflows/
│       └── deploy.yml       ← vamos criar este arquivo
├── src/
├── pom.xml
├── Dockerfile
└── ...
```

### 3.2 Criar o arquivo

Crie o arquivo `.github/workflows/deploy.yml` com o conteudo abaixo.

Cada secao esta comentada para explicar o que faz:

```yaml
# ============================================================
# Workflow: Build, Test & Deploy to AWS ECS
# ============================================================
# Dispara automaticamente em pushes na branch main.
# Tambem pode ser disparado manualmente pelo botao no GitHub.
#
# Fluxo:
#   build (compilar) → test (testar) → docker (push ECR) → deploy (run ECS)
# ============================================================

name: Deploy to AWS

# ── Quando esse workflow roda? ──────────────────────────────
on:
  push:
    branches: [main]        # Push direto ou merge de PR na main
  workflow_dispatch:         # Botao "Run workflow" manual no GitHub

# ── Variaveis reutilizaveis ─────────────────────────────────
env:
  AWS_REGION: us-east-1
  ECR_REPOSITORY: roubometro-batch
  ECS_CLUSTER: roubometro
  ECS_TASK_DEFINITION: roubometro-batch
  JAVA_VERSION: '21'

# ── Permissoes OIDC ─────────────────────────────────────────
# Necessario para que o GitHub Actions possa solicitar
# credenciais temporarias da AWS via OIDC.
permissions:
  id-token: write           # Permite criar token OIDC
  contents: read            # Permite checkout do codigo

# ── Jobs ─────────────────────────────────────────────────────
jobs:

  # ┌─────────────────────────────────────────────────────────┐
  # │  JOB 1: BUILD — Compilar o projeto                     │
  # └─────────────────────────────────────────────────────────┘
  build:
    name: Build
    runs-on: ubuntu-latest

    steps:
      # Baixa o codigo do repositorio para o runner
      - name: Checkout do codigo
        uses: actions/checkout@v4

      # Configura o JDK 21 (mesmo do projeto)
      - name: Configurar JDK
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: temurin

      # Cache do Maven para nao baixar dependencias toda vez
      - name: Cache Maven
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-maven-

      # Compila o projeto (sem rodar testes — isso e feito no proximo job)
      - name: Compilar
        run: mvn clean package -DskipTests -q

      # Salva o JAR para os proximos jobs usarem
      # (cada job roda em uma maquina diferente, entao
      #  precisamos "passar" o artefato entre eles)
      - name: Upload do artefato
        uses: actions/upload-artifact@v4
        with:
          name: app-jar
          path: target/*.jar
          retention-days: 1

  # ┌─────────────────────────────────────────────────────────┐
  # │  JOB 2: TEST — Rodar testes unitarios                  │
  # └─────────────────────────────────────────────────────────┘
  test:
    name: Test
    runs-on: ubuntu-latest
    needs: build             # So roda se o build passar

    steps:
      - name: Checkout do codigo
        uses: actions/checkout@v4

      - name: Configurar JDK
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: temurin

      - name: Cache Maven
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-maven-

      # Roda os testes unitarios (22 testes)
      - name: Testes unitarios
        run: mvn test

      # Roda os testes de integracao (13 testes, usa Testcontainers)
      # O runner do GitHub ja tem Docker instalado, entao
      # Testcontainers funciona sem configuracao extra.
      - name: Testes de integracao
        run: mvn verify -Pintegration-tests

  # ┌─────────────────────────────────────────────────────────┐
  # │  JOB 3: DOCKER — Construir imagem e enviar ao ECR      │
  # └─────────────────────────────────────────────────────────┘
  docker:
    name: Push to ECR
    runs-on: ubuntu-latest
    needs: test              # So roda se os testes passarem

    outputs:
      image: ${{ steps.build-image.outputs.image }}

    steps:
      - name: Checkout do codigo
        uses: actions/checkout@v4

      # Baixa o JAR que foi compilado no job "build"
      - name: Download do artefato
        uses: actions/download-artifact@v4
        with:
          name: app-jar
          path: target/

      # ── Autenticacao OIDC com AWS ──
      # Essa action solicita credenciais temporarias da AWS
      # usando o token OIDC do GitHub. Nenhuma chave fixa e
      # armazenada no GitHub.
      - name: Autenticar na AWS (OIDC)
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: ${{ env.AWS_REGION }}

      # Faz login no ECR (necessario para o docker push)
      - name: Login no ECR
        id: ecr-login
        uses: aws-actions/amazon-ecr-login@v2

      # Constroi a imagem Docker e envia para o ECR
      - name: Build e Push da imagem
        id: build-image
        env:
          ECR_REGISTRY: ${{ steps.ecr-login.outputs.registry }}
          IMAGE_TAG: ${{ github.sha }}
        run: |
          docker build -t $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG .
          docker tag $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG $ECR_REGISTRY/$ECR_REPOSITORY:latest
          docker push $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG
          docker push $ECR_REGISTRY/$ECR_REPOSITORY:latest
          echo "image=$ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG" >> $GITHUB_OUTPUT

  # ┌─────────────────────────────────────────────────────────┐
  # │  JOB 4: DEPLOY — Executar task no ECS                   │
  # └─────────────────────────────────────────────────────────┘
  deploy:
    name: Deploy to ECS
    runs-on: ubuntu-latest
    needs: docker            # So roda se o push ao ECR passar
    environment: production  # (opcional) protecao extra no GitHub

    steps:
      - name: Autenticar na AWS (OIDC)
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: ${{ env.AWS_REGION }}

      # Executa a task no ECS Fargate
      # Isso e equivalente ao "aws ecs run-task" que voce
      # fez manualmente no AWS-DEPLOY-GUIDE.md (Passo 6)
      - name: Executar task no ECS
        run: |
          TASK_ARN=$(aws ecs run-task \
            --cluster $ECS_CLUSTER \
            --task-definition $ECS_TASK_DEFINITION \
            --launch-type FARGATE \
            --network-configuration '{
              "awsvpcConfiguration": {
                "subnets": ["${{ secrets.ECS_SUBNET }}"],
                "securityGroups": ["${{ secrets.ECS_SECURITY_GROUP }}"],
                "assignPublicIp": "ENABLED"
              }
            }' \
            --query "tasks[0].taskArn" \
            --output text)

          echo "Task iniciada: $TASK_ARN"
          echo "Acompanhe os logs em: CloudWatch > /ecs/roubometro-batch"
```

> **Sobre as tags da imagem**: cada push gera uma imagem tagueada com o SHA do commit (ex: `a1b2c3d`). Isso permite rastrear exatamente qual versao do codigo esta rodando. A tag `latest` tambem e atualizada para facilitar o uso no EventBridge (agendamento).

---

## Passo 4: Commit e primeiro deploy

### 4.1 Criar o diretorio e commitar

```bash
mkdir -p .github/workflows
# (copie o conteudo do YAML acima para .github/workflows/deploy.yml)
git add .github/workflows/deploy.yml
git commit -m "ci: add GitHub Actions workflow for AWS deploy"
git push origin main
```

### 4.2 Acompanhar a execucao

1. Abra o repositorio no GitHub
2. Clique na aba **Actions**
3. Voce vera o workflow "Deploy to AWS" rodando
4. Clique nele para ver os jobs: **Build → Test → Push to ECR → Deploy to ECS**

Cada job mostra um check verde (sucesso) ou vermelho (falha). Clique em um job para ver os logs detalhados de cada step.

### 4.3 Resultado esperado

```
✅ Build        (compilou o JAR)
✅ Test         (22 unit + 13 integration)
✅ Push to ECR  (imagem enviada ao ECR)
✅ Deploy to ECS (task executada no Fargate)
```

---

## Passo 5: Executar manualmente (workflow_dispatch)

Alem do deploy automatico em cada push, voce pode disparar o workflow manualmente:

1. Va em **Actions > Deploy to AWS**
2. Clique no botao **Run workflow**
3. Selecione a branch `main`
4. Clique **Run workflow**

Isso e util para re-deploy sem precisar fazer um commit.

---

## Como tudo se conecta

```
┌──────────────┐
│  Desenvolvedor│
│  (voce)      │
└──────┬───────┘
       │ git push origin main
       ▼
┌──────────────┐     ┌─────────────────────────────────────────────┐
│   GitHub     │     │           GitHub Actions (Runner)            │
│   (repo)     │────→│                                             │
│              │     │  1. mvn package        (compila)            │
│              │     │  2. mvn test           (testa)              │
│              │     │  3. mvn verify         (integracao)         │
│              │     │  4. docker build+push  (imagem → ECR)       │
│              │     │  5. aws ecs run-task   (executa no Fargate) │
└──────────────┘     └────────────────┬────────────────────────────┘
                                      │ OIDC (credenciais temporarias)
                                      ▼
                     ┌──────────────────────────────────────────────┐
                     │                    AWS                       │
                     │                                              │
                     │  ECR ← imagem Docker                        │
                     │  ECS Fargate ← executa container            │
                     │    └→ Secrets Manager (credenciais DB)      │
                     │    └→ CloudWatch Logs (logs)                │
                     │    └→ MySQL externo (Locaweb)               │
                     │                                              │
                     │  EventBridge (cron semanal, independente)    │
                     └──────────────────────────────────────────────┘
```

> **EventBridge vs GitHub Actions**: o EventBridge continua rodando o agendamento semanal (Passo 7 do AWS-DEPLOY-GUIDE). O GitHub Actions cuida do **deploy** (atualizar a imagem). Sao complementares.

---

## Resumo dos recursos criados

| Recurso | Onde | Para que |
|---------|------|----------|
| OIDC Identity Provider | AWS IAM | Permite GitHub autenticar sem chaves fixas |
| Role `github-actions-roubometro-batch` | AWS IAM | Permissoes do GitHub Actions na AWS |
| Secret `AWS_ACCOUNT_ID` | GitHub Secrets | Account ID da AWS |
| Secret `AWS_ROLE_ARN` | GitHub Secrets | ARN da role OIDC |
| Secret `ECS_SUBNET` | GitHub Secrets | Subnet para o Fargate |
| Secret `ECS_SECURITY_GROUP` | GitHub Secrets | Security Group para o Fargate |
| `.github/workflows/deploy.yml` | Repositorio | Definicao do workflow |

---

## Troubleshooting

### Erro: `Not authorized to perform: sts:AssumeRoleWithWebIdentity`

**Causa**: a trust policy da role nao esta configurada corretamente para o seu repositorio.

**Solucao**: verifique se o campo `sub` na trust policy corresponde ao seu repositorio:
```json
"token.actions.githubusercontent.com:sub": "repo:SEU-USUARIO/roubometro-batch:ref:refs/heads/main"
```

### Erro: `Error: Could not assume role with OIDC`

**Causa**: o Identity Provider OIDC nao foi criado no IAM, ou o audience esta errado.

**Solucao**: no console AWS, va em **IAM > Identity providers** e verifique se `token.actions.githubusercontent.com` existe com audience `sts.amazonaws.com`.

### Erro: `denied: Your authorization token has expired`

**Causa**: o login no ECR expirou (tokens duram 12h, mas o job e rapido).

**Solucao**: verifique se o step de login no ECR esta **antes** do docker push.

### Erro: `CannotPullContainerError` no ECS apos deploy

**Causa**: a imagem foi enviada ao ECR mas a task definition aponta para uma tag que nao existe.

**Solucao**: confirme que o push ao ECR incluiu a tag `latest` (o workflow acima faz isso automaticamente).

### Os testes de integracao falham no GitHub Actions

**Causa**: Testcontainers precisa de Docker, que pode ter problemas de permissao no runner.

**Solucao**: runners `ubuntu-latest` do GitHub ja tem Docker instalado. Se falhar, adicione:
```yaml
env:
  TESTCONTAINERS_RYUK_DISABLED: true
```

---

## Proximos passos

1. **Branch protection**: em **Settings > Branches > Branch protection rules**, exija que o workflow passe antes de permitir merge na `main`
2. **Deploy somente em tags**: para deploys mais controlados, troque o trigger de `push` para `tags`:
   ```yaml
   on:
     push:
       tags: ['v*']   # Dispara somente em tags como v1.0.0
   ```
3. **Notificacao Slack/Discord**: adicione um step ao final do workflow para notificar o time
4. **Cache Docker layers**: use `docker/build-push-action@v6` com cache para builds mais rapidos
5. **Ambiente de staging**: crie um segundo ECS cluster e duplique o workflow para testes pre-producao
