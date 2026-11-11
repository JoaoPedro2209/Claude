package lab

import org.apache.camel.builder.RouteBuilder

/*
 * Os cinco iFlows, passo a passo, exatamente como as tabelas do IMPLEMENTACAO-CPI.md mandam montar.
 * Se o guia mudar, este arquivo muda junto: é ele que prova que a montagem do guia funciona.
 */

/** Seção 9 - IF_TRP_04_SnowUpdate */
class IfTrp04 extends IFlow {
    IfTrp04(String scripts) {
        name = "IF_TRP_04_SnowUpdate"; address = "/trp/snow-update"
        allowedHeaders = ["X-Trp-Snow-Token"]; scriptFile = scripts + "/TRP_04_SnowUpdate.groovy"
    }
    void routes(RouteBuilder rb) {
        rb.from("direct:start").routeId(name)
          .process(script("prepareUpdate"))
          .process(http(address: 'https://{{SNOW_HOST}}/api/now/table/{{SNOW_TABLE}}/${property.SYS_ID}', method: "PATCH",
                        requestHeaders: "Authorization|Content-Type|Accept", throwOnFailure: true))
          .process(script("finishUpdate"))
    }
}

/** Seção 10 - IF_TRP_03_SnowToken */
class IfTrp03 extends IFlow {
    IfTrp03(String scripts) {
        name = "IF_TRP_03_SnowToken"; address = "/trp/snow-token"
        allowedHeaders = ["X-Trp-Snow-Token"]; scriptFile = scripts + "/TRP_03_SnowToken.groovy"
    }
    void routes(RouteBuilder rb) {
        rb.from("direct:start").routeId(name)
          .process(contentModifier([
              SNOW_CLIENT_CREDENTIAL: "{{SNOW_CLIENT_CREDENTIAL}}", SNOW_JWT_KEY_ALIAS: "{{SNOW_JWT_KEY_ALIAS}}",
              SNOW_JWT_KID: "{{SNOW_JWT_KID}}", SNOW_JWT_SUB: "{{SNOW_JWT_SUB}}",
              SNOW_ASSERTION_PARAM: "{{SNOW_ASSERTION_PARAM}}", SNOW_JWT_AUD: "{{SNOW_JWT_AUD}}",
              SNOW_JWT_ISS: "{{SNOW_JWT_ISS}}", SNOW_TOKEN_SCOPE: "{{SNOW_TOKEN_SCOPE}}"]))
          .process(script("buildTokenRequest"))
          .process(http(address: "{{SNOW_TOKEN_URL}}", method: "POST", requestHeaders: "Content-Type|Accept", throwOnFailure: true))
          .process(script("parseTokenResponse"))
    }
}

/** Seção 11 - IF_TRP_99_ConnectionTest */
class IfTrp99 extends IFlow {
    IfTrp99(String scripts) {
        name = "IF_TRP_99_ConnectionTest"
        allowedHeaders = ["X-Trp-Snow-Token"]; scriptFile = scripts + "/TRP_99_ConnectionTest.groovy"
    }
    void routes(RouteBuilder rb) {
        rb.from("direct:start").routeId(name)
          .process(contentModifier([TEST_SYS_ID: "{{TEST_SYS_ID}}"]))
          .process(processDirect("/trp/snow-token"))
          .process(script("afterToken"))
          .choice()
            .when(when(rb, '${property.DO_UPDATE} = \'true\''))
              .process(processDirect("/trp/snow-update"))
              .process(script("afterUpdate"))
          .end()
    }
}

/** Seção 12 (e 15, Etapa B) - IF_TRP_02_AdvanceTicket */
class IfTrp02 extends IFlow {
    /** Propriedades do Content Modifier "Config". Bloco A sempre; B e C quando o cenário pede. */
    Map<String, String> config = [
        EXPORT_MODE: "{{EXPORT_MODE}}", CTMS_URI: "{{CTMS_URI}}", CTMS_QAS_NODE_ID: "{{CTMS_QAS_NODE_ID}}",
        CTMS_NAMED_USER: "{{CTMS_NAMED_USER}}", MAX_RETRIES: "{{MAX_RETRIES}}",
        TIMEOUT_WAITING_TR_MIN: "{{TIMEOUT_WAITING_TR_MIN}}", TIMEOUT_IMPORT_MIN: "{{TIMEOUT_IMPORT_MIN}}",
        TIMEOUT_DEPLOY_MIN: "{{TIMEOUT_DEPLOY_MIN}}"]

    IfTrp02(String scripts) {
        name = "IF_TRP_02_AdvanceTicket"; address = "/trp/advance"
        allowedHeaders = ["X-Trp-Snow-Token"]; scriptFile = scripts + "/TRP_02_AdvanceTicket.groovy"
    }
    void routes(RouteBuilder rb) {
        // 12.7 Exception Subprocess: Error Start -> handleError -> /trp/snow-update -> End Message
        rb.onException(Throwable).handled(true)
          .process(script("handleError"))
          .process(processDirect("/trp/snow-update"))

        rb.from("direct:start").routeId(name)
          .process(contentModifier(config))
          .process(script("prepare"))
          // 12.3 Router "Rota"
          .choice()
            .when(when(rb, '${property.ROUTE} = \'CHECK_QUEUE\''))
              .process(http(address: '${property.CTMS_QUEUE_URL}', method: "GET", credential: "{{CRED_CTMS}}",
                            requestHeaders: "Accept", throwOnFailure: true))                                   // A
              .process(script("findTrByTicket"))
            .endChoice()
            .when(when(rb, '${property.ROUTE} = \'CHECK_IMPORT\''))
              .process(http(address: '${property.CTMS_ACTION_URL}', method: "GET", credential: "{{CRED_CTMS}}",
                            requestHeaders: "Accept", throwOnFailure: true))                                   // B
              .process(script("evaluateImport"))
            .endChoice()
            .when(when(rb, '${property.ROUTE} = \'CHECK_DEPLOY\''))
              .process(http(address: '{{CPI_QAS_URL}}/api/v1/IntegrationRuntimeArtifacts(%27${property.ARTIFACT_ID}%27)',
                            method: "GET", credential: "{{CRED_CPI_QAS}}", requestHeaders: "Accept", throwOnFailure: false))  // C
              .process(script("evaluateDeploy"))
            .endChoice()
            // 15.2 Etapa B
            .when(when(rb, '${property.ROUTE} = \'EXPORT\''))
              .process(script("buildExportRequest"))
              .process(http(address: '${property.CAS_EXPORT_URL}', method: "POST", credential: "{{CRED_CAS_DEV}}",
                            requestHeaders: "Content-Type|Accept", throwOnFailure: true))                      // G
              .process(script("handleExportResponse"))
            .endChoice()
            .when(when(rb, '${property.ROUTE} = \'CHECK_EXPORT\''))
              .process(http(address: '${property.CAS_STATUS_URL}', method: "GET", credential: "{{CRED_CAS_DEV}}",
                            requestHeaders: "Accept", throwOnFailure: true))                                   // H
              .process(script("evaluateExportStatus"))
            .endChoice()
          .end()
          // 12.4 Router "Próximo"
          .choice()
            .when(when(rb, '${property.NEXT} = \'START_IMPORT\''))
              .process(script("buildImportRequest"))
              .process(http(address: '${property.CTMS_IMPORT_URL}', method: "POST", credential: "{{CRED_CTMS}}",
                            requestHeaders: "Content-Type|Accept", throwOnFailure: true))                      // D
              .process(script("handleImportResponse"))
            .endChoice()
            .when(when(rb, '${property.NEXT} = \'START_DEPLOY\''))
              .process(http(address: '{{CPI_QAS_URL}}/api/v1/DeployIntegrationDesigntimeArtifact',
                            query: 'Id=%27${property.ARTIFACT_ID}%27&Version=%27active%27', method: "POST",
                            credential: "{{CRED_CPI_QAS}}", requestHeaders: "Accept", throwOnFailure: true))  // E
              .process(script("handleDeployResponse"))
            .endChoice()
          .end()
          // 12.5 Router "Atualizar?"
          .choice()
            .when(when(rb, '${property.UPDATE} = \'true\''))
              .process(script("buildUpdateRequest"))
              .process(processDirect("/trp/snow-update"))                                                      // F
          .end()
    }
}

/** Seção 13 - IF_TRP_01_Orchestrator */
class IfTrp01 extends IFlow {
    IfTrp01(String scripts) {
        name = "IF_TRP_01_Orchestrator"
        allowedHeaders = ["X-Trp-Snow-Token"]; scriptFile = scripts + "/TRP_01_Orchestrator.groovy"
    }
    void routes(RouteBuilder rb) {
        rb.from("direct:start").routeId(name)
          .process(contentModifier([TRP_ENABLED: "{{TRP_ENABLED}}", SNOW_CAT_ITEM_SYSID: "{{SNOW_CAT_ITEM_SYSID}}",
                                    SNOW_LIMIT: "{{SNOW_LIMIT}}"]))
          .choice().when(when(rb, '${property.TRP_ENABLED} != \'true\'')).stop().end()
          .process(processDirect("/trp/snow-token"))
          .process(script("prepareSearch"))
          .process(http(address: 'https://{{SNOW_HOST}}/api/now/table/{{SNOW_TABLE}}', query: '${property.SNOW_QUERY}',
                        method: "GET", requestHeaders: "Authorization|Accept", throwOnFailure: true))
          .process(script("ticketsToLines"))
          .choice().when(when(rb, '${property.TICKET_COUNT} = \'0\'')).stop().end()
          // 13.4 Iterating Splitter: Line Break, sem paralelismo, Stop on Exception desmarcado
          .split(rb.body().tokenize("\n"))
            .process(script("lineToRequest"))
            .process(processDirect("/trp/advance"))
          .end()
    }
}
