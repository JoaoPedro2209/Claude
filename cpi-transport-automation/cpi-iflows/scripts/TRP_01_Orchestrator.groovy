/*
 * TRP_01_Orchestrator.groovy
 * iFlow: IF_TRP_01_Orchestrator
 *
 * Funções usadas nos passos de Script do iFlow:
 *   prepareSearch   -> depois da chamada ao IF_TRP_03: guarda o token e monta a busca na Table API
 *   ticketsToLines  -> depois do GET na Table API (um chamado por linha, para o Iterating Splitter)
 *   lineToRequest   -> dentro do splitter, antes de chamar o IF_TRP_02
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

/** Campos do sc_req_item que o IF_TRP_02 precisa. */
@Field static final List<String> FIELDS = [
    "sys_id", "number",
    "u_cpi_transport_status", "u_cpi_stage_since", "u_cpi_retries",
    "u_cpi_artifact_id", "u_cpi_artifact_version", "u_cpi_package_id",
    "u_cpi_operation_id", "u_cpi_tr_id", "u_cpi_action_id"
]

String prop(Message m, String k) {
    String v = ((m.getProperty(k) ?: "") as String).trim()
    return v == "-" ? "" : v
}

/**
 * Lê {"access_token": "..."} devolvido pelo IF_TRP_03, guarda numa propriedade
 * e monta os headers e a query (já codificada) do GET na Table API.
 */
def Message prepareSearch(Message message) {
    String body = message.getBody(String)
    def json = body ? new JsonSlurper().parseText(body) : [:]
    if (!json.access_token) {
        throw new IllegalStateException("IF_TRP_03 não devolveu access_token")
    }
    String token = json.access_token as String
    message.setProperty("SNOW_TOKEN", token)

    if (!prop(message, "SNOW_CAT_ITEM_SYSID")) {
        throw new IllegalStateException("Parâmetro SNOW_CAT_ITEM_SYSID vazio")
    }
    String states = prop(message, "ACTIVE_STATES") ?: "PENDING,EXPORTING,WAITING_TR,IMPORTING,DEPLOYING"
    String encodedQuery = "cat_item=" + prop(message, "SNOW_CAT_ITEM_SYSID") +
                          "^u_cpi_transport_statusIN" + states.replace(" ", "") +
                          "^ORDERBYsys_created_on"
    String query = "sysparm_query=" + URLEncoder.encode(encodedQuery, "UTF-8") +
                   "&sysparm_fields=" + URLEncoder.encode(FIELDS.join(","), "UTF-8") +
                   "&sysparm_display_value=false" +
                   "&sysparm_exclude_reference_link=true" +
                   "&sysparm_limit=" + (prop(message, "SNOW_LIMIT") ?: "20")
    message.setProperty("SNOW_QUERY", query)

    message.setHeader("Authorization", "Bearer " + token)
    message.setHeader("Accept", "application/json")
    message.setBody("")
    return message
}

/**
 * Converte {"result":[{...},{...}]} em um JSON por linha.
 * O Iterating Splitter (Expression Type = Line Break) separa cada chamado.
 */
def Message ticketsToLines(Message message) {
    message.setHeader("Authorization", "")      // o token não segue adiante neste header
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
 * que o IF_TRP_02 lê e esvazia logo no primeiro passo.
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
