# Implementação no CPI — Transporte automatizado de iFlows

**Guia de construção · versão 1.0 · 29 de outubro de 2026**

Este guia implementa, dentro do SAP Integration Suite (CPI), a arquitetura descrita em [`../ARQUITETURA-TRANSPORTE-CPI.md`](../ARQUITETURA-TRANSPORTE-CPI.md): o desenvolvedor abre um chamado no ServiceNow, e o CPI gera a transport request, leva pela rota do cTMS, faz o deploy em QAS e devolve o resultado ao chamado. O próprio ServiceNow fecha o chamado.

Tudo o que vai dentro dos iFlows está aqui: passos, configuração de cada adapter, parâmetros, Security Material e os scripts Groovy completos (também em [`scripts/`](scripts/)).

---

## Sumário

1. [O que muda e o que não muda](#1-o-que-muda-e-o-que-não-muda)
2. [Visão dos iFlows](#2-visão-dos-iflows)
3. [Pré-requisitos](#3-pré-requisitos)
4. [Security Material e Keystore](#4-security-material-e-keystore)
5. [Pacote e scripts](#5-pacote-e-scripts)
6. [Parâmetros externalizados](#6-parâmetros-externalizados)
7. [IF_TRP_04_SnowUpdate](#7-if_trp_04_snowupdate)
8. [IF_TRP_03_SnowToken](#8-if_trp_03_snowtoken)
9. [IF_TRP_02_AdvanceTicket](#9-if_trp_02_advanceticket)
10. [IF_TRP_01_Orchestrator](#10-if_trp_01_orchestrator)
11. [Ordem de deploy e testes](#11-ordem-de-deploy-e-testes)
12. [Operação](#12-operação)
13. [Pontos a confirmar](#13-pontos-a-confirmar)
14. [Anexo — scripts Groovy completos](#14-anexo--scripts-groovy-completos)

---

## 1. O que muda e o que não muda

| Pergunta | Resposta |
| --- | --- |
| O Automation Pilot continua? | **Não, no caminho principal.** O CPI assume a orquestração. O catálogo `cpitrans` fica como referência e laboratório |
| A rota de transporte continua? | **Sim.** cTMS com os nós `DEV_CAS` → `QAS_CAS` (após a migração para o Content Agent, ver [`../MIGRACAO-CPI.md`](../MIGRACAO-CPI.md)) |
| Precisa de API Management? | **Não.** O API Management serve para expor e proteger APIs para quem chama de fora. Aqui o CPI só chama APIs de outros sistemas, sem receber nada de fora |
| Quem fecha o chamado? | **O ServiceNow**, por business rule, quando o CPI grava `SUCCESS` ou `FAILED` |
| Como o CPI fala com o ServiceNow? | OAuth 2.0 com **JWT assinado por certificado** (grant `jwt-bearer`), um dos estilos de conexão do Hub de Arquitetura |
| Quem chama quem? | Só o CPI chama o ServiceNow (modelo pull). O ServiceNow não chama o CPI |

Fluxo resumido:

```text
ServiceNow (dev abre chamado, status PENDING)
   ▲   │
   │   └── CPI busca a cada 2 min (IF_TRP_01)
   │         └── para cada chamado, avança 1 passo (IF_TRP_02):
   │               PENDING    → cria export no Content Agent DEV
   │               EXPORTING  → export pronto? importa SÓ essa request no nó QAS_CAS (cTMS)
   │               IMPORTING  → import pronto? dispara deploy no CPI QAS
   │               DEPLOYING  → iFlow STARTED na versão pedida? SUCCESS
   └── CPI grava estado + notas no chamado (IF_TRP_04) → ServiceNow fecha o chamado
```

---

## 2. Visão dos iFlows

| iFlow | Gatilho | Função | Chama |
| --- | --- | --- | --- |
| `IF_TRP_01_Orchestrator` | Timer (a cada 2 min) | Obtém o token, lista os chamados ativos, envia um a um ao `IF_TRP_02` | `IF_TRP_03`, ServiceNow (GET), `IF_TRP_02` |
| `IF_TRP_02_AdvanceTicket` | ProcessDirect `/trp/advance` | Avança um passo da máquina de estados do chamado | Content Agent, cTMS, CPI QAS, `IF_TRP_04` |
| `IF_TRP_03_SnowToken` | ProcessDirect `/trp/snow-token` | Assina o JWT com a chave do Keystore e troca pelo access token | ServiceNow (`oauth_token.do`) |
| `IF_TRP_04_SnowUpdate` | ProcessDirect `/trp/snow-update` | PATCH no chamado (estado, IDs, notas) | ServiceNow (Table API) |

Regras gerais:

- **Propriedades não atravessam o ProcessDirect.** O token do ServiceNow vai no header `X-Trp-Snow-Token`, e cada iFlow o converte em propriedade e esvazia o header no primeiro passo.
- **Cada adapter HTTP envia só os headers listados** em *Request Headers*. Nunca use `*`: isso evita vazar o token para outro sistema.
- **Nenhum passo espera dentro do iFlow.** As consultas de status acontecem no ciclo seguinte do timer.
- **Log:** nível *Info* em produção. Use *Trace* só em teste e por pouco tempo, porque o trace grava corpos que contêm tokens.

---

## 3. Pré-requisitos

### 3.1 Tenant de orquestração

Fase 1 (DEV → QAS): **tenant CPI de QAS** (decisão D1 da arquitetura). Antes da fase de produção, mover para um tenant dedicado.

Roles do seu usuário no tenant: desenvolver e fazer deploy de iFlows, e administrar Security Material e Keystore (role collections `PI_Integration_Developer` e `PI_Administrator` ou equivalentes do banco).

### 3.2 BTP — service keys dedicadas ao orquestrador

Crie keys **novas**, só para o CPI de orquestração (não reaproveite as das destinations):

| Key | Instância | Subaccount | Vai para |
| --- | --- | --- | --- |
| `key-cas-dev-trp` | Content Agent Service `standard` (`cas-dev`) | DEV | `TRP_CAS_DEV` |
| `key-tms-trp` | Cloud Transport Management `standard` (`CTM_Service_Key`) | TMS | `TRP_CTMS` |
| `key-pi-api-qas-deploy` | Process Integration Runtime `api` com `WorkspaceArtifactsDeploy` e `MonitoringDataRead` | QAS | `TRP_CPI_QAS` |

### 3.3 ServiceNow

Do lado do ServiceNow (detalhe na seção 11 da arquitetura), além do item de catálogo:

1. **Colunas novas em `sc_req_item`.** As variáveis do item de catálogo não são colunas da tabela, então o flow copia os valores para colunas que a Table API lê direto:

| Coluna | Tipo | Preenchida por |
| --- | --- | --- |
| `u_cpi_artifact_id` | String (100) | Flow, a partir da variável `u_artifact_id` |
| `u_cpi_artifact_version` | String (20) | Flow, a partir de `u_artifact_version` |
| `u_cpi_package_id` | String (100) | Flow, a partir de `u_package_id` |
| `u_cpi_transport_status` | Choice | Flow (`PENDING`) e CPI |
| `u_cpi_stage_since` | Date/Time | Flow (agora) e CPI |
| `u_cpi_retries` | Integer | CPI |
| `u_cpi_operation_id` | String (100) | CPI |
| `u_cpi_tr_id` | String (40) | CPI |
| `u_cpi_action_id` | String (40) | CPI |
| `u_cpi_message` | String (255) | CPI (motivo da falha ou resumo do sucesso) |

   Valores de `u_cpi_transport_status`: `PENDING`, `EXPORTING`, `WAITING_TR`, `IMPORTING`, `DEPLOYING`, `SUCCESS`, `FAILED`.

2. **Flow (Service Catalog trigger):** copia as variáveis para as colunas acima, grava `u_cpi_transport_status = PENDING` e `u_cpi_stage_since = agora`. Nenhuma chamada REST.
3. **Business rule** (after update em `sc_req_item`, quando `u_cpi_transport_status` muda): `SUCCESS` → State = Closed Complete; `FAILED` → grupo de suporte de integração + notificação ao solicitante.
4. **Cliente OAuth JWT:** System OAuth → Application Registry → *Create an OAuth JWT API endpoint for external clients*; *User Field* = `user_name`; JWT Verifier Map com o `kid` e o certificado gerado no CPI (4.2).
5. **Usuário de integração** `svc_cpi_transport`: *Web service access only*; leitura no item de catálogo e escrita só nas colunas `u_cpi_*` e em `work_notes`.

### 3.4 Transporte

A migração para o Content Agent (Fases 0 a 4 de [`../MIGRACAO-CPI.md`](../MIGRACAO-CPI.md)) precisa estar concluída para o modo `EXPORT_MODE = API`. Enquanto não estiver, ou se a API de export não for confirmada, use `EXPORT_MODE = MANUAL_TR` (contingência: o dev clica em Transport com o número do chamado na descrição).

---

## 4. Security Material e Keystore

Tudo em **Monitor → Integrations and APIs → Manage Security** do tenant de orquestração.

### 4.1 Credenciais

| Nome | Tipo | Campos |
| --- | --- | --- |
| `TRP_SNOW_CLIENT` | User Credentials | User = client_id do cliente OAuth do ServiceNow; Password = client_secret |
| `TRP_CAS_DEV` | OAuth2 Client Credentials | Token Service URL = `uaa.url` da `key-cas-dev-trp` + `/oauth/token`; Client ID / Secret da key; Client Authentication = Send as Request Header; Content Type = `application/x-www-form-urlencoded` |
| `TRP_CTMS` | OAuth2 Client Credentials | Mesmo padrão, com a `key-tms-trp` |
| `TRP_CPI_QAS` | OAuth2 Client Credentials | Mesmo padrão, com a `key-pi-api-qas-deploy` (o `tokenurl` já vem completo na key) |

### 4.2 Par de chaves do JWT

Gere a chave **dentro do CPI**, assim a chave privada nunca sai do tenant:

1. **Keystore → Create → Key Pair**.
2. Alias `trp_snow_jwt`, algoritmo RSA 2048, validade conforme a política do banco (por exemplo 1 ano), Common Name `cpi-transporte-servicenow`.
3. Selecione o par → **Download → Public Certificate**.
4. Entregue o certificado (`.cer`) ao time do ServiceNow, que o cadastra no `sys_certificate` e no JWT Verifier Map com o `kid` combinado (por exemplo `trp-cpi-2026`).

Se a Segurança exigir HSM (decisão D2-A), a assinatura sai do script e vira uma chamada ao HSM. Ver seção 13.

---

## 5. Pacote e scripts

1. **Design → Create package**: nome `Transporte CPI (Orquestração)`, technical name `Z_TRANSPORTE_CPI`.
2. Crie os 4 iFlows com os nomes da seção 2.
3. Em cada iFlow: **Resources → Add → Script → Upload from file system** e envie o script correspondente:

| iFlow | Script |
| --- | --- |
| `IF_TRP_01_Orchestrator` | [`scripts/TRP_01_Orchestrator.groovy`](scripts/TRP_01_Orchestrator.groovy) |
| `IF_TRP_02_AdvanceTicket` | [`scripts/TRP_02_AdvanceTicket.groovy`](scripts/TRP_02_AdvanceTicket.groovy) |
| `IF_TRP_03_SnowToken` | [`scripts/TRP_03_SnowToken.groovy`](scripts/TRP_03_SnowToken.groovy) |
| `IF_TRP_04_SnowUpdate` | [`scripts/TRP_04_SnowUpdate.groovy`](scripts/TRP_04_SnowUpdate.groovy) |

Cada arquivo tem várias funções. Em cada passo **Groovy Script** do iFlow, escolha o arquivo e preencha **Script Function** com o nome da função indicado nas tabelas abaixo.

Os scripts foram testados fora do CPI com o Groovy 2.4 (o runtime padrão do CPI) e classes que simulam as APIs do CPI: 41 verificações, cobrindo assinatura e claims do JWT, todas as transições de estado, prazos, contingência e classificação de erros. Não usam recurso específico de versão, mas não foram executados no Groovy 4.

---

## 6. Parâmetros externalizados

Nos passos *Content Modifier → Exchange Property → Source Type = Constant*, use o valor `{{NOME}}`. O CPI cria o parâmetro externalizado e você preenche em **Configure** antes do deploy.

> Não use `${...}` nos valores dos parâmetros: o CPI interpreta isso como expressão. Os modelos usam marcadores `#NOME#`, trocados pelo script.

### 6.1 ServiceNow

| Parâmetro | iFlow | Exemplo / valor |
| --- | --- | --- |
| `SNOW_HOST` | 01, 04 | `bradesco.service-now.com` |
| `SNOW_TABLE` | 01, 04 | `sc_req_item` |
| `SNOW_CAT_ITEM_SYSID` | 01 | sys_id do item "Transporte CPI DEV → QAS" |
| `SNOW_LIMIT` | 01 | `20` |
| `ACTIVE_STATES` | 01 | `PENDING,EXPORTING,WAITING_TR,IMPORTING,DEPLOYING` |
| `TRP_ENABLED` | 01 | `true` (desliga tudo com `false`) |
| `SNOW_TOKEN_URL` | 03 | `https://bradesco.service-now.com/oauth_token.do` (ou o endpoint do IdP corporativo) |
| `SNOW_CLIENT_CREDENTIAL` | 03 | `TRP_SNOW_CLIENT` |
| `SNOW_JWT_KEY_ALIAS` | 03 | `trp_snow_jwt` |
| `SNOW_JWT_KID` | 03 | Key ID do JWT Verifier Map |
| `SNOW_JWT_SUB` | 03 | `svc_cpi_transport` (valor do *User Field*) |
| `SNOW_JWT_ISS` / `SNOW_JWT_AUD` | 03 | Vazio = client_id (padrão do ServiceNow) |
| `SNOW_JWT_TTL_SECONDS` | 03 | `300` |
| `SNOW_ASSERTION_PARAM` | 03 | `assertion` (jwt-bearer do ServiceNow) ou `client_assertion` |
| `SNOW_TOKEN_SCOPE` | 03 | Vazio, ou o escopo combinado com o time do ServiceNow |

### 6.2 Content Agent (export) — **a confirmar na documentação oficial**

| Parâmetro | Exemplo provisório | O que é |
| --- | --- | --- |
| `EXPORT_MODE` | `API` ou `MANUAL_TR` | `MANUAL_TR` = contingência sem API de export |
| `CAS_DEV_URL` | URL da `key-cas-dev-trp` | Base da API do Content Agent |
| `CAS_EXPORT_PATH` | `/CONFIRMAR/export` | Caminho do POST que cria o export |
| `CAS_EXPORT_BODY_TEMPLATE` | `{"contentType":"CloudIntegration","resources":[{"id":"#PACKAGE_ID#"}],"exportMode":"CTMS","description":"#TICKET#"}` | Corpo do POST; marcadores `#PACKAGE_ID#`, `#ARTIFACT_ID#`, `#VERSION#`, `#TICKET#` |
| `CAS_OPID_JSONPATH` | `id` | Onde está o ID da operação na resposta |
| `CAS_STATUS_PATH` | `/CONFIRMAR/export/#OPERATION_ID#` | Caminho do GET de status |
| `CAS_STATUS_JSONPATH` | `status` | Campo de status na resposta |
| `CAS_DONE_VALUES` | `COMPLETED,SUCCEEDED` | Status de sucesso |
| `CAS_FAILED_VALUES` | `FAILED,ERROR` | Status de falha |
| `CAS_TRID_JSONPATH` | `transportRequest.id` | Onde está o ID da transport request |
| `CRED_CAS_DEV` | `TRP_CAS_DEV` | Credencial do adapter |

### 6.3 cTMS

| Parâmetro | Exemplo | Observação |
| --- | --- | --- |
| `CTMS_URI` | `uri` da `key-tms-trp` | |
| `CTMS_QAS_NODE_ID` | `42` | ID numérico do nó `QAS_CAS` |
| `CTMS_NAMED_USER` | `cpi-orquestracao` | Aparece no histórico do cTMS |
| `CTMS_IMPORT_PATH` | `/v2/nodes/#NODE_ID#/transportRequests/import` | Import por ID (a conferir) |
| `CTMS_ACTIONID_JSONPATH` | `actionId` | A conferir |
| `CTMS_ACTION_PATH` | `/v2/actions/#ACTION_ID#` | A conferir |
| `CTMS_ACTION_STATUS_JSONPATH` | `status` | A conferir |
| `CTMS_QUEUE_PATH` | `/v2/nodes/#NODE_ID#/transportRequests` | Só na contingência |
| `CTMS_QUEUE_QUERY` | `status=initial` | Só na contingência |
| `CRED_CTMS` | `TRP_CTMS` | |

### 6.4 CPI QAS e controle

| Parâmetro | Exemplo |
| --- | --- |
| `CPI_QAS_URL` | `url` da `key-pi-api-qas-deploy` (ex.: `https://<tenant>.it-cpi<nnn>.cfapps.br10.hana.ondemand.com`) |
| `CRED_CPI_QAS` | `TRP_CPI_QAS` |
| `TIMEOUT_EXPORT_MIN` | `15` |
| `TIMEOUT_WAITING_TR_MIN` | `60` |
| `TIMEOUT_IMPORT_MIN` | `15` |
| `TIMEOUT_DEPLOY_MIN` | `10` |
| `MAX_RETRIES` | `5` |

---

## 7. IF_TRP_04_SnowUpdate

Atualiza o chamado. É o primeiro a ser construído, porque os outros dependem dele.

**Sender:** ProcessDirect, Address `/trp/snow-update`.

| # | Passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 1 | Config | Content Modifier | Exchange Properties (Constant): `SNOW_HOST` = `{{SNOW_HOST}}`; `SNOW_TABLE` = `{{SNOW_TABLE}}` |
| 2 | Preparar PATCH | Groovy Script | `TRP_04_SnowUpdate.groovy` · função `prepareUpdate` |
| 3 | PATCH no chamado | Request Reply → **HTTP** | Address: `https://${property.SNOW_HOST}/api/now/table/${property.SNOW_TABLE}/${property.SYS_ID}` · Method: `PATCH` · Authentication: None · Request Headers: `Authorization\|Content-Type\|Accept` · Response Headers: vazio · Throw Exception on Failure: ✔ · Timeout: 60000 |
| 4 | Limpar | Content Modifier | Message Body vazio (não devolve dados do ServiceNow a quem chamou) |
| — | Fim | End | |

Entrada esperada: header `X-Trp-Snow-Token` e corpo `{"sys_id":"...","fields":{...}}`.

---

## 8. IF_TRP_03_SnowToken

Monta e assina o JWT e troca pelo access token do ServiceNow.

**Sender:** ProcessDirect, Address `/trp/snow-token`.

| # | Passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 1 | Config | Content Modifier | Exchange Properties (Constant): `SNOW_TOKEN_URL`, `SNOW_CLIENT_CREDENTIAL`, `SNOW_JWT_KEY_ALIAS`, `SNOW_JWT_KID`, `SNOW_JWT_SUB`, `SNOW_JWT_ISS`, `SNOW_JWT_AUD`, `SNOW_JWT_TTL_SECONDS`, `SNOW_ASSERTION_PARAM`, `SNOW_TOKEN_SCOPE`, cada um = `{{NOME}}` |
| 2 | Assinar JWT | Groovy Script | `TRP_03_SnowToken.groovy` · função `buildTokenRequest` |
| 3 | Pedir token | Request Reply → **HTTP** | Address: `${property.SNOW_TOKEN_URL}` · Method: `POST` · Authentication: None · Request Headers: `Content-Type\|Accept` · Throw Exception on Failure: ✔ · Timeout: 30000 |
| 4 | Extrair token | Groovy Script | `TRP_03_SnowToken.groovy` · função `parseTokenResponse` |
| — | Fim | End | Devolve `{"access_token":"...","expires_in":n}` |

Cuidados:

- Log level *Info*. O corpo do passo 3 contém client_secret e JWT.
- O token dura cerca de 30 minutos e não tem refresh; cada ciclo do `IF_TRP_01` pede um novo.
- Relógio: o ServiceNow rejeita JWT com `iat`/`exp` fora do horário. O script usa `nbf` = agora − 30 s para tolerar pequenas diferenças.

---

## 9. IF_TRP_02_AdvanceTicket

O coração da automação: recebe um chamado e executa **um** passo da máquina de estados.

**Sender:** ProcessDirect, Address `/trp/advance`.

### 9.1 Processo principal

| # | Passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 1 | Config | Content Modifier | Exchange Properties (Constant), cada uma = `{{NOME}}`: `EXPORT_MODE`, `CAS_DEV_URL`, `CAS_EXPORT_PATH`, `CAS_EXPORT_BODY_TEMPLATE`, `CAS_OPID_JSONPATH`, `CAS_STATUS_PATH`, `CAS_STATUS_JSONPATH`, `CAS_DONE_VALUES`, `CAS_FAILED_VALUES`, `CAS_TRID_JSONPATH`, `CTMS_URI`, `CTMS_QAS_NODE_ID`, `CTMS_NAMED_USER`, `CTMS_IMPORT_PATH`, `CTMS_ACTIONID_JSONPATH`, `CTMS_ACTION_PATH`, `CTMS_ACTION_STATUS_JSONPATH`, `CTMS_QUEUE_PATH`, `CPI_QAS_URL`, `TIMEOUT_EXPORT_MIN`, `TIMEOUT_WAITING_TR_MIN`, `TIMEOUT_IMPORT_MIN`, `TIMEOUT_DEPLOY_MIN`, `MAX_RETRIES` |
| 2 | Preparar | Groovy Script | função `prepare` |
| 3 | Estado | **Router** | Rotas na ordem da tabela 9.2 (Expression Type: Non-XML) |

### 9.2 Rotas do Router "Estado"

A ordem importa: o CPI avalia de cima para baixo.

| Ordem | Rota | Condição | Passos da rota |
| --- | --- | --- | --- |
| 1 | Prazo vencido | `${property.TIMED_OUT} = 'true'` | Script `markTimeout` → Process Call `LIP_UpdateTicket` → End |
| 2 | Campos inválidos | `${property.STATE} = 'PENDING' and ${property.VALIDATION_ERROR} != ''` | Script `markValidationError` → Process Call `LIP_UpdateTicket` → End |
| 3 | Export por API | `${property.STATE} = 'PENDING' and ${property.EXPORT_MODE} = 'API'` | Script `buildExportRequest` → **HTTP POST** export (9.4-a) → Script `handleExportResponse` → Process Call `LIP_UpdateTicket` → End |
| 4 | Contingência | `${property.STATE} = 'PENDING' and ${property.EXPORT_MODE} = 'MANUAL_TR'` | Script `markWaitingTr` → Process Call `LIP_UpdateTicket` → End |
| 5 | Exportando | `${property.STATE} = 'EXPORTING'` | **HTTP GET** status do export (9.4-b) → Script `evaluateExportStatus` → Router "Próximo" (9.3) |
| 6 | Aguardando request | `${property.STATE} = 'WAITING_TR'` | **HTTP GET** fila do nó (9.4-c) → Script `findTrByTicket` → Router "Próximo" (9.3) |
| 7 | Importando | `${property.STATE} = 'IMPORTING'` | **HTTP GET** ação do cTMS (9.4-d) → Script `evaluateImport` → Router "Próximo" (9.3) |
| 8 | Em deploy | `${property.STATE} = 'DEPLOYING'` | **HTTP GET** runtime QAS (9.4-f) → Script `evaluateDeploy` → Process Call `LIP_UpdateTicket` → End |
| Default | Estado final | — | End (nada a fazer) |

### 9.3 Router "Próximo" (depois das rotas 5, 6 e 7)

Cada uma das três rotas tem o seu Router "Próximo" (o CPI não junta rotas):

| Condição | Passos |
| --- | --- |
| `${property.NEXT} = 'START_IMPORT'` | Process Call `LIP_StartImport` → Process Call `LIP_UpdateTicket` → End |
| `${property.NEXT} = 'START_DEPLOY'` | Process Call `LIP_StartDeploy` → Process Call `LIP_UpdateTicket` → End |
| Default | Process Call `LIP_UpdateTicket` → End |

### 9.4 Chamadas HTTP

Em todas: **Request Reply → HTTP**, *Request Headers* = `Content-Type|Accept`, *Response Headers* vazio, *Timeout* 60000, *Authentication* = **OAuth2 Client Credentials** com a credencial indicada.

| Ref. | Chamada | Method | Address | Query | Credencial | Throw Exception on Failure |
| --- | --- | --- | --- | --- | --- | --- |
| a | Criar export | POST | `${property.CAS_EXPORT_URL}` | — | `{{CRED_CAS_DEV}}` | ✔ |
| b | Status do export | GET | `${property.CAS_STATUS_URL}` | — | `{{CRED_CAS_DEV}}` | ✔ |
| c | Fila do nó QAS | GET | `${property.CTMS_QUEUE_URL}` | `{{CTMS_QUEUE_QUERY}}` | `{{CRED_CTMS}}` | ✔ |
| d | Status do import | GET | `${property.CTMS_ACTION_URL}` | — | `{{CRED_CTMS}}` | ✔ |
| e | Import por ID | POST | `${property.CTMS_IMPORT_URL}` | — | `{{CRED_CTMS}}` | ✔ |
| f | Status no runtime | GET | `${property.CPI_QAS_URL}/api/v1/IntegrationRuntimeArtifacts(%27${property.ARTIFACT_ID}%27)` | — | `{{CRED_CPI_QAS}}` | **✘** (404 no início é normal; o script trata) |
| g | Deploy | POST | `${property.CPI_QAS_URL}/api/v1/DeployIntegrationDesigntimeArtifact` | `Id=%27${property.ARTIFACT_ID}%27&Version=%27active%27` | `{{CRED_CPI_QAS}}` | ✔ |

Nas chamadas `f` e `g`, antes do HTTP, um Content Modifier define o header `Accept` = `application/json` (as outras recebem os headers do script).

### 9.5 Local Integration Processes

**`LIP_StartImport`**

| # | Passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 1 | Corpo do import | Groovy Script | função `buildImportRequest` |
| 2 | Import por ID | Request Reply → HTTP | Chamada **e** da 9.4 |
| 3 | Gravar ação | Groovy Script | função `handleImportResponse` |

**`LIP_StartDeploy`**

| # | Passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 1 | Header Accept | Content Modifier | Header `Accept` = `application/json` |
| 2 | Deploy | Request Reply → HTTP | Chamada **g** da 9.4 |
| 3 | Gravar estado | Groovy Script | função `handleDeployResponse` |

**`LIP_UpdateTicket`**

| # | Passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 1 | Precisa atualizar? | Router | Rota 1: `${property.UPDATE} = 'true'` → passos 2 e 3; Default → End |
| 2 | Montar pedido | Groovy Script | função `buildUpdateRequest` |
| 3 | Atualizar chamado | Request Reply → **ProcessDirect** | Address `/trp/snow-update` |

### 9.6 Exception Subprocess

| # | Passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 1 | Início | Error Start Event | |
| 2 | Classificar erro | Groovy Script | função `handleError` (transitório → só soma tentativa; definitivo ou tentativas esgotadas → `FAILED`) |
| 3 | Atualizar chamado | Request Reply → ProcessDirect | Address `/trp/snow-update` |
| 4 | Fim | **End Message** | O chamador (`IF_TRP_01`) segue para o próximo chamado |

Se o próprio ServiceNow estiver fora do ar, o passo 3 falha, o chamado fica no mesmo estado e é retomado no ciclo seguinte.

---

## 10. IF_TRP_01_Orchestrator

**Sender:** Timer.

| # | Passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 0 | Timer | Timer Start Event | *Schedule to Recur* · Recur Daily · Every 2 minutes · Between 07:00 e 22:00 · Time Zone `America/Sao_Paulo` (ajuste à janela de trabalho) |
| 1 | Config | Content Modifier | Exchange Properties (Constant): `TRP_ENABLED`, `SNOW_HOST`, `SNOW_TABLE`, `SNOW_CAT_ITEM_SYSID`, `ACTIVE_STATES`, `SNOW_LIMIT`, cada um = `{{NOME}}` |
| 2 | Ligado? | Router | Rota 1: `${property.TRP_ENABLED} != 'true'` → End; Default → segue |
| 3 | Token | Request Reply → ProcessDirect | Address `/trp/snow-token` (corpo vazio) |
| 4 | Guardar token | Groovy Script | `TRP_01_Orchestrator.groovy` · função `extractToken` |
| 5 | Headers do GET | Content Modifier | Header `Authorization` = Expression `Bearer ${property.SNOW_TOKEN}`; header `Accept` = Constant `application/json` |
| 6 | Buscar chamados | Request Reply → **HTTP** | Address: `https://${property.SNOW_HOST}/api/now/table/${property.SNOW_TABLE}` · Method: GET · Authentication: None · Query (abaixo) · Request Headers: `Authorization\|Accept` · Throw Exception on Failure: ✔ · Timeout: 60000 |
| 7 | Limpar headers | Content Modifier | Delete header `Authorization` |
| 8 | Um por linha | Groovy Script | função `ticketsToLines` |
| 9 | Há chamados? | Router | Rota 1: `${property.TICKET_COUNT} = '0'` → End; Default → segue |
| 10 | Separar | **Iterating Splitter** | Expression Type: **Line Break** · Grouping: 1 · Streaming: ✘ · Parallel Processing: ✘ · Stop On Exception: **✘** |
| 11 | Preparar chamado | Groovy Script | função `lineToRequest` |
| 12 | Avançar chamado | Request Reply → ProcessDirect | Address `/trp/advance` |
| — | Fim | End | |

Query do passo 6 (uma linha, sem espaços):

```text
sysparm_query=cat_item=${property.SNOW_CAT_ITEM_SYSID}^u_cpi_transport_statusIN${property.ACTIVE_STATES}^ORDERBYsys_created_on&sysparm_fields=sys_id,number,u_cpi_transport_status,u_cpi_stage_since,u_cpi_retries,u_cpi_artifact_id,u_cpi_artifact_version,u_cpi_package_id,u_cpi_operation_id,u_cpi_tr_id,u_cpi_action_id&sysparm_display_value=false&sysparm_exclude_reference_link=true&sysparm_limit=${property.SNOW_LIMIT}
```

`sysparm_display_value=false` faz as datas virem em UTC, no formato que o `IF_TRP_02` usa para calcular os prazos.

**Exception Subprocess:** Error Start Event → End Message. Se o token ou a busca falharem, o ciclo termina e o próximo tenta de novo; os chamados não mudam de estado.

---

## 11. Ordem de deploy e testes

### 11.1 Ordem de deploy

1. Security Material e Keystore (seção 4) — e o certificado cadastrado no ServiceNow.
2. `IF_TRP_04_SnowUpdate`
3. `IF_TRP_03_SnowToken`
4. `IF_TRP_02_AdvanceTicket`
5. `IF_TRP_01_Orchestrator` com `TRP_ENABLED = false`

### 11.2 Testes

| # | Teste | Como | Resultado esperado |
| --- | --- | --- | --- |
| 1 | Token | Ligar `TRP_ENABLED = true` sem chamados abertos; log *Trace* por 10 min | `IF_TRP_03` com sucesso; `IF_TRP_01` termina no Router "Há chamados?" |
| 2 | Validação | Chamado com ID técnico inválido | `FAILED` com "Campos inválidos" e chamado atribuído ao suporte |
| 3 | Contingência | `EXPORT_MODE = MANUAL_TR`; abrir chamado; clicar em Transport com o nº do chamado na descrição | `WAITING_TR` → `IMPORTING` → `DEPLOYING` → `SUCCESS`; chamado fechado |
| 4 | Export por API | `EXPORT_MODE = API` (depois da confirmação, seção 13) | `PENDING` → `EXPORTING` → ... → `SUCCESS` |
| 5 | Versão errada | Pedir uma versão que não existe em DEV | `FAILED` no export (ou prazo vencido) com o motivo |
| 6 | iFlow com erro | iFlow de teste com endpoint inválido | `FAILED` com "ficou em ERROR em QAS" |
| 7 | Prazo | Reduzir `TIMEOUT_DEPLOY_MIN` para 1 num teste | `FAILED` por prazo vencido |
| 8 | Liga/desliga | `TRP_ENABLED = false` | Nenhum chamado processado |

Volte o log para *Info* depois dos testes.

---

## 12. Operação

| Situação | O que fazer |
| --- | --- |
| Parar tudo | `TRP_ENABLED = false` no `IF_TRP_01` e redeploy |
| Achar a execução de um chamado | Monitor de mensagens, filtro **Application ID** = número do chamado (o script grava `SAP_ApplicationID`) |
| Chamado em `FAILED` | Ler `u_cpi_message` e as work notes; corrigir; voltar `u_cpi_transport_status` para `PENDING` (ou para `IMPORTING`/`DEPLOYING` se a request já existe e só o passo seguinte falhou) |
| Chamado parado | Ver se o timer está rodando e se o ciclo terminou com erro; checar o horário configurado no Timer |
| Token negado (401) | Validade do certificado, `kid`, client_id/secret e relógio |
| Troca de certificado | Criar par novo no Keystore → cadastrar no ServiceNow como segundo verifier map → trocar `SNOW_JWT_KEY_ALIAS` e `SNOW_JWT_KID` → remover o antigo |

---

## 13. Pontos a confirmar

| # | Ponto | Impacto | Onde confirmar |
| --- | --- | --- | --- |
| 1 | API de export do Content Agent (caminho, corpo, status, ID da request) | Parâmetros da 6.2; sem ela, usar `MANUAL_TR` | help.sap.com → Content Agent → "Consuming APIs"; api.sap.com |
| 2 | Import por ID e status da ação no cTMS (caminhos e campos) | Parâmetros da 6.3 | api.sap.com → Cloud Transport Management (`TmsForCloudPub`) |
| 3 | Servidor de autorização (OAuth do ServiceNow ou IdP corporativo), nome do parâmetro (`assertion` ou `client_assertion`) e escopo | Parâmetros da 6.1 | Time do ServiceNow / Hub de Arquitetura |
| 4 | Keystore do CPI aceito como cofre ou HSM obrigatório | Se HSM: o script `buildTokenRequest` passa a enviar `header.payload` ao HSM e receber a assinatura | Segurança da Informação |
| 5 | CSRF nas chamadas POST à OData API do CPI com OAuth client credentials | Se exigido, adicionar um GET com `X-CSRF-Token: Fetch` antes do deploy | Teste 4 da 11.2 |
| 6 | Nome exato do campo de usuário (*User Field*) no ServiceNow | `SNOW_JWT_SUB` | Time do ServiceNow |

---

## 14. Anexo — scripts Groovy completos

Os mesmos arquivos de [`scripts/`](scripts/), reproduzidos aqui para leitura.


### TRP_01_Orchestrator.groovy

```groovy
/*
 * TRP_01_Orchestrator.groovy
 * iFlow: IF_TRP_01_Orchestrator
 *
 * Funções usadas nos passos de Script do iFlow:
 *   extractToken      -> depois da chamada ao IF_TRP_03 (token do ServiceNow)
 *   ticketsToLines    -> depois do GET na Table API (um chamado por linha, para o Iterating Splitter)
 *   lineToRequest     -> dentro do splitter, antes de chamar o IF_TRP_02
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

/** Campos do sc_req_item que o IF_TRP_02 precisa. Mesma lista do sysparm_fields. */
@Field static final List<String> FIELDS = [
    "sys_id", "number",
    "u_cpi_transport_status", "u_cpi_stage_since", "u_cpi_retries",
    "u_cpi_artifact_id", "u_cpi_artifact_version", "u_cpi_package_id",
    "u_cpi_operation_id", "u_cpi_tr_id", "u_cpi_action_id"
]

/** Lê {"access_token": "..."} devolvido pelo IF_TRP_03 e guarda numa propriedade. */
def Message extractToken(Message message) {
    String body = message.getBody(String)
    def json = body ? new JsonSlurper().parseText(body) : [:]
    if (!json.access_token) {
        throw new IllegalStateException("IF_TRP_03 não devolveu access_token")
    }
    message.setProperty("SNOW_TOKEN", json.access_token as String)
    message.setBody("")
    return message
}

/**
 * Converte {"result":[{...},{...}]} em um JSON por linha.
 * O Iterating Splitter (Expression Type = Line Break) separa cada chamado.
 */
def Message ticketsToLines(Message message) {
    String body = message.getBody(String)
    def json = body ? new JsonSlurper().parseText(body) : [:]
    List result = (json.result ?: []) as List

    List<String> lines = result.collect { t ->
        Map ticket = [:]
        FIELDS.each { f -> ticket[f] = (t[f] ?: "") as String }
        JsonOutput.toJson(ticket)
    }

    message.setProperty("TICKET_COUNT", lines.size() as String)
    message.setBody(lines.join("\n"))
    return message
}

/**
 * Dentro do splitter: o corpo é um chamado (uma linha JSON).
 * Propriedades não atravessam o ProcessDirect, então o token vai num header
 * que o IF_TRP_02 lê e apaga logo no primeiro passo.
 */
def Message lineToRequest(Message message) {
    String line = (message.getBody(String) ?: "").trim()
    if (!line) {
        throw new IllegalStateException("Linha vazia no splitter")
    }
    def ticket = new JsonSlurper().parseText(line)
    message.setHeader("SAP_ApplicationID", ticket.number as String)
    message.setHeader("X-Trp-Snow-Token", message.getProperty("SNOW_TOKEN") as String)
    message.setHeader("Content-Type", "application/json")
    message.setBody(line)
    return message
}
```

### TRP_02_AdvanceTicket.groovy

```groovy
/*
 * TRP_02_AdvanceTicket.groovy
 * iFlow: IF_TRP_02_AdvanceTicket
 *
 * Avança UM passo da máquina de estados de um chamado por ciclo:
 *   PENDING -> EXPORTING -> IMPORTING -> DEPLOYING -> SUCCESS
 *   (contingência sem API de export: PENDING -> WAITING_TR -> IMPORTING ...)
 *   qualquer estado -> FAILED (erro definitivo, prazo vencido ou tentativas esgotadas)
 *
 * Funções (uma por passo de Script no iFlow):
 *   prepare               -> lê o chamado, calcula prazo, valida campos, monta URLs
 *   buildExportRequest    -> corpo do POST de export no Content Agent
 *   handleExportResponse  -> PENDING -> EXPORTING
 *   evaluateExportStatus  -> export concluído? define NEXT = START_IMPORT
 *   findTrByTicket        -> contingência: acha na fila a request com o nº do chamado
 *   buildImportRequest    -> corpo do POST de import por ID no cTMS
 *   handleImportResponse  -> -> IMPORTING (grava ID da request e da ação)
 *   evaluateImport        -> import concluído? define NEXT = START_DEPLOY
 *   handleDeployResponse  -> -> DEPLOYING
 *   evaluateDeploy        -> STARTED na versão pedida -> SUCCESS; ERROR -> FAILED
 *   markWaitingTr         -> PENDING -> WAITING_TR (contingência)
 *   markTimeout           -> prazo do estado vencido -> FAILED
 *   markValidationError   -> campos inválidos -> FAILED
 *   buildUpdateRequest    -> monta o pedido para o IF_TRP_04 (ServiceNow)
 *   handleError           -> Exception Subprocess: erro transitório ou definitivo
 *
 * Os endpoints do Content Agent e parte dos do cTMS vêm de parâmetros externalizados,
 * porque precisam ser confirmados na documentação oficial (ver IMPLEMENTACAO-CPI.md, seção 13).
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

import java.text.SimpleDateFormat

@Field static final String SNOW_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"
@Field static final List<Integer> TRANSIENT_HTTP = [408, 425, 429, 500, 502, 503, 504]

// ---------------------------------------------------------------- utilitários

String prop(Message m, String k) { (m.getProperty(k) ?: "") as String }

String nowUtc() {
    def f = new SimpleDateFormat(SNOW_DATE_FORMAT)
    f.setTimeZone(TimeZone.getTimeZone("UTC"))
    return f.format(new Date())
}

Date parseUtc(String s) {
    def f = new SimpleDateFormat(SNOW_DATE_FORMAT)
    f.setTimeZone(TimeZone.getTimeZone("UTC"))
    return f.parse(s)
}

def bodyJson(Message m) {
    String b = m.getBody(String)
    return b ? new JsonSlurper().parseText(b) : [:]
}

/** Lê um caminho simples com pontos, ex.: "d.Status" ou "result.0.id". */
def jsonPath(Object obj, String path) {
    if (!path) return null
    return path.split("\\.").inject(obj) { acc, k ->
        if (acc == null) return null
        if (acc instanceof List && k.isInteger()) return acc[k as int]
        if (acc instanceof Map) return acc[k]
        return null
    }
}

/** Troca os marcadores #NOME# pelos valores do chamado. */
String fill(String template, Message m) {
    return (template ?: "")
        .replace("#PACKAGE_ID#", prop(m, "PACKAGE_ID"))
        .replace("#ARTIFACT_ID#", prop(m, "ARTIFACT_ID"))
        .replace("#VERSION#", prop(m, "VERSION"))
        .replace("#TICKET#", prop(m, "NUMBER"))
        .replace("#OPERATION_ID#", prop(m, "OPERATION_ID"))
        .replace("#NODE_ID#", prop(m, "CTMS_QAS_NODE_ID"))
        .replace("#ACTION_ID#", prop(m, "ACTION_ID"))
        .replace("#TR_ID#", prop(m, "TR_ID"))
}

List<String> csvUpper(String s) {
    (s ?: "").split(",").collect { it.trim().toUpperCase() }.findAll { it }
}

void setJsonHeaders(Message m) {
    m.setHeader("Content-Type", "application/json")
    m.setHeader("Accept", "application/json")
}

/** Marca que o chamado precisa ser atualizado no ServiceNow. */
void setUpdate(Message m, Map fields, String note) {
    m.setProperty("UPD_FIELDS", JsonOutput.toJson(fields))
    m.setProperty("UPD_NOTE", note ?: "")
    m.setProperty("UPDATE", "true")
}

/** Muda de estado: grava o início do novo estado e zera as tentativas. */
void moveTo(Message m, String state, Map extra, String note) {
    Map f = [u_cpi_transport_status: state, u_cpi_stage_since: nowUtc(), u_cpi_retries: "0"]
    if (extra) f.putAll(extra)
    setUpdate(m, f, note)
}

void fail(Message m, String reason) {
    String r = reason ?: "Falha sem detalhe"
    moveTo(m, "FAILED", [u_cpi_message: r.take(250)], "FALHA: " + r)
}

// ---------------------------------------------------------------- prepare

def Message prepare(Message message) {
    def t = bodyJson(message)
    Map map = [
        SYS_ID      : "sys_id",
        NUMBER      : "number",
        STATE       : "u_cpi_transport_status",
        STAGE_SINCE : "u_cpi_stage_since",
        RETRIES     : "u_cpi_retries",
        ARTIFACT_ID : "u_cpi_artifact_id",
        VERSION     : "u_cpi_artifact_version",
        PACKAGE_ID  : "u_cpi_package_id",
        OPERATION_ID: "u_cpi_operation_id",
        TR_ID       : "u_cpi_tr_id",
        ACTION_ID   : "u_cpi_action_id"
    ]
    map.each { k, f -> message.setProperty(k, (t[f] ?: "") as String) }
    if (!prop(message, "RETRIES")) message.setProperty("RETRIES", "0")

    // Token: chega em header e vira propriedade; o header é esvaziado
    String token = message.getHeaders().get("X-Trp-Snow-Token") as String
    if (!token) throw new IllegalStateException("Token do ServiceNow ausente")
    message.setProperty("SNOW_TOKEN", token)
    message.setHeader("X-Trp-Snow-Token", "")
    message.setHeader("SAP_ApplicationID", prop(message, "NUMBER"))
    message.setHeader("Accept", "application/json")

    // Prazo do estado atual
    String state = prop(message, "STATE")
    Map<String, String> limits = [
        EXPORTING : prop(message, "TIMEOUT_EXPORT_MIN"),
        WAITING_TR: prop(message, "TIMEOUT_WAITING_TR_MIN"),
        IMPORTING : prop(message, "TIMEOUT_IMPORT_MIN"),
        DEPLOYING : prop(message, "TIMEOUT_DEPLOY_MIN")
    ]
    boolean timedOut = false
    if (limits[state] && prop(message, "STAGE_SINCE")) {
        long elapsedMin = ((System.currentTimeMillis() - parseUtc(prop(message, "STAGE_SINCE")).getTime()) / 60000L) as long
        message.setProperty("STAGE_ELAPSED_MIN", elapsedMin as String)
        timedOut = elapsedMin > (limits[state] as long)
    }
    message.setProperty("TIMED_OUT", timedOut as String)

    // Validação dos campos do chamado (só no primeiro passo)
    List<String> errors = []
    if (state == "PENDING") {
        if (!(prop(message, "ARTIFACT_ID") ==~ /[A-Za-z0-9_.\-]+/)) errors << "ID técnico do iFlow inválido"
        if (!(prop(message, "VERSION") ==~ /(\d+\.\d+\.\d+|active)/)) errors << "Versão inválida (ex.: 1.0.3)"
        if (!(prop(message, "PACKAGE_ID") ==~ /[A-Za-z0-9_.\-]+/)) errors << "ID técnico do pacote inválido"
    }
    message.setProperty("VALIDATION_ERROR", errors.join("; "))

    // URLs resolvidas (endpoints configuráveis)
    message.setProperty("CAS_EXPORT_URL", prop(message, "CAS_DEV_URL") + fill(prop(message, "CAS_EXPORT_PATH"), message))
    message.setProperty("CAS_STATUS_URL", prop(message, "CAS_DEV_URL") + fill(prop(message, "CAS_STATUS_PATH"), message))
    message.setProperty("CTMS_QUEUE_URL", prop(message, "CTMS_URI") + fill(prop(message, "CTMS_QUEUE_PATH"), message))
    message.setProperty("CTMS_ACTION_URL", prop(message, "CTMS_URI") + fill(prop(message, "CTMS_ACTION_PATH"), message))

    message.setProperty("UPDATE", "false")
    message.setProperty("NEXT", "")
    message.setBody("")
    return message
}

// ---------------------------------------------------------------- export (Content Agent)

def Message buildExportRequest(Message message) {
    setJsonHeaders(message)
    message.setBody(fill(prop(message, "CAS_EXPORT_BODY_TEMPLATE"), message))
    return message
}

def Message handleExportResponse(Message message) {
    def opId = jsonPath(bodyJson(message), prop(message, "CAS_OPID_JSONPATH"))
    message.setBody("")
    if (!opId) {
        fail(message, "Content Agent não devolveu o ID da operação de export")
        return message
    }
    moveTo(message, "EXPORTING", [u_cpi_operation_id: opId as String],
           "Export criado no Content Agent (operação " + opId + ").")
    return message
}

def Message evaluateExportStatus(Message message) {
    def body = bodyJson(message)
    String status = ((jsonPath(body, prop(message, "CAS_STATUS_JSONPATH")) ?: "") as String).toUpperCase()
    message.setBody("")
    if (status in csvUpper(prop(message, "CAS_DONE_VALUES"))) {
        def trId = jsonPath(body, prop(message, "CAS_TRID_JSONPATH"))
        if (!trId) {
            fail(message, "Export concluído sem ID de transport request na resposta")
            return message
        }
        message.setProperty("TR_ID", trId as String)
        message.setProperty("NEXT", "START_IMPORT")
    } else if (status in csvUpper(prop(message, "CAS_FAILED_VALUES"))) {
        fail(message, "Export falhou no Content Agent (status " + status + ")")
    }
    // qualquer outro status: ainda em andamento, nada a fazer neste ciclo
    return message
}

// ---------------------------------------------------------------- contingência (sem API de export)

def Message markWaitingTr(Message message) {
    moveTo(message, "WAITING_TR", [:],
           "Aguardando a transport request. Clique em Transport no pacote do CPI DEV e escreva " +
           prop(message, "NUMBER") + " na descrição.")
    return message
}

def Message findTrByTicket(Message message) {
    def body = bodyJson(message)
    List list = (body instanceof List) ? body : ((body.transportRequests ?: []) as List)
    String ticket = prop(message, "NUMBER")
    List matches = list.findAll { (it.description ?: "").toString().contains(ticket) }
    message.setBody("")
    if (matches.size() > 1) {
        fail(message, "Há mais de uma request na fila com " + ticket + " na descrição")
    } else if (matches.size() == 1) {
        message.setProperty("TR_ID", matches[0].id as String)
        message.setProperty("NEXT", "START_IMPORT")
    }
    return message
}

// ---------------------------------------------------------------- import (cTMS)

def Message buildImportRequest(Message message) {
    String tr = prop(message, "TR_ID")
    def trValue = tr.isLong() ? (tr as long) : tr
    setJsonHeaders(message)
    message.setProperty("CTMS_IMPORT_URL", prop(message, "CTMS_URI") + fill(prop(message, "CTMS_IMPORT_PATH"), message))
    message.setBody(JsonOutput.toJson([
        namedUser        : prop(message, "CTMS_NAMED_USER"),
        transportRequests: [trValue]
    ]))
    return message
}

def Message handleImportResponse(Message message) {
    def actionId = jsonPath(bodyJson(message), prop(message, "CTMS_ACTIONID_JSONPATH"))
    message.setBody("")
    if (!actionId) {
        fail(message, "cTMS não devolveu o ID da ação de import")
        return message
    }
    moveTo(message, "IMPORTING",
           [u_cpi_tr_id: prop(message, "TR_ID"), u_cpi_action_id: actionId as String],
           "Import da request " + prop(message, "TR_ID") + " iniciado no nó de QAS (ação " + actionId + ").")
    return message
}

def Message evaluateImport(Message message) {
    String status = ((jsonPath(bodyJson(message), prop(message, "CTMS_ACTION_STATUS_JSONPATH")) ?: "") as String).toUpperCase()
    message.setBody("")
    if (status in ["SUCCEEDED", "WARNING"]) {
        message.setProperty("NEXT", "START_DEPLOY")
    } else if (status in ["ERROR", "FATAL"]) {
        fail(message, "Import da request " + prop(message, "TR_ID") + " terminou em " + status + " no cTMS")
    }
    return message
}

// ---------------------------------------------------------------- deploy (CPI QAS)

def Message handleDeployResponse(Message message) {
    message.setBody("")
    moveTo(message, "DEPLOYING", [:], "Deploy do iFlow " + prop(message, "ARTIFACT_ID") + " disparado em QAS.")
    return message
}

def Message evaluateDeploy(Message message) {
    int code = (message.getHeaders().get("CamelHttpResponseCode") ?: 0) as int
    if (code == 404) {               // ainda não apareceu no runtime
        message.setBody("")
        return message
    }
    if (code != 200) {
        String prefix = (code in TRANSIENT_HTTP) ? "TRANSIENT: " : ""
        throw new IllegalStateException(prefix + "Consulta do runtime em QAS retornou HTTP " + code)
    }
    def d = bodyJson(message)?.d
    String status = ((d?.Status ?: "") as String).toUpperCase()
    String runtimeVersion = (d?.Version ?: "") as String
    String wanted = prop(message, "VERSION")
    message.setBody("")

    if (status == "STARTED" && (wanted == "active" || runtimeVersion == wanted)) {
        moveTo(message, "SUCCESS", [u_cpi_message: "STARTED " + runtimeVersion],
               "iFlow " + prop(message, "ARTIFACT_ID") + " versão " + runtimeVersion + " em QAS com status STARTED " +
               "(transport request " + prop(message, "TR_ID") + ").")
    } else if (status == "ERROR") {
        fail(message, "iFlow " + prop(message, "ARTIFACT_ID") + " ficou em ERROR em QAS. Veja o Monitor do CPI QAS.")
    }
    // STARTING, ou STARTED com versão antiga: espera o próximo ciclo
    return message
}

// ---------------------------------------------------------------- estados finais

def Message markTimeout(Message message) {
    fail(message, "Prazo do estado " + prop(message, "STATE") + " vencido (" +
                  prop(message, "STAGE_ELAPSED_MIN") + " min).")
    return message
}

def Message markValidationError(Message message) {
    fail(message, "Campos inválidos no chamado: " + prop(message, "VALIDATION_ERROR"))
    return message
}

// ---------------------------------------------------------------- atualização do chamado

def Message buildUpdateRequest(Message message) {
    def fields = new JsonSlurper().parseText(prop(message, "UPD_FIELDS") ?: "{}")
    String note = prop(message, "UPD_NOTE")
    if (note) fields.work_notes = "[Transporte CPI] " + note
    message.setHeader("X-Trp-Snow-Token", prop(message, "SNOW_TOKEN"))
    message.setHeader("Content-Type", "application/json")
    message.setBody(JsonOutput.toJson([sys_id: prop(message, "SYS_ID"), fields: fields]))
    return message
}

// ---------------------------------------------------------------- erros

def Message handleError(Message message) {
    def ex = message.getProperty("CamelExceptionCaught")
    int code = 0
    String detail = (ex?.getMessage() ?: "erro desconhecido") as String

    // Erro HTTP do adapter (a classe muda entre versões do runtime; testa pelo método)
    if (ex != null && ex.metaClass.respondsTo(ex, "getStatusCode")) {
        code = ex.getStatusCode() as int
        String resp = ex.metaClass.respondsTo(ex, "getResponseBody") ? (ex.getResponseBody() ?: "") as String : ""
        detail = "HTTP " + code + ": " + resp.take(300)
    }

    boolean isTransient = (code in TRANSIENT_HTTP) ||
                          detail.startsWith("TRANSIENT") ||
                          ex instanceof java.net.SocketTimeoutException ||
                          ex instanceof java.net.ConnectException ||
                          (code == 0 && ex?.getCause() instanceof IOException)

    int retries = (prop(message, "RETRIES") ?: "0") as int
    int maxRetries = (prop(message, "MAX_RETRIES") ?: "5") as int

    if (isTransient && retries + 1 < maxRetries) {
        setUpdate(message, [u_cpi_retries: (retries + 1) as String],
                  "Erro transitório no estado " + prop(message, "STATE") + " (tentativa " + (retries + 1) + "): " + detail)
    } else {
        fail(message, "Erro no estado " + prop(message, "STATE") + ": " + detail)
    }
    return buildUpdateRequest(message)
}
```

### TRP_03_SnowToken.groovy

```groovy
/*
 * TRP_03_SnowToken.groovy
 * iFlow: IF_TRP_03_SnowToken
 *
 * OAuth 2.0 com JWT assinado por certificado (grant jwt-bearer), um dos estilos
 * de conexão do Hub de Arquitetura para o ServiceNow.
 *
 * Funções:
 *   buildTokenRequest  -> monta e assina o JWT e o corpo do POST de token
 *   parseTokenResponse -> devolve só {"access_token": "...", "expires_in": n}
 *
 * Material de segurança usado (nomes vêm de parâmetros externalizados):
 *   SNOW_CLIENT_CREDENTIAL : User Credentials (usuário = client_id, senha = client_secret)
 *   SNOW_JWT_KEY_ALIAS     : alias do par de chaves no Keystore do tenant (RSA)
 *
 * Nada deste script grava token, segredo ou chave em log.
 */
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.keystore.KeystoreService
import com.sap.it.api.securestore.SecureStoreService
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.Signature

String prop(Message m, String k) { (m.getProperty(k) ?: "") as String }

String b64url(byte[] bytes) {
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

String enc(String s) { URLEncoder.encode(s ?: "", "UTF-8") }

def Message buildTokenRequest(Message message) {
    // 1. client_id e client_secret do Security Material
    def secureStore = ITApiFactory.getApi(SecureStoreService.class, null)
    def cred = secureStore.getUserCredential(prop(message, "SNOW_CLIENT_CREDENTIAL"))
    if (cred == null) {
        throw new IllegalStateException("Credencial " + prop(message, "SNOW_CLIENT_CREDENTIAL") + " não encontrada no Security Material")
    }
    String clientId = cred.getUsername()
    String clientSecret = new String(cred.getPassword())

    // 2. Chave privada do Keystore
    def keystore = ITApiFactory.getApi(KeystoreService.class, null)
    def key = keystore.getKey(prop(message, "SNOW_JWT_KEY_ALIAS"))
    if (!(key instanceof PrivateKey)) {
        throw new IllegalStateException("Alias " + prop(message, "SNOW_JWT_KEY_ALIAS") + " não é uma chave privada no Keystore")
    }

    // 3. JWT (RS256)
    long now = (long) (System.currentTimeMillis() / 1000L)
    long ttl = (prop(message, "SNOW_JWT_TTL_SECONDS") ?: "300") as long
    String aud = prop(message, "SNOW_JWT_AUD") ?: clientId
    String iss = prop(message, "SNOW_JWT_ISS") ?: clientId

    Map header = [alg: "RS256", typ: "JWT", kid: prop(message, "SNOW_JWT_KID")]
    Map claims = [
        iss: iss,
        aud: aud,
        sub: prop(message, "SNOW_JWT_SUB"),
        iat: now,
        nbf: now - 30,
        exp: now + ttl,
        jti: UUID.randomUUID().toString()
    ]

    String signingInput = b64url(JsonOutput.toJson(header).getBytes(StandardCharsets.UTF_8)) + "." +
                          b64url(JsonOutput.toJson(claims).getBytes(StandardCharsets.UTF_8))

    Signature signer = Signature.getInstance("SHA256withRSA")
    signer.initSign((PrivateKey) key)
    signer.update(signingInput.getBytes(StandardCharsets.US_ASCII))
    String jwt = signingInput + "." + b64url(signer.sign())

    // 4. Corpo do POST (application/x-www-form-urlencoded)
    //    SNOW_ASSERTION_PARAM = "assertion" (jwt-bearer do ServiceNow) ou "client_assertion",
    //    conforme o estilo escolhido no Hub de Arquitetura.
    String assertionParam = prop(message, "SNOW_ASSERTION_PARAM") ?: "assertion"
    String body = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer") +
                  "&client_id=" + enc(clientId) +
                  "&client_secret=" + enc(clientSecret) +
                  "&" + assertionParam + "=" + enc(jwt)
    if (prop(message, "SNOW_TOKEN_SCOPE")) {
        body += "&scope=" + enc(prop(message, "SNOW_TOKEN_SCOPE"))
    }

    message.setHeader("Content-Type", "application/x-www-form-urlencoded")
    message.setHeader("Accept", "application/json")
    message.setBody(body)
    return message
}

def Message parseTokenResponse(Message message) {
    String body = message.getBody(String)
    def json = body ? new JsonSlurper().parseText(body) : [:]
    if (!json.access_token) {
        throw new IllegalStateException("Resposta de token sem access_token")
    }
    message.setBody(JsonOutput.toJson([access_token: json.access_token, expires_in: json.expires_in]))
    message.setHeader("Content-Type", "application/json")
    return message
}
```

### TRP_04_SnowUpdate.groovy

```groovy
/*
 * TRP_04_SnowUpdate.groovy
 * iFlow: IF_TRP_04_SnowUpdate
 *
 * Entrada (ProcessDirect /trp/snow-update):
 *   header X-Trp-Snow-Token : access token do ServiceNow
 *   corpo                   : {"sys_id": "...", "fields": {"u_cpi_transport_status": "...", "work_notes": "..."}}
 *
 * Função:
 *   prepareUpdate -> prepara o PATCH na Table API
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def Message prepareUpdate(Message message) {
    String body = message.getBody(String)
    def req = body ? new JsonSlurper().parseText(body) : [:]
    if (!req.sys_id) {
        throw new IllegalStateException("sys_id ausente no pedido de atualização")
    }
    String token = message.getHeaders().get("X-Trp-Snow-Token") as String
    if (!token) {
        throw new IllegalStateException("Token do ServiceNow ausente")
    }

    message.setProperty("SYS_ID", req.sys_id as String)
    message.setHeader("X-Trp-Snow-Token", "")
    message.setHeader("Authorization", "Bearer " + token)
    message.setHeader("Content-Type", "application/json")
    message.setHeader("Accept", "application/json")
    message.setBody(JsonOutput.toJson(req.fields ?: [:]))
    return message
}
```
