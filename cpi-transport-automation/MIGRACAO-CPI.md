# Migração CPI

**Do modo "Transport Management Service" para o modo "Content Agent Service", pensando na automação ponta a ponta**

Versão de 21 de outubro de 2026 · Escopo: Cloud Integration (CPI), DEV → QAS. Produção entra na Fase 6, como próximo passo.

Este guia leva a sua rota de transporte do modo atual (o CPI empacota e manda direto para o cTMS) para o desenho que a SAP usa como referência nos materiais oficiais: **Content Agent + Cloud Transport Management**, com o **Cloud ALM** como camada opcional de governança. Tudo foi pensado para encaixar na automação já desenhada: chamado no ServiceNow → SAP Automation Pilot → transporte → deploy → retorno ao chamado.

> **Leia antes de começar.** Alguns pontos não puderam ser confirmados em documentação pública aberta (a maior parte do help.sap.com não está acessível às ferramentas usadas para escrever este guia). Eles estão marcados como **A CONFIRMAR** e reunidos na seção 15. O mais importante é a **API de export do Content Agent** (Fase 1): ela decide se a automação fica 100% sem clique.

---

## Sumário

1. [Situação atual](#1-situação-atual)
2. [Arquitetura alvo](#2-arquitetura-alvo)
3. [Por que migrar](#3-por-que-migrar)
4. [Decisões antes de começar](#4-decisões-antes-de-começar)
5. [Fase 0 — Preparação e inventário](#5-fase-0--preparação-e-inventário)
6. [Fase 1 — Confirmar a API de export (gate)](#6-fase-1--confirmar-a-api-de-export-gate)
7. [Fase 2 — Lado de origem (DEV)](#7-fase-2--lado-de-origem-dev)
8. [Fase 3 — Lado de destino (QAS e cTMS)](#8-fase-3--lado-de-destino-qas-e-ctms)
9. [Fase 4 — Virada (cutover) e plano de volta](#9-fase-4--virada-cutover-e-plano-de-volta)
10. [Fase 5 — Automação ponta a ponta](#10-fase-5--automação-ponta-a-ponta)
11. [Fase 6 — Produção (próxima etapa)](#11-fase-6--produção-próxima-etapa)
12. [Cloud ALM (opcional)](#12-cloud-alm-opcional)
13. [Recomendações transversais](#13-recomendações-transversais)
14. [Riscos e mitigação](#14-riscos-e-mitigação)
15. [Pontos a confirmar](#15-pontos-a-confirmar)
16. [Cronograma sugerido e checklist final](#16-cronograma-sugerido-e-checklist-final)
17. [Fontes](#17-fontes)

---

## 1. Situação atual

O que foi levantado no seu ambiente:

| Item | Situação hoje |
| --- | --- |
| Modo de transporte do Integration Suite DEV | **Transport Management Service** (Settings → Transport) |
| Check Configuration | `SUCCESS` para `CloudIntegration` e `TransportManagementService` (a `ContentAssemblyService` não é verificada, porque esse modo não usa Content Agent) |
| Destination `TransportManagementService` (com `sourceSystemId`) | Encontrada no subaccount do **TMS**, não no DEV |
| Subaccount TMS — assinaturas | Cloud Transport Management, plano `standard` |
| Subaccount TMS — instâncias | `CTM_Service_Key` (Cloud Transport Management, `standard`, 2 keys) e `Content_Agent` (Content Agent Service, `standard`, 1 key), ambas no espaço `DEV_QAS` |
| Export | Manual: o dev clica em **Transport** no pacote do CPI DEV |
| Import e deploy | Automatizados pelo Automation Pilot na rota `CTMS_IMPORT` (catálogo `cpitrans`) |

Como o modo atual funciona por dentro:

1. O botão **Transport** faz o CPI chamar o próprio endpoint `/api/1.0/transportmodule/Transport` (é para lá que aponta a destination `CloudIntegration`) e montar um MTAR do pacote.
2. O CPI envia esse MTAR ao cTMS pela destination `TransportManagementService`, que cria a transport request (o "ID") na fila do nó de destino.

O endpoint do passo 1 não está publicado como API documentada. Por isso, **no modo atual não há um jeito suportado de gerar o ID sem o clique**. Esse é o motivo técnico central da migração.

---

## 2. Arquitetura alvo

### 2.1 Visão por subaccount

| Subaccount | O que tem | Para quê |
| --- | --- | --- |
| **DEV** (Brasil) | Integration Suite em modo **Content Agent Service**; assinatura do Content Agent (interface); instância Content Agent Service `standard` + key; instância Process Integration Runtime `api` + key; destinations `ContentAssemblyService`, `CloudIntegration`, `TransportManagementService` | Exportar o conteúdo e criar a request no cTMS |
| **TMS** (Brasil, central) | Cloud Transport Management (assinatura + instância `standard`); destination para o Content Agent de QAS; nós e rota novos | Fila, rota e entrega ao destino |
| **QAS** (Brasil) | Instância Content Agent Service `application` + key; instância Process Integration Runtime `api` (gravar e fazer deploy); destination `CloudIntegration` apontando para o CPI QAS | Receber o conteúdo e gravá-lo no CPI QAS |
| **OPS** (Europa) | SAP Automation Pilot, catálogo `cpitrans` | Orquestrar export, import, deploy e retorno ao chamado |
| ServiceNow | Item de catálogo, flow, usuário de integração | Porta de entrada do dev e trilha de auditoria |
| Cloud ALM (opcional) | Features, aprovação para produção, rastreabilidade | Governança de mudança SAP |

### 2.2 Fluxo alvo

```text
Dev abre chamado (ServiceNow)
        │  POST /api/v1/executions
        ▼
Automation Pilot (Europa)
        │ 1. ExportContent  ──► Content Agent DEV ──► cTMS: cria a request (ID) com o nº do chamado na descrição
        │ 2. ImportRequest  ──► cTMS: importa SÓ essa request no nó QAS ──► Content Agent QAS grava no CPI QAS
        │ 3. Deploy         ──► CPI QAS: deploy + status STARTED
        │ 4. CloseTicket    ──► ServiceNow: grava ID da request, versão, status
        ▼
Chamado fechado (ou FAILED com o motivo)
```

O conteúdo dos iFlows circula só no Brasil (DEV → cTMS → QAS). O Automation Pilot na Europa só orquestra chamadas e guarda credenciais e metadados.

---

## 3. Por que migrar

| Motivo | Modo atual (TMS direto) | Modo alvo (Content Agent) |
| --- | --- | --- |
| Gerar a request por API | Sem API documentada; depende do clique | O guia do Content Agent tem a seção "Consuming APIs" — **A CONFIRMAR** que cobre o export |
| Tipos de conteúdo | Só Cloud Integration | Cloud Integration, API Management, role collections, destinations e outros |
| Dependências | Não trata | Mostra e inclui dependências no export |
| Desenho de referência da SAP | Suportado, mas é o caminho mais antigo | É o desenho dos materiais oficiais ("recommended tools") com cTMS e Cloud ALM |
| Rastreabilidade | Descrição da request digitada à mão | Descrição definida pela automação (número do chamado) |
| Compliance | Conteúdo fica no Brasil | Conteúdo fica no Brasil |

**Se a API de export não for confirmada**, a migração ainda vale pelos outros motivos, mas a automação continua com o clique (ver 10.3). Por isso a Fase 1 vem antes de qualquer mudança no ambiente.

---

## 4. Decisões antes de começar

| # | Decisão | Opções | Recomendação |
| --- | --- | --- | --- |
| D1 | Onde ficam as destinations de origem | Subaccount de origem (padrão SAP) ou centralizadas no TMS (como hoje) | Subaccount de origem, onde está a assinatura do Integration Suite DEV. Ver 5.2 |
| D2 | Nós e rota | Reaproveitar os atuais ou criar novos | **Criar novos** (`DEV_CAS`, `QAS_CAS`). A volta fica trivial e o histórico antigo fica intacto |
| D3 | Escopo da primeira onda | Só CPI ou CPI + API Management | Só CPI. API Management numa segunda onda |
| D4 | Governança | Só ServiceNow ou ServiceNow + Cloud ALM | Começar só com ServiceNow; avaliar Cloud ALM na Fase 6 (seção 12) |
| D5 | Janela de virada | — | Fora do horário de entregas, com fila de QAS vazia e devs avisados |
| D6 | Instância `Content_Agent` que já existe no TMS | Reaproveitar, ignorar ou remover | Descobrir quem usa (5.3). Não remover antes da migração estabilizar |

---

## 5. Fase 0 — Preparação e inventário

### 5.1 Entitlements

Como Global Account Administrator, em cada subaccount: **Entitlements → Edit → Add Service Plans**.

| Subaccount | Serviço | Plano | Uso |
| --- | --- | --- | --- |
| DEV | Content Agent Service | `standard` (instância) | Montagem e export |
| DEV | Content Agent | Plano de assinatura disponível na sua conta (nos laboratórios da SAP aparece `free`) | Interface web do Content Agent |
| DEV | Process Integration Runtime | `api` | Destination `CloudIntegration` de origem |
| QAS | Content Agent Service | `application` | Receber o import |
| QAS | Process Integration Runtime | `api` | Destination `CloudIntegration` de destino e credencial de deploy |
| TMS | Cloud Transport Management | `standard` (já existe) | Fila, nós, rota e API |

### 5.2 Descobrir o subaccount de origem de verdade

As destinations do modo atual estão no subaccount do TMS, mas o Check Configuration do CPI DEV as encontra. Antes de criar qualquer coisa, descubra de onde o CPI DEV lê:

1. Ache o subaccount onde está a **assinatura do Integration Suite DEV** (Instances and Subscriptions → Subscriptions). Esse é o subaccount de origem.
2. Nele, veja **Connectivity → Destinations** e também as destinations **no nível de instância** (abra cada instância do serviço Destination, se houver).
3. Se o Integration Suite DEV estiver no próprio subaccount TMS, a "origem" é o TMS e as destinations da Fase 2 vão para lá.

Anote o resultado: todas as destinations "de origem" deste guia vão para **esse** subaccount.

### 5.3 Inventário do que já existe

- [ ] `CTM_Service_Key`: para que serve cada uma das 2 keys (compare o `clientid` de cada key com o Client ID das destinations).
- [ ] `Content_Agent` no TMS: compare o `clientid` da key com as destinations. Se nenhuma usa, está sem uso.
- [ ] Nós e rota atuais do cTMS: nome, tipo de conteúdo, destination de cada nó.
- [ ] Destination usada pelo nó QAS atual (provavelmente o deploy service do espaço de QAS).
- [ ] Requests pendentes na fila de QAS (precisam estar zeradas na virada).

### 5.4 Backup

Para cada destination existente (`CloudIntegration`, `TransportManagementService` e a do nó QAS): **Destinations → selecione → Export**. Guarde os arquivos num cofre do time, nunca no repositório. São o seu plano de volta.

Tire prints de Settings → Transport, dos nós e da rota atuais.

### 5.5 Papéis

| Role collection | Quem | Para quê |
| --- | --- | --- |
| `Content Agent Admin` (nome nos laboratórios da SAP) | Você | Usar a interface e configurar o Content Agent |
| Administrador do cTMS | Você | Criar nós e rotas |
| `AuthGroup.Administrator` (Integration Suite DEV) | Você | Mudar o modo em Settings → Transport |
| Role com `WorkspacePackagesTransport` | Devs | Ver o botão Transport no CPI |

### 5.6 Comunicação

Avise os devs da janela de virada. Durante a janela, ninguém clica em Transport e ninguém importa à mão no cTMS.

---

## 6. Fase 1 — Confirmar a API de export (gate)

Esta fase decide o resto. Não mexe em nada do ambiente.

### 6.1 Onde procurar

1. Com o seu login, abra no help.sap.com o guia do **SAP Content Agent Service → Consuming APIs**.
2. No **SAP Business Accelerator Hub** (api.sap.com), procure "Content Agent".
3. Anote, se existir: URL base (de qual campo da service key ela sai), autenticação, endpoint que cria o export, corpo de exemplo, endpoint de status e o campo da resposta que traz o ID da transport request.

### 6.2 Teste de bancada (depois de criar a instância da 7.2)

Com a key `standard` do Content Agent DEV, no Postman:

1. Pegue o token: `POST {uaa.url}/oauth/token` com `grant_type=client_credentials`.
2. Chame o endpoint de export documentado, com um pacote de teste e a descrição `TESTE-API`.
3. Confirme que uma request com a descrição `TESTE-API` apareceu na fila do nó QAS novo.

### 6.3 Decisão

| Resultado | Caminho |
| --- | --- |
| API existe e funciona | Siga as Fases 2 a 5 normalmente. Automação 100% sem clique (10.2) |
| API não existe ou não é suportada | A migração ainda pode valer pelos motivos da seção 3. A automação continua com o clique, mas amarrada ao chamado (10.3). Abra um chamado na SAP perguntando pelo caminho suportado |

---

## 7. Fase 2 — Lado de origem (DEV)

Crie tudo nesta fase **sem mudar o modo de transporte**. As destinations com nome fixo que já existem (`CloudIntegration`, `TransportManagementService`) só são alteradas na virada (Fase 4).

### 7.1 Assinatura do Content Agent (interface)

1. Subaccount de origem → **Instances and Subscriptions → Create**.
2. Serviço **Content Agent**, plano de assinatura disponível (5.1).
3. Atribua a role collection `Content Agent Admin` e abra a aplicação. Em **Content Types**, confira se **Cloud Integration** aparece como **Active** (ele só fica ativo depois das destinations da 7.3 e 7.4).

### 7.2 Instância Content Agent Service `standard`

1. **Create → Content Agent Service**, plano `standard`, nome `cas-dev`.
2. **Create Service Key** → `key-cas-dev`. Guarde a URL e o bloco `uaa`. Ela alimenta a destination da 7.3 e o input `CasCredentials` do Automation Pilot.

### 7.3 Destination `ContentAssemblyService`

**Connectivity → Destinations → Create → From Service Instance** (ou manual com os dados da `key-cas-dev`):

| Campo | Valor |
| --- | --- |
| Name | `ContentAssemblyService` (nome fixo, sensível a maiúsculas) |
| Service Instance | `cas-dev` |
| Authentication | OAuth2ClientCredentials (preenchido a partir da instância) |

Um `401` no Check Connection é esperado para essa destination.

### 7.4 Instância Process Integration Runtime `api` de origem

1. **Create → Process Integration Runtime**, plano `api`, nome `pi-api-dev-transport`.
2. Grant type `client_credentials`, roles `WorkspacePackagesRead` e `WorkspacePackagesTransport`.
3. Key `key-pi-api-dev-transport`.

Ela alimenta a nova `CloudIntegration` na virada (9.2). **A CONFIRMAR:** a URL exigida pelo modo Content Agent. No modo atual a URL termina em `/api/1.0/transportmodule/Transport`; nos tutoriais do Content Agent aparece a URL do tenant. Confirme na doc antes da virada.

### 7.5 Key do cTMS para a destination de origem

Na instância `CTM_Service_Key` do TMS, crie uma key nova `key-tms-source-cas`. Ela vai na `TransportManagementService` na virada. Não reaproveite as keys atuais: assim a volta não depende de mexer nelas.

---

## 8. Fase 3 — Lado de destino (QAS e cTMS)

Tudo aqui é novo e não interfere na rota atual.

### 8.1 Content Agent no QAS

1. Subaccount QAS → **Create → Content Agent Service**, plano `application`, nome `cas-app-qas`. Ao criar, selecione a role de administração ou import (nos laboratórios da SAP: `Admin`).
2. Key `key-cas-app-qas`.

### 8.2 Destination `CloudIntegration` no QAS

O Content Agent de QAS grava o conteúdo no CPI QAS por ela.

1. Instância Process Integration Runtime `api` `pi-api-qas-transport`, roles `WorkspacePackagesRead`, `WorkspacePackagesEdit` e `WorkspacePackagesTransport`. Key `key-pi-api-qas-transport`.
2. **Destinations → New** no subaccount QAS:

| Campo | Valor |
| --- | --- |
| Name | `CloudIntegration` |
| Type | HTTP |
| URL | URL do tenant CPI QAS (**A CONFIRMAR** o formato exigido pelo Content Agent) |
| Authentication | OAuth2ClientCredentials |
| Client ID / Secret / Token Service URL | Da `key-pi-api-qas-transport` |

Se já existir uma `CloudIntegration` no QAS usada pelo modo atual, **não altere** antes da virada; compare as duas configurações e trate na Fase 4.

A credencial de deploy do Automation Pilot (`pi-api-qas`, com `WorkspaceArtifactsDeploy` e `MonitoringDataRead`) continua separada desta.

### 8.3 Destination do nó QAS no cTMS

No subaccount TMS → **Destinations → New**:

| Campo | Valor |
| --- | --- |
| Name | `CAS_QAS` |
| Type | HTTP |
| URL | URL da `key-cas-app-qas` |
| Proxy Type | Internet |
| Authentication | OAuth2ClientCredentials |
| Client ID / Secret | Da `key-cas-app-qas` |
| Token Service URL | `uaa.url` da `key-cas-app-qas` + `/oauth/token` |

### 8.4 Nós e rota novos

Na aplicação do cTMS → **Transport Nodes → +**:

| Nó | Allow Upload to Node | Forward Mode | Content Type | Destination |
| --- | --- | --- | --- | --- |
| `DEV_CAS` | Sim | Auto | — (origem) | Nenhuma |
| `QAS_CAS` | Não | Auto | Multi-Target Application | `CAS_QAS` |

**Transport Routes → +**: `DEV_CAS_TO_QAS_CAS`, origem `DEV_CAS`, destino `QAS_CAS`.

### 8.5 ID numérico do nó novo

```bash
TOKEN=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d grant_type=client_credentials "$UAA_URL/oauth/token" | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" "$TMS_URI/v2/nodes" | jq '.[] | {id, name}'
```

Anote o `id` do `QAS_CAS`. Ele substitui o `qasNodeId` atual no flow do ServiceNow.

---

## 9. Fase 4 — Virada (cutover) e plano de volta

### 9.1 Pré-virada (D-1)

- [ ] Fases 2 e 3 concluídas, Fase 1 decidida.
- [ ] Backup das destinations atuais guardado (5.4).
- [ ] Fila de QAS vazia.
- [ ] Devs avisados; flow do ServiceNow desativado durante a janela.

### 9.2 Passos da virada

1. **Destination `TransportManagementService`** (no subaccount de origem descoberto em 5.2): troque as credenciais para a `key-tms-source-cas` e a propriedade `sourceSystemId` para `DEV_CAS`.
2. **Destination `CloudIntegration` de origem**: ajuste para a `key-pi-api-dev-transport` e a URL confirmada para o modo Content Agent (7.4).
3. **Integration Suite DEV → Settings → Transport → Edit**: Transport Mode = **Content Agent Service**. Salve.
4. **Check Configuration**: agora devem aparecer `CloudIntegration`, `ContentAssemblyService` e `TransportManagementService` com `SUCCESS`.
5. No Content Agent DEV, **Content Types**: Cloud Integration **Active**.

### 9.3 Testes de aceite

- [ ] Pela interface do Content Agent: export de um pacote de teste com modo **SAP Cloud Transport Management** e descrição `TESTE-UI`. A request aparece na fila do `QAS_CAS`.
- [ ] Import manual dessa request no `QAS_CAS`. Action log com sucesso e pacote visível no CPI QAS.
- [ ] Pelo botão **Transport** no CPI DEV: a request também cai no `QAS_CAS` (no modo Content Agent o botão passa pelo Content Agent).
- [ ] Rode o `ImportQueue` e o `DeployIntegrationFlow` do Automation Pilot apontando para o `QAS_CAS`.
- [ ] Rode o export por API (se a Fase 1 confirmou).

### 9.4 Plano de volta

Se algum teste de aceite falhar e não houver correção na janela:

1. Settings → Transport → **Transport Management Service**.
2. Restaure `TransportManagementService` e `CloudIntegration` a partir do backup (credenciais, URL e `sourceSystemId` antigos).
3. Check Configuration com `SUCCESS` nas duas.
4. Transporte de teste pela rota antiga.

Os nós, a rota e as destinations novos podem ficar criados; eles não atrapalham a rota antiga.

### 9.5 Pós-virada

- Reative o flow do ServiceNow com o novo `qasNodeId`.
- Mantenha os nós e a rota antigos por 30 dias (só leitura, histórico) antes de remover.
- Decida o destino da instância `Content_Agent` antiga do TMS (5.3).

---

## 10. Fase 5 — Automação ponta a ponta

### 10.1 Mudanças no Automation Pilot

| Item | Mudança |
| --- | --- |
| Input `CasCredentials` | Preencher com a `key-cas-dev` (7.2) |
| Comando `ExportContent` | Preencher `exportPath`, `statusPath`, corpo e campo do ID com o que a Fase 1 confirmou. A descrição da request passa a ser o número do chamado |
| Novo comando `ImportRequest` | Importar **só a request** devolvida pelo export (`POST /v2/nodes/{nodeId}/transportRequests/import` com o ID), em vez da fila inteira |
| Orquestrador `TransportToQas` | `route = CTMS` passa a ser o padrão: `Export → ImportRequest → Deploy → CloseTicket` |
| `UpdateServiceNowTicket` | Gravar também o ID da transport request nas work notes |

Os comandos `ImportQueue` (fila inteira) e a rota `CTMS_IMPORT` continuam no catálogo como contingência.

### 10.2 Fluxo com a API confirmada

1. Dev abre o chamado com iFlow, versão e pacote.
2. Flow do ServiceNow chama `TransportToQas` com `route = CTMS`.
3. `ExportContent` exporta o pacote com descrição = número do chamado e devolve o ID da request.
4. `ImportRequest` importa só essa request no `QAS_CAS` e espera o action log terminar.
5. `DeployIntegrationFlow` faz o deploy do iFlow e espera `STARTED`.
6. `CloseTicket` grava no chamado: ID da request, versão, status, rota.

Corpo do POST do ServiceNow:

```json
{
  "commandId": "cpitrans-T006887R4:TransportToQas:1",
  "inputValues": {
    "artifactId": "{{artifact_id}}",
    "version": "{{version}}",
    "packageId": "{{package_id}}",
    "route": "CTMS",
    "qasNodeId": "<ID_DO_NO_QAS_CAS>",
    "ticketNumber": "{{ticket_number}}",
    "ticketSysId": "{{ticket_sys_id}}",
    "ticketTable": "sc_req_item"
  },
  "tags": {"feature:logs": "", "servicenow:ticket": "{{ticket_number}}"}
}
```

### 10.3 Fluxo se a API não for confirmada

O clique continua, mas amarrado ao chamado:

1. Dev abre o chamado e recebe o número (RITM).
2. Dev clica em **Transport** e digita o número do chamado na descrição da request.
3. O Automation Pilot procura na fila do `QAS_CAS` a request cuja descrição contém o número do chamado (espera até alguns minutos, caso o clique venha depois do chamado).
4. Importa só essa request, faz o deploy e grava tudo no chamado.
5. Se não achar a request no tempo limite, falha com mensagem clara. Sem falso sucesso.

### 10.4 Granularidade

O transporte leva o **pacote**; o deploy é de **um iFlow por execução** (decisão já tomada). Se o pacote tiver outros iFlows alterados, eles chegam em QAS mas não são implantados por esse chamado. Combine com os devs: um pacote por assunto, ou um chamado por iFlow alterado.

---

## 11. Fase 6 — Produção (próxima etapa)

| Item | O que fazer |
| --- | --- |
| Subaccount PRD | Content Agent Service `application` + key; destination `CloudIntegration` para o CPI PRD |
| cTMS | Destination `CAS_PRD`; nó `PRD_CAS` (Multi-Target Application, Forward Mode **Manual** ou controlado pela aprovação); rota `QAS_CAS → PRD_CAS` |
| Aprovação | Mudança aprovada no ServiceNow dispara o import em PRD; ou Feature aprovada no Cloud ALM (seção 12) |
| Automação | Comando `TransportToPrd`: importa em PRD a **mesma** request já validada em QAS (pelo ID gravado no chamado), faz o deploy e fecha a mudança |
| Credenciais | Inputs separados para PRD (`CpiPrdCredentials`), nunca reaproveitar os de QAS |
| Regra de ouro | Em PRD só entra request que passou por QAS. Nada de export direto para PRD |

---

## 12. Cloud ALM (opcional)

No desenho de referência da SAP, o Cloud ALM orquestra o cTMS:

- A destination `TransportManagementService` de origem aponta para a **API de transporte do Cloud ALM** (no laboratório da SAP: URL da API do Cloud ALM + `/imp-cdm-transport-management-api/v1`, com `sourceSystemId`).
- Cada mudança é uma **Feature**. As requests são atribuídas à Feature, o deploy em QAS e PRD é feito pela Feature, e produção só libera com status "Ready for Production".
- O app **Feature Traceability** mostra onde cada mudança está.

| Prós | Contras |
| --- | --- |
| Governança nativa SAP, rastreabilidade por Feature | Mais uma ferramenta além do ServiceNow |
| Aprovação de produção embutida | Integração com ServiceNow não confirmada em fonte oficial; provavelmente via API do Cloud ALM |
| Normalmente incluído no suporte SAP Enterprise | A automação precisaria criar e mover Features por API (**A CONFIRMAR**) |

**Recomendação:** começar sem Cloud ALM, com o ServiceNow como fonte da verdade. Reavaliar quando produção entrar (Fase 6), principalmente se a auditoria pedir rastreabilidade dentro do ecossistema SAP.

---

## 13. Recomendações transversais

**Segurança**
- Uma service key por consumidor (destination de origem, destination de destino, Automation Pilot, ServiceNow). Revogar uma não derruba as outras.
- Só as roles necessárias em cada instância do plano `api`. A de origem lê e transporta; a de destino grava; a do Automation Pilot faz deploy e lê status.
- Rotação de keys periódica (sugestão: semestral), registrada.
- Segredos só nos inputs sensíveis do Automation Pilot e no Credential Alias do ServiceNow. Nunca em chamado, e-mail ou repositório.

**Disciplina de fila**
- Ninguém importa à mão em QAS depois da virada.
- Um chamado gera uma request e vira um deploy.
- Requests "órfãs" na fila (sem chamado) são investigadas, não importadas.

**Rastreabilidade**
- Número do chamado na descrição da request.
- ID da request, ID da execução do Automation Pilot e versão do iFlow gravados no chamado.
- Logs da execução ligados (`feature:logs`) e action logs do cTMS preservados.

**Padrão de nomes**

| Objeto | Padrão | Exemplo |
| --- | --- | --- |
| Nó cTMS | `<AMBIENTE>_CAS` | `DEV_CAS`, `QAS_CAS` |
| Rota | `<ORIGEM>_TO_<DESTINO>` | `DEV_CAS_TO_QAS_CAS` |
| Destination de nó | `CAS_<AMBIENTE>` | `CAS_QAS` |
| Instância | `<serviço>-<ambiente>[-uso]` | `pi-api-qas-transport` |
| Service key | `key-<instância>[-consumidor]` | `key-tms-automation-pilot` |

**Monitoramento**
- Falhas do Automation Pilot viram `FAILED` no chamado e vão para o grupo de suporte de integração.
- Revisão semanal do action log do cTMS no começo, mensal depois de estabilizar.

**Compliance**
- Com o Content Agent, o conteúdo dos iFlows fica no Brasil. O Automation Pilot na Europa guarda credenciais e metadados das execuções. Leve esse desenho à Segurança da Informação antes da virada.

---

## 14. Riscos e mitigação

| Risco | Impacto | Mitigação |
| --- | --- | --- |
| API de export não existir ou não ser suportada | Automação continua com clique | Fase 1 antes de tudo; fluxo 10.3; chamado na SAP |
| Destinations criadas no subaccount errado | Check Configuration falha ou o transporte some | Descobrir o subaccount de origem (5.2) antes de criar |
| `sourceSystemId` diferente do nome do nó | Export "com sucesso" que não aparece na fila | Copiar e colar o nome do nó; testar 9.3 |
| URL da `CloudIntegration` errada para o modo Content Agent | Content type Cloud Integration fica inativo | Confirmar na doc (7.4); backup para voltar |
| Importar requests de outros junto | Conteúdo não testado em QAS | `ImportRequest` por ID; disciplina de fila |
| Pacote com vários iFlows alterados | Mudança não intencional chega a QAS | Regra de granularidade (10.4) |
| Credencial de um ambiente no input de outro | 401 ou ação no ambiente errado | Conferir subdomínio de cada URL; `clientId` diferentes por ambiente |
| Virada sem volta testada | Transporte parado | Plano de volta (9.4) ensaiado na janela |

---

## 15. Pontos a confirmar

| Item | Onde confirmar | Impacto |
| --- | --- | --- |
| API de export do Content Agent (endpoint, corpo, ID da request na resposta) | help.sap.com → Content Agent → "Consuming APIs"; api.sap.com | Define a automação 100% sem clique |
| URL da destination `CloudIntegration` no modo Content Agent | Guia do Content Agent → "Using Integration Suite Applications" | Virada (9.2) |
| Se o Content Agent exporta iFlow individual ou só pacote | Interface do Content Agent → Export | Granularidade (10.4) |
| Plano de assinatura da interface do Content Agent na sua conta | Entitlements da global account | 7.1 |
| Nome exato da role collection do Content Agent | Role Collections do subaccount DEV | 5.5 |
| Endpoint de import por ID no cTMS e campos da resposta | api.sap.com → Cloud Transport Management (`TmsForCloudPub`) | `ImportRequest` (10.1) |
| Se o botão Transport do CPI usa o Content Agent no modo novo | Teste 9.3 | Rotina dos devs |
| API do Cloud ALM para Features (se adotar) | api.sap.com → SAP Cloud ALM | Seção 12 |

---

## 16. Cronograma sugerido e checklist final

| Semana | Atividade | Saída |
| --- | --- | --- |
| 1 | Fase 0 e Fase 1 | Inventário, backup, decisão sobre a API de export |
| 2 | Fases 2 e 3 em paralelo à rota atual | Origem e destino prontos, nós novos criados |
| 3 | Fase 4 (virada) + testes de aceite | Modo Content Agent ativo, rota nova validada |
| 4 | Fase 5 | Automação ponta a ponta pelo chamado |
| Depois | Fase 6 e avaliação do Cloud ALM | Produção |

Checklist final:

- [ ] Subaccount de origem identificado
- [ ] Backup das destinations atuais guardado
- [ ] API de export confirmada (ou fluxo 10.3 escolhido)
- [ ] Content Agent DEV ativo com Cloud Integration **Active**
- [ ] Content Agent QAS e destination `CAS_QAS` criados
- [ ] Nós `DEV_CAS`, `QAS_CAS` e rota criados; `sourceSystemId = DEV_CAS`
- [ ] Modo de transporte = Content Agent Service; Check Configuration com 3 `SUCCESS`
- [ ] Testes de aceite 9.3 aprovados
- [ ] Automation Pilot com `CasCredentials`, `ExportContent` e `ImportRequest`
- [ ] Flow do ServiceNow com o novo `qasNodeId` e `route = CTMS`
- [ ] Teste ponta a ponta pelo portal do ServiceNow
- [ ] Rota antiga mantida 30 dias e depois removida

---

## 17. Fontes

- [SAP TechEd AD261 — Manage the Propagation of Content Types Based on SAP BTP with SAP Cloud ALM](https://github.com/SAP-archive/teched2023-AD261) — desenho de referência com Content Agent, cTMS e Cloud ALM; nós, destinations e Features.
- [Transport SAP Integration Suite Content using cTMS and Content Agent Service (SAP Tutorials)](https://developers.sap.com/group.cloud-transport-migrate-integration-suite-content.html)
- [SAP Content Agent Service — Consuming APIs (help.sap.com)](https://help.sap.com/docs/content-agent-service/user-guide/consuming-apis) — a confirmar com login.
- [Content Transport Using cTMS for SAP CI CF Environment](https://blogs.sap.com/2020/09/21/content-transport-using-sap-cloud-platform-transport-management-service-in-sap-cpi-cf-environment/) — modo TMS direto e endpoint `/api/1.0/transportmodule/Transport`.
- [SAP CPI: transport package by using TMS](https://sapzero2hero.com/2022/06/15/sap-cpi-transport-package-by-using-transport-management-service-tms/) — destinations do modo TMS direto.
- [Integration Suite (CPI) content transport using CTMS and Content Agent Service](https://community.sap.com/t5/technology-blog-posts-by-members/integration-suite-cpi-content-transport-using-ctms-and-content-agent/ba-p/14095550) — planos por subaccount e `sourceSystemId`.
- [Release and Deployment Management in SAP Cloud ALM](https://support.sap.com/ja/alm/sap-cloud-alm/implementation/sap-cloud-alm-implementation-expert-portal/change-deployment-mangement.html) — Features e deploy de transportes cTMS.
- [SAP Automation Pilot — agent skills (SAP)](https://github.com/SAP/automation-pilot-agent-skills) — formato dos comandos e API de execuções.
