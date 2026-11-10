package lab

import com.sap.gateway.ip.core.customdev.util.Message
import org.apache.camel.CamelContext
import org.apache.camel.Exchange
import org.apache.camel.Predicate
import org.apache.camel.Processor
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.ahc.AhcComponent
import org.apache.camel.component.ahc.AhcEndpoint
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.spi.HeaderFilterStrategy
import org.apache.camel.support.DefaultExchange

import java.security.PrivateKey

/**
 * Comportamentos do tenant que a documentação pública não deixa 100% claros.
 * O laboratório roda todos os cenários em TODAS as combinações: o procedimento só
 * passa se funcionar em qualquer uma delas.
 */
class Modes {
    /** Propriedades do Exchange atravessam o ProcessDirect? (o guia assume que NÃO) */
    boolean pdShareProperties = false
    /** O ProcessDirect só repassa os headers liberados em Allowed Header(s)? */
    boolean pdOnlyAllowedHeaders = true
    /** Os headers do iFlow chamado voltam para o chamador? */
    boolean pdHeadersBack = true
    /** O adapter HTTP copia os headers da resposta para a mensagem? (campo Response Headers) */
    boolean httpCopyResponseHeaders = true
    /** O header CamelHttpResponseCode fica disponível depois da chamada? */
    boolean httpResponseCodeHeader = true
    /** O erro de um iFlow chamado chega embrulhado em outra exceção? */
    boolean wrapExceptions = false

    static List<Modes> all() {
        List<Modes> out = []
        (0..<64).each { int n ->
            out << new Modes(pdShareProperties: (n & 1) != 0, pdOnlyAllowedHeaders: (n & 2) != 0,
                             pdHeadersBack: (n & 4) != 0, httpCopyResponseHeaders: (n & 8) != 0,
                             httpResponseCodeHeader: (n & 16) != 0, wrapExceptions: (n & 32) != 0)
        }
        return out
    }

    /** A combinação que o guia assume, cada comportamento trocado sozinho, e todos trocados juntos. */
    static List<Modes> extremes() {
        List<Modes> every = all()
        Modes d = new Modes()
        int base = every.findIndexOf { it.toString() == d.toString() }
        ([base] + (0..5).collect { base ^ (1 << it) } + [base ^ 63]).collect { every[it] }
    }

    String toString() {
        "props=" + (pdShareProperties ? "atravessam" : "isoladas") +
        " headersPD=" + (pdOnlyAllowedHeaders ? "so-liberados" : "todos") +
        " voltaPD=" + (pdHeadersBack ? "sim" : "nao") +
        " respHeaders=" + (httpCopyResponseHeaders ? "sim" : "nao") +
        " respCode=" + (httpResponseCodeHeader ? "sim" : "nao") +
        " excecao=" + (wrapExceptions ? "embrulhada" : "original")
    }
}

/** Message Processing Log: o que apareceria em Monitor Message Processing. */
class Mpl {
    String iflow
    String status = "PROCESSING"
    String error
    String applicationId
    Map<String, List<String>> customHeaders = [:]

    void addCustomHeaderProperty(String name, String value) {
        if (!customHeaders.containsKey(name)) customHeaders[name] = []
        customHeaders[name] << value
    }
    void setStringProperty(String name, String value) { }
    void addAttachmentAsString(String name, String text, String mediaType) { }
    String toString() { iflow + " " + status + (applicationId ? " [" + applicationId + "]" : "") + (error ? " - " + error : "") }
}

class MessageLogFactory {
    def getMessageLog(Object message) { ((Message) message).@exchange.getProperty("LAB_MPL") }
}

/** Erro genérico usado quando o modo "exceção embrulhada" está ligado. */
class WrappedFlowException extends RuntimeException {
    WrappedFlowException(String m, Throwable cause) { super(m, cause) }
}

/** Campo Request Headers do adapter HTTP: só passam os nomes da lista. */
class AllowList implements HeaderFilterStrategy {
    Set<String> allowed
    AllowList(Collection<String> names) { allowed = names.collect { it.toLowerCase() } as Set }
    boolean applyFilterToCamelHeaders(String name, Object value, Exchange exchange) { !(name.toLowerCase() in allowed) }
    boolean applyFilterToExternalHeaders(String name, Object value, Exchange exchange) { false }
}

class LabRouteBuilder extends RouteBuilder {
    IFlow flow
    void configure() { flow.routes(this) }
}

/** O tenant do CPI de orquestração: iFlows implantados, Security Material, Keystore e monitor. */
class Tenant {
    Modes modes = new Modes()
    Map<String, IFlow> byAddress = [:]
    Map<String, Map> credentials = [:]
    Map<String, PrivateKey> keystore = [:]
    List<Mpl> mpls = Collections.synchronizedList(new ArrayList<Mpl>())
    private Map<String, Map> tokenCache = [:]
    org.asynchttpclient.AsyncHttpClient httpClient = new org.asynchttpclient.DefaultAsyncHttpClient(
        new org.asynchttpclient.DefaultAsyncHttpClientConfig.Builder().setRequestTimeout(60000).setReadTimeout(60000).build())

    void deploy(IFlow flow, Map<String, String> params) {
        flow.tenant = this
        flow.params = params
        flow.ctx = new DefaultCamelContext()
        // um único cliente HTTP por tenant (senão cada chamada criaria o seu)
        AhcComponent ahc = new AhcComponent()
        ahc.setClient(httpClient)
        flow.ctx.addComponent("ahc", ahc)
        flow.ctx.addRoutes(new LabRouteBuilder(flow: flow))
        flow.ctx.start()
        if (flow.address) byAddress[flow.address] = flow
    }

    void shutdown() {
        byAddress.values().each { it.ctx.stop() }
        httpClient.close()
    }

    /** Security Material do tipo OAuth2 Client Credentials: o CPI busca e guarda o token. */
    void clearTokenCache() { tokenCache.clear() }

    synchronized String oauthToken(String alias) {
        Map cred = credentials[alias]
        if (cred == null || cred.type != "oauth2cc") {
            throw new IllegalStateException("Credencial OAuth2 Client Credentials '" + alias + "' não existe no Security Material")
        }
        Map cached = tokenCache[alias]
        if (cached && (cached.exp as long) > System.currentTimeMillis()) return cached.token
        HttpURLConnection c = (HttpURLConnection) new URL(cred.tokenUrl as String).openConnection()
        c.setRequestMethod("POST")
        c.setDoOutput(true)
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("Authorization", "Basic " + ((cred.clientId + ":" + cred.clientSecret) as String).bytes.encodeBase64().toString())
        c.outputStream.withWriter { it << "grant_type=client_credentials" }
        if (c.responseCode != 200) {
            throw new IllegalStateException("Falha ao obter o token OAuth da credencial " + alias + ": HTTP " + c.responseCode)
        }
        def json = new groovy.json.JsonSlurper().parseText(c.inputStream.getText("UTF-8"))
        tokenCache[alias] = [token: json.access_token, exp: System.currentTimeMillis() + ((json.expires_in as long) - 60) * 1000L]
        return json.access_token
    }
}

/** Base de um iFlow: os tipos de passo do editor do CPI, sobre rotas do Apache Camel. */
abstract class IFlow {
    Tenant tenant
    CamelContext ctx
    Map<String, String> params = [:]
    String name
    /** Address do Sender ProcessDirect (null se o iFlow começa por Timer). */
    String address
    /** Runtime Configuration > Allowed Header(s). */
    List<String> allowedHeaders = []
    String scriptFile
    Script compiled

    abstract void routes(RouteBuilder rb)

    /** Troca {{NOME}} pelo valor da tela Configure (lido na hora, para os cenários poderem mudar). */
    String resolveParams(String text) {
        (text ?: "").replaceAll(/\{\{([A-Za-z0-9_]+)\}\}/) { List<String> m ->
            if (!params.containsKey(m[1])) {
                throw new IllegalStateException(name + ": parâmetro externalizado " + m[1] + " sem valor na tela Configure")
            }
            return java.util.regex.Matcher.quoteReplacement(params[m[1]])
        }
    }

    /** Expressões do CPI (${property.X}, sinal =) na sintaxe Simple do Camel 3. */
    static String toCamelSimple(String cpi) {
        cpi.replace('${property.', '${exchangeProperty.').replaceAll(/(?<![!=<>])=(?!=)\s*'/, "== '")
    }

    String simple(String template, Exchange ex) {
        if (!template) return ""
        if (!template.contains('${')) return template
        ctx.resolveLanguage("simple").createExpression(toCamelSimple(template)).evaluate(ex, String)
    }

    /** Condição de rota do Router (Expression Type = Non-XML). */
    Predicate when(RouteBuilder rb, String condition) { rb.simple(toCamelSimple(condition)) }

    /** Passo Groovy Script: arquivo do iFlow + Script Function. */
    Processor script(String function) {
        IFlow self = this
        return { Exchange ex ->
            synchronized (self) {
                if (self.compiled == null) {
                    self.compiled = new GroovyShell(new Binding([messageLogFactory: new MessageLogFactory()])).parse(new File(self.scriptFile))
                }
            }
            if (!self.compiled.metaClass.respondsTo(self.compiled, function, Message)) {
                throw new IllegalStateException(self.name + ": Script Function '" + function + "' não existe em " + new File(self.scriptFile).name)
            }
            synchronized (self.compiled) {
                self.compiled.invokeMethod(function, [new Message(ex)] as Object[])
            }
        } as Processor
    }

    /** Content Modifier, aba Exchange Property, Source Type = Constant. */
    Processor contentModifier(Map<String, String> props) {
        IFlow self = this
        return { Exchange ex -> props.each { k, v -> ex.setProperty(k, self.resolveParams(v)) } } as Processor
    }

    /**
     * Request Reply + adapter HTTP (receiver).
     * cfg: address, query, method, requestHeaders ("A|B"), credential (OAuth2 Client Credentials) e throwOnFailure.
     */
    Processor http(Map cfg) {
        List<String> allow = ((cfg.requestHeaders ?: "") as String).split("\\|").findAll { it } as List
        boolean throwOnFailure = cfg.throwOnFailure as boolean
        String method = cfg.method as String
        IFlow self = this
        return { Exchange ex ->
            String address = self.simple(self.resolveParams(cfg.address as String), ex)
            String query = self.simple(self.resolveParams((cfg.query ?: "") as String), ex)
            String credential = cfg.credential ? self.resolveParams(cfg.credential as String) : null
            if (!address) throw new IllegalArgumentException(self.name + ": Address do adapter HTTP ficou vazio")
            // O laboratório fala HTTP simples com os simuladores locais.
            String url = address.replaceFirst('^https://127\\.0\\.0\\.1', 'http://127.0.0.1')
            def msg = ex.getIn()
            Set<String> before = msg.getHeaders().keySet().collect { it.toLowerCase() } as Set
            Object previousAuth = msg.getHeader("Authorization")
            List<String> sendable = new ArrayList<String>(allow)
            if (credential) {
                msg.setHeader("Authorization", "Bearer " + self.tenant.oauthToken(credential))
                sendable << "Authorization"
            }
            msg.setHeader(Exchange.HTTP_METHOD, method)
            if (query) msg.setHeader(Exchange.HTTP_QUERY, query) else msg.removeHeader(Exchange.HTTP_QUERY)

            AhcComponent component = self.ctx.getComponent("ahc", AhcComponent)
            AhcEndpoint endpoint = (AhcEndpoint) component.createEndpoint("ahc:" + url)
            endpoint.setThrowExceptionOnFailure(throwOnFailure)
            endpoint.setHeaderFilterStrategy(new AllowList(sendable))
            endpoint.start()
            def producer = endpoint.createProducer()
            producer.start()
            try {
                producer.process(ex)
            } finally {
                producer.stop()
                // o componente devolve a resposta na mensagem de saída; os passos seguintes a recebem como entrada
                if (ex.hasOut()) {
                    ex.setIn(ex.getOut())
                    ex.setOut(null)
                }
                msg = ex.getIn()
                msg.removeHeader(Exchange.HTTP_METHOD)
                msg.removeHeader(Exchange.HTTP_QUERY)
                if (credential) {
                    if (previousAuth != null) msg.setHeader("Authorization", previousAuth) else msg.removeHeader("Authorization")
                }
            }
            Modes modes = self.tenant.modes
            new ArrayList<String>(msg.getHeaders().keySet()).each { String h ->
                boolean isCode = h.equalsIgnoreCase(Exchange.HTTP_RESPONSE_CODE)
                boolean isNew = !(h.toLowerCase() in before)
                if (isCode && !modes.httpResponseCodeHeader) msg.removeHeader(h)
                else if (isNew && !isCode && !modes.httpCopyResponseHeaders) msg.removeHeader(h)
            }
            Exception failure = ex.getException()
            if (failure != null) {
                ex.setException(null)
                throw failure
            }
        } as Processor
    }

    /** Request Reply + adapter ProcessDirect (receiver). */
    Processor processDirect(String target) {
        IFlow self = this
        return { Exchange ex ->
            IFlow consumer = self.tenant.byAddress[target]
            if (consumer == null) {
                throw new IllegalStateException("ProcessDirect: nenhum iFlow implantado com o Address " + target)
            }
            Modes modes = self.tenant.modes
            Exchange sub = new DefaultExchange(consumer.ctx)
            sub.getIn().setBody(ex.getIn().getBody())
            ex.getIn().getHeaders().each { String k, Object v ->
                boolean allowed = consumer.allowedHeaders.any { it.equalsIgnoreCase(k) }
                if (allowed || !modes.pdOnlyAllowedHeaders) sub.getIn().setHeader(k, v)
            }
            if (modes.pdShareProperties) {
                ex.getProperties().each { String k, Object v -> if (!k.startsWith("Camel") && k != "LAB_MPL") sub.setProperty(k, v) }
            }
            consumer.run(sub)
            if (sub.getException() != null) {
                Throwable cause = sub.getException()
                if (modes.wrapExceptions) {
                    throw new WrappedFlowException("Erro no iFlow chamado por ProcessDirect (" + target + "): " + cause.getMessage(), cause)
                }
                throw cause
            }
            ex.getIn().setBody(sub.getIn().getBody())
            if (modes.pdHeadersBack) {
                sub.getIn().getHeaders().each { String k, Object v -> ex.getIn().setHeader(k, v) }
            }
            if (modes.pdShareProperties) {
                sub.getProperties().each { String k, Object v -> if (!k.startsWith("Camel") && k != "LAB_MPL") ex.setProperty(k, v) }
            }
        } as Processor
    }

    /** Executa o iFlow para uma mensagem e registra o resultado no monitor. */
    Exchange run(Exchange ex) {
        Mpl mpl = new Mpl(iflow: name)
        tenant.mpls << mpl
        ex.setProperty("LAB_MPL", mpl)
        def template = ctx.createProducerTemplate()
        try {
            template.send("direct:start", ex)
        } finally {
            template.stop()
        }
        Object appId = ex.getIn().getHeader("SAP_ApplicationID")
        mpl.applicationId = appId ? appId as String : null
        if (ex.getException() != null) {
            mpl.status = "FAILED"
            mpl.error = ex.getException().toString()
        } else {
            mpl.status = "COMPLETED"
        }
        return ex
    }

    /** Disparo do Timer: mensagem sem corpo. */
    Exchange fire() { run(new DefaultExchange(ctx)) }
}
