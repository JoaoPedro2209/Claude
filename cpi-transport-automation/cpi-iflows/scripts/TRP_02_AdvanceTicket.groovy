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
 *   prepare               -> lê o chamado, decide a rota (propriedade ROUTE) e já resolve
 *                            os casos que não precisam de chamada HTTP (prazo vencido,
 *                            campos inválidos, contingência PENDING -> WAITING_TR)
 *   buildExportRequest    -> corpo do POST de export no Content Agent
 *   handleExportResponse  -> PENDING -> EXPORTING
 *   evaluateExportStatus  -> export concluído? define NEXT = START_IMPORT
 *   findTrByTicket        -> contingência: acha na fila a request com o nº do chamado
 *   buildImportRequest    -> corpo do POST de import por ID no cTMS
 *   handleImportResponse  -> -> IMPORTING (grava ID da request e da ação)
 *   evaluateImport        -> import concluído? define NEXT = START_DEPLOY
 *   handleDeployResponse  -> -> DEPLOYING
 *   evaluateDeploy        -> STARTED, implantado depois do pedido e na versão pedida -> SUCCESS
 *   buildUpdateRequest    -> monta o pedido para o IF_TRP_04 (ServiceNow)
 *   handleError           -> Exception Subprocess: erro transitório ou definitivo
 *                            (o erro também aparece no monitor, em Custom Headers > TRP_Error)
 *
 * Valores de ROUTE: EXPORT, CHECK_EXPORT, CHECK_QUEUE, CHECK_IMPORT, CHECK_DEPLOY, DONE
 * Valores de NEXT : START_IMPORT, START_DEPLOY ou vazio
 *
 * Os endpoints do Content Agent e do cTMS vêm de parâmetros externalizados,
 * porque precisam ser confirmados no tenant (ver IMPLEMENTACAO-CPI.md).
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

import java.text.SimpleDateFormat

@Field static final String SNOW_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"
@Field static final List<Integer> TRANSIENT_HTTP = [408, 425, 429, 500, 502, 503, 504]
/** Tolerância entre o relógio do orquestrador e o do tenant de QAS. */
@Field static final long CLOCK_SKEW_MS = 120000L
/** Caminhos padrão da API v2 do cTMS (podem ser trocados por parâmetro). */
@Field static final String DEFAULT_QUEUE_PATH = "/v2/nodes/#NODE_ID#/transportRequests"
@Field static final String DEFAULT_IMPORT_PATH = "/v2/nodes/#NODE_ID#/transportRequests/import"
@Field static final String DEFAULT_ACTION_PATH = "/v2/actions/#ACTION_ID#"
/** Marca do erro "o ServiceNow não gravou" lançado pelo IF_TRP_04 (TRP_04_SnowUpdate.groovy). */
@Field static final String WRITE_MARKER = "[TRP-GRAVACAO]"
/** Respostas do cTMS ao import que podem ser só corrida entre dois ciclos (request já em import). */
@Field static final List<Integer> IMPORT_REJECTED_HTTP = [400, 404, 409, 412, 422, 423]

// ---------------------------------------------------------------- utilitários

/** Lê uma propriedade. O valor "-" conta como vazio (para parâmetros opcionais). */
String prop(Message m, String k) {
    String v = ((m.getProperty(k) ?: "") as String).trim()
    return v == "-" ? "" : v
}

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

/** Lista de valores separados por vírgula, em maiúsculas; usa o padrão se o parâmetro estiver vazio. */
List<String> csvUpper(String s, String fallback) {
    ((s ?: "") ?: fallback).split(",").collect { it.trim().toUpperCase() }.findAll { it }
}

/** Grava um Custom Header no log da mensagem (ignora se não estiver rodando no CPI). */
void logHeader(Message m, String name, String value) {
    try {
        def log = messageLogFactory.getMessageLog(m)
        if (log != null) log.addCustomHeaderProperty(name, (value ?: "").take(200))
    } catch (MissingPropertyException ignored) {
        // fora do CPI (testes locais) não existe messageLogFactory
    }
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
    m.setProperty("NEXT", "")
}

/** DeployedOn do OData: "/Date(1767225600000)/" (JSON) ou "2026-02-25T09:46:43.180" (UTC). */
Long parseDeployedOn(Object value) {
    String s = (value ?: "") as String
    def m = (s =~ /\/Date\((\d+)([+-]\d+)?\)\//)
    if (m.find()) return m.group(1) as Long
    if (s ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*/) {
        try {
            def f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
            f.setTimeZone(TimeZone.getTimeZone("UTC"))
            return f.parse(s.take(19)).getTime()
        } catch (Exception ignored) {
            return null
        }
    }
    return null
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
    map.each { k, f -> message.setProperty(k, ((t[f] ?: "") as String).trim()) }
    if (!prop(message, "RETRIES")) message.setProperty("RETRIES", "0")
    message.setProperty("UPDATE", "false")
    message.setProperty("NEXT", "")

    // Token: chega em header e vira propriedade; o header é esvaziado
    String token = message.getHeaders().get("X-Trp-Snow-Token") as String
    if (!token) throw new IllegalStateException("Token do ServiceNow ausente (header X-Trp-Snow-Token)")
    message.setProperty("SNOW_TOKEN", token)
    message.setHeader("X-Trp-Snow-Token", "")
    message.setHeader("SAP_ApplicationID", prop(message, "NUMBER"))
    message.setHeader("Accept", "application/json")
    message.setBody("")

    String state = prop(message, "STATE")
    boolean active = state in ["PENDING", "EXPORTING", "WAITING_TR", "IMPORTING", "DEPLOYING"]

    // Configuração mínima (só cobra quando há algo a fazer)
    if (active) {
        ["CTMS_URI", "CTMS_QAS_NODE_ID"].each { k ->
            if (!prop(message, k)) throw new IllegalStateException("Parâmetro " + k + " vazio no IF_TRP_02")
        }
    }

    // URLs resolvidas (endpoints configuráveis, com padrão quando o parâmetro não existe)
    String base = prop(message, "CTMS_URI")
    message.setProperty("CAS_EXPORT_URL", prop(message, "CAS_DEV_URL") + fill(prop(message, "CAS_EXPORT_PATH"), message))
    message.setProperty("CAS_STATUS_URL", prop(message, "CAS_DEV_URL") + fill(prop(message, "CAS_STATUS_PATH"), message))
    message.setProperty("CTMS_QUEUE_URL", base + fill(prop(message, "CTMS_QUEUE_PATH") ?: DEFAULT_QUEUE_PATH, message))
    message.setProperty("CTMS_ACTION_URL", base + fill(prop(message, "CTMS_ACTION_PATH") ?: DEFAULT_ACTION_PATH, message))

    // 1. Prazo do estado atual
    Map<String, String> limits = [
        EXPORTING : prop(message, "TIMEOUT_EXPORT_MIN") ?: "15",
        WAITING_TR: prop(message, "TIMEOUT_WAITING_TR_MIN") ?: "60",
        IMPORTING : prop(message, "TIMEOUT_IMPORT_MIN") ?: "15",
        DEPLOYING : prop(message, "TIMEOUT_DEPLOY_MIN") ?: "10"
    ]
    if (limits[state] && prop(message, "STAGE_SINCE")) {
        long elapsedMin = ((System.currentTimeMillis() - parseUtc(prop(message, "STAGE_SINCE")).getTime()) / 60000L) as long
        message.setProperty("STAGE_ELAPSED_MIN", elapsedMin as String)
        if (elapsedMin > (limits[state] as long)) {
            fail(message, "Prazo do estado " + state + " vencido (" + elapsedMin + " min; limite " + limits[state] + " min).")
            message.setProperty("ROUTE", "DONE")
            return message
        }
    }

    // 2. Primeiro passo: valida os campos e escolhe o modo de export
    if (state == "PENDING") {
        List<String> errors = []
        if (!(prop(message, "ARTIFACT_ID") ==~ /[A-Za-z0-9_.\-]+/)) errors << "ID técnico do iFlow inválido"
        if (!(prop(message, "VERSION") ==~ /(\d+\.\d+\.\d+|active)/)) errors << "Versão inválida (ex.: 1.0.3)"
        if (!(prop(message, "PACKAGE_ID") ==~ /[A-Za-z0-9_.\-]+/)) errors << "ID técnico do pacote inválido"
        if (errors) {
            fail(message, "Campos inválidos no chamado: " + errors.join("; "))
            message.setProperty("ROUTE", "DONE")
        } else if (prop(message, "EXPORT_MODE").toUpperCase() == "API") {
            message.setProperty("ROUTE", "EXPORT")
        } else {
            moveTo(message, "WAITING_TR", [:],
                   "Aguardando a transport request. Clique em Transport no pacote do CPI DEV e escreva " +
                   prop(message, "NUMBER") + " na descrição.")
            message.setProperty("ROUTE", "DONE")
        }
        return message
    }

    // 3. Demais estados
    Map<String, String> routes = [
        EXPORTING : "CHECK_EXPORT",
        WAITING_TR: "CHECK_QUEUE",
        IMPORTING : "CHECK_IMPORT",
        DEPLOYING : "CHECK_DEPLOY"
    ]
    message.setProperty("ROUTE", routes[state] ?: "DONE")
    return message
}

// ---------------------------------------------------------------- export (Content Agent)

def Message buildExportRequest(Message message) {
    setJsonHeaders(message)
    message.setBody(fill(prop(message, "CAS_EXPORT_BODY_TEMPLATE"), message))
    return message
}

def Message handleExportResponse(Message message) {
    def opId = jsonPath(bodyJson(message), prop(message, "CAS_OPID_JSONPATH") ?: "id")
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
    String status = ((jsonPath(body, prop(message, "CAS_STATUS_JSONPATH") ?: "status") ?: "") as String).toUpperCase()
    message.setBody("")
    if (status in csvUpper(prop(message, "CAS_DONE_VALUES"), "COMPLETED,SUCCEEDED,SUCCESS")) {
        def trId = jsonPath(body, prop(message, "CAS_TRID_JSONPATH"))
        if (!trId) {
            fail(message, "Export concluído sem ID de transport request na resposta")
            return message
        }
        message.setProperty("TR_ID", trId as String)
        message.setProperty("NEXT", "START_IMPORT")
    } else if (status in csvUpper(prop(message, "CAS_FAILED_VALUES"), "FAILED,ERROR")) {
        fail(message, "Export falhou no Content Agent (status " + status + ")")
    }
    // qualquer outro status: ainda em andamento, nada a fazer neste ciclo
    return message
}

// ---------------------------------------------------------------- contingência (sem API de export)

/** O número do chamado aparece na descrição como palavra inteira? (RITM0010011 não casa com RITM00100119) */
boolean mentions(Object description, String ticket) {
    if (!ticket) return false
    String pattern = "(?<![A-Za-z0-9])" + java.util.regex.Pattern.quote(ticket) + "(?![A-Za-z0-9])"
    return (((description ?: "") as String) =~ pattern).find()
}

def Message findTrByTicket(Message message) {
    def body = bodyJson(message)
    List list = (body instanceof List) ? body : ((body.transportRequests ?: []) as List)
    String ticket = prop(message, "NUMBER")
    List<String> importable = csvUpper(prop(message, "CTMS_IMPORTABLE_VALUES"), "INITIAL,IN,REPEATABLE,RE")
    List<String> running = csvUpper(prop(message, "CTMS_RUNNING_VALUES"), "RUNNING,RU")
    List<String> imported = csvUpper(prop(message, "CTMS_IMPORTED_VALUES"), "SUCCEEDED,SU")
    List<String> broken = csvUpper(prop(message, "CTMS_ACTION_FAILED_VALUES"), "ERROR,ER,FATAL,FA")

    Closure<String> statusOf = { tr -> ((tr.status ?: "") as String).toUpperCase() }
    List mine = list.findAll { tr -> mentions(tr.description, ticket) }
    List ready = mine.findAll { tr -> !statusOf(tr) || statusOf(tr) in importable }
    message.setBody("")

    if (ready.size() > 1) {
        fail(message, "Há mais de uma request importável na fila com " + ticket + " na descrição")
    } else if (ready.size() == 1) {
        message.setProperty("TR_ID", ready[0].id as String)
        message.setProperty("NEXT", "START_IMPORT")
    } else if (mine.any { tr -> statusOf(tr) in running }) {
        // import já em andamento (disparado por outro ciclo ou por alguém na tela do cTMS): espera
    } else if (mine.any { tr -> statusOf(tr) in imported }) {
        // A request já foi importada, mas o chamado não ficou sabendo (por exemplo, o ServiceNow
        // estava fora do ar na hora de gravar). Segue direto para o deploy.
        def done = mine.findAll { tr -> statusOf(tr) in imported }.max { tr -> (tr.id as String).isLong() ? (tr.id as long) : 0L }
        message.setProperty("TR_ID", done.id as String)
        message.setProperty("TR_RECOVERED", "true")
        message.setProperty("NEXT", "START_DEPLOY")
    } else if (mine.any { tr -> statusOf(tr) in broken }) {
        def bad = mine.find { tr -> statusOf(tr) in broken }
        fail(message, "A request " + bad.id + " do chamado está com status " + statusOf(bad) + " na fila do nó de QAS no cTMS.")
    }
    // nenhuma: o dev ainda não clicou em Transport; espera o próximo ciclo
    return message
}

// ---------------------------------------------------------------- import (cTMS)

def Message buildImportRequest(Message message) {
    String tr = prop(message, "TR_ID")
    def trValue = tr.isLong() ? (tr as long) : tr
    setJsonHeaders(message)
    message.setProperty("CTMS_IMPORT_URL", prop(message, "CTMS_URI") + fill(prop(message, "CTMS_IMPORT_PATH") ?: DEFAULT_IMPORT_PATH, message))
    message.setBody(JsonOutput.toJson([
        namedUser        : prop(message, "CTMS_NAMED_USER") ?: "cpi-orquestracao",
        transportRequests: [trValue]
    ]))
    return message
}

def Message handleImportResponse(Message message) {
    def actionId = jsonPath(bodyJson(message), prop(message, "CTMS_ACTIONID_JSONPATH") ?: "actionId")
    message.setBody("")
    if (!actionId) {
        fail(message, "cTMS não devolveu o ID da ação de import")
        return message
    }
    moveTo(message, "IMPORTING",
           [u_cpi_tr_id: prop(message, "TR_ID"), u_cpi_action_id: actionId as String],
           "Import da request " + prop(message, "TR_ID") + " iniciado no nó de QAS (ação " + actionId + ").")
    message.setProperty("NEXT", "")
    return message
}

def Message evaluateImport(Message message) {
    String status = ((jsonPath(bodyJson(message), prop(message, "CTMS_ACTION_STATUS_JSONPATH") ?: "status") ?: "") as String).toUpperCase()
    message.setBody("")
    if (status in csvUpper(prop(message, "CTMS_ACTION_OK_VALUES"), "SUCCEEDED,SU,WARNING,WA")) {
        message.setProperty("NEXT", "START_DEPLOY")
    } else if (status in csvUpper(prop(message, "CTMS_ACTION_FAILED_VALUES"), "ERROR,ER,FATAL,FA")) {
        fail(message, "Import da request " + prop(message, "TR_ID") + " terminou em " + status +
                      " no cTMS. Veja o log da ação " + prop(message, "ACTION_ID") + ".")
    }
    return message
}

// ---------------------------------------------------------------- deploy (CPI QAS)

def Message handleDeployResponse(Message message) {
    message.setBody("")
    Map extra = prop(message, "TR_ID") ? [u_cpi_tr_id: prop(message, "TR_ID")] : [:]
    String recovered = prop(message, "TR_RECOVERED") == "true" ?
        "A request " + prop(message, "TR_ID") + " já estava importada no cTMS. " : ""
    moveTo(message, "DEPLOYING", extra, recovered + "Deploy do iFlow " + prop(message, "ARTIFACT_ID") + " disparado em QAS.")
    message.setProperty("NEXT", "")
    return message
}

def Message evaluateDeploy(Message message) {
    String raw = message.getBody(String) ?: ""
    def json = null
    try {
        json = raw.trim() ? new JsonSlurper().parseText(raw) : null
    } catch (Exception ignored) {
        // corpo que não é JSON (por exemplo XML, quando o header Accept não chegou)
    }
    int code = (message.getHeaders().get("CamelHttpResponseCode") ?: 0) as int
    if (code == 0) {
        // O header com o código HTTP não está disponível: deduz pela resposta.
        String err = (json instanceof Map && json.error) ? JsonOutput.toJson(json.error).toLowerCase() : ""
        if (json instanceof Map && json.d instanceof Map) code = 200
        else if (err.contains("not found") || err.contains("notfound")) code = 404
    }
    if (code == 404) {               // ainda não apareceu no runtime
        message.setBody("")
        return message
    }
    if (code == 200 && !(json instanceof Map && json.d instanceof Map)) {
        throw new IllegalStateException("Consulta do runtime em QAS não devolveu JSON (confira o header Accept: application/json " +
                                        "no Request Headers da chamada): " + raw.take(120))
    }
    if (code != 200) {
        // sem código conhecido, trata como passageiro: as tentativas contam e o motivo fica registrado
        String prefix = (code == 0 || code in TRANSIENT_HTTP) ? "TRANSIENT: " : ""
        throw new IllegalStateException(prefix + "Consulta do runtime em QAS retornou HTTP " + (code ?: "desconhecido") +
                                        (raw ? ": " + raw.take(200) : ""))
    }
    def d = json.d
    String status = ((d?.Status ?: "") as String).toUpperCase()
    String runtimeVersion = (d?.Version ?: "") as String
    String wanted = prop(message, "VERSION")
    String artifact = prop(message, "ARTIFACT_ID")
    message.setBody("")

    // O que está no runtime é o deploy deste chamado, ou um deploy antigo?
    Long deployedOn = parseDeployedOn(d?.DeployedOn)
    Long since = prop(message, "STAGE_SINCE") ? parseUtc(prop(message, "STAGE_SINCE")).getTime() : null
    boolean fresh = (deployedOn == null || since == null) ? true : (deployedOn >= since - CLOCK_SKEW_MS)

    if (status in ["ERROR", "FAILED"]) {
        fail(message, "iFlow " + artifact + " ficou em " + status + " em QAS. Veja Monitor > Manage Integration Content no CPI QAS.")
    } else if (status == "STARTED" && fresh) {
        if (wanted == "active" || runtimeVersion == wanted) {
            moveTo(message, "SUCCESS", [u_cpi_message: "STARTED " + runtimeVersion],
                   "iFlow " + artifact + " versão " + runtimeVersion + " em QAS com status STARTED " +
                   "(transport request " + prop(message, "TR_ID") + ").")
        } else {
            fail(message, "iFlow " + artifact + " subiu em QAS na versão " + runtimeVersion + ", mas o chamado pediu " + wanted +
                          ". O transporte leva a versão que estava salva em DEV no momento do export.")
        }
    }
    // STARTING, ou STARTED de um deploy antigo: espera o próximo ciclo
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

/** A exceção e as causas dela (o erro de um iFlow chamado pode chegar embrulhado em outra exceção). */
List causeChain(Object ex) {
    List chain = []
    def cur = ex
    while (cur != null && chain.size() < 10 && !chain.any { it.is(cur) }) {
        chain << cur
        cur = cur.getCause()
    }
    return chain
}

def Message handleError(Message message) {
    def ex = message.getProperty("CamelExceptionCaught")
    List chain = causeChain(ex)
    List<String> messages = chain.collect { (it.getMessage() ?: "") as String }.findAll { it }
    int code = 0
    String detail = messages ? messages[0] : (ex != null ? ex.toString() : "erro desconhecido")

    // Erro HTTP do adapter (a classe muda entre versões do runtime; testa pelo método)
    def httpEx = chain.find { it.metaClass.respondsTo(it, "getStatusCode") }
    if (httpEx != null) {
        code = httpEx.getStatusCode() as int
        String resp = httpEx.metaClass.respondsTo(httpEx, "getResponseBody") ? (httpEx.getResponseBody() ?: "") as String : ""
        detail = "HTTP " + code + ": " + resp.take(300)
    }

    logHeader(message, "TRP_Error", (prop(message, "NUMBER") ?: "?") + " / " + (prop(message, "STATE") ?: "?") + ": " + detail)

    if (!prop(message, "SYS_ID") || !prop(message, "SNOW_TOKEN")) {
        // Falhou antes de ler o chamado: não há o que atualizar no ServiceNow
        throw new IllegalStateException("Erro antes de identificar o chamado: " + detail)
    }
    String notSaved = messages.find { it.contains(WRITE_MARKER) }
    if (notSaved) {
        // O ServiceNow não está gravando os campos: tentar de novo só encheria o chamado de work notes.
        throw new IllegalStateException(notSaved.substring(notSaved.indexOf(WRITE_MARKER)))
    }

    boolean isTransient = (code in TRANSIENT_HTTP) ||
                          messages.any { it.startsWith("TRANSIENT") } ||
                          chain.any { it instanceof java.net.SocketTimeoutException || it instanceof java.net.ConnectException ||
                                      it instanceof java.util.concurrent.TimeoutException } ||
                          (code == 0 && chain.any { it instanceof IOException })
    // Import recusado: pode ser outro ciclo que chegou antes. O próximo ciclo confere a fila de novo.
    boolean importRejected = prop(message, "NEXT") == "START_IMPORT" && (code in IMPORT_REJECTED_HTTP)

    int retries = (prop(message, "RETRIES") ?: "0") as int
    int maxRetries = (prop(message, "MAX_RETRIES") ?: "5") as int

    if ((isTransient || importRejected) && retries + 1 < maxRetries) {
        String what = importRejected ? "O cTMS recusou o import da request " + prop(message, "TR_ID") + "; a fila será conferida de novo"
                                     : "Erro transitório no estado " + prop(message, "STATE")
        setUpdate(message, [u_cpi_retries: (retries + 1) as String],
                  what + " (tentativa " + (retries + 1) + " de " + maxRetries + "): " + detail)
    } else {
        fail(message, "Erro no estado " + prop(message, "STATE") + ": " + detail)
    }
    return buildUpdateRequest(message)
}
