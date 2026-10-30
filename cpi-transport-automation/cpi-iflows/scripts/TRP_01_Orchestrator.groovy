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
