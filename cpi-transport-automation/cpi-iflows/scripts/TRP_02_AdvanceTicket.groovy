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
