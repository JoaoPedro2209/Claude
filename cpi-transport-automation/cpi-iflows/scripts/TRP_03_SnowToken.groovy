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
