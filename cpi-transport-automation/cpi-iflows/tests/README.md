# Testes locais dos scripts

Estes testes rodam os scripts de `../scripts/` fora do CPI, com classes que simulam as APIs do CPI (`stubs/`). Servem para conferir a lógica depois de qualquer alteração nos scripts. Eles **não** substituem os testes no tenant.

Pré-requisitos: Java 11 ou superior e o JAR do Groovy 2.4 (`groovy-all-2.4.21.jar`, do Maven Central).

```bash
# dentro de cpi-iflows/tests
mkdir -p build
java -cp groovy-all-2.4.21.jar org.codehaus.groovy.tools.FileSystemCompiler -d build $(find stubs -name "*.groovy")
java -cp groovy-all-2.4.21.jar:build groovy.ui.GroovyMain ScriptsTest.groovy ../scripts
```

Saída esperada: `OK: <n> verificacoes passaram`.

O que os testes cobrem: assinatura e campos do JWT, montagem da busca no ServiceNow, todas as mudanças de estado, prazos, contingência `MANUAL_TR`, checagem do deploy (versão e data), classificação de erros e os padrões dos parâmetros opcionais.
