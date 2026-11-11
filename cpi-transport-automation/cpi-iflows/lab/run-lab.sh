#!/usr/bin/env bash
# Laboratório do transporte CPI. Uso:
#   ./run-lab.sh            roda todos os cenários em todas as combinações de comportamento do tenant
#   ./run-lab.sh extremos   8 combinações: a do guia, cada comportamento trocado sozinho e todos trocados
#   ./run-lab.sh padrao     roda uma vez, na combinação que o guia assume (mostra cada falha)
#   ./run-lab.sh padrao t13 roda só os cenários cujo nome contém "t13"
# Precisa de: Java 11+, Maven, Python 3 (pip install pyjwt cryptography) e openssl.
set -euo pipefail
cd "$(dirname "$0")"
MODE="${1:-todas}"; ONLY="${2:-}"
SCRIPTS="$(cd ../scripts && pwd)"
B=.build; mkdir -p "$B/keys" "$B/classes"

# 1. Bibliotecas (Apache Camel 3.14 + Groovy 2.4)
[ -d .lib ] || mvn -q -B dependency:copy-dependencies -DoutputDirectory=.lib >/dev/null

# 2. Par de chaves e certificado X.509, como o Keystore do CPI gera. O certificado vai para o "ServiceNow".
if [ ! -f "$B/keys/trp_snow_jwt.pk8" ]; then
  for n in trp_snow_jwt outra; do
    openssl req -x509 -newkey rsa:2048 -nodes -keyout "$B/keys/$n.pem" -out "$B/keys/$n.crt" -days 30 -subj "/CN=$n" 2>/dev/null
    openssl pkcs8 -topk8 -nocrypt -in "$B/keys/$n.pem" -outform DER -out "$B/keys/$n.pk8"
  done
fi

# 3. Compila o executor (a classe Message do laboratório + as interfaces da API de scripts)
CP="$(ls .lib/*.jar | tr '\n' ':')"
STUBS=../tests/stubs/com/sap/it/api
JAVA_TOOL_OPTIONS= java -Dfile.encoding=UTF-8 -cp "$CP" org.codehaus.groovy.tools.FileSystemCompiler --encoding UTF-8 -d "$B/classes" \
  runner/com/sap/gateway/ip/core/customdev/util/Message.groovy \
  "$STUBS/ITApiFactory.groovy" "$STUBS/keystore/KeystoreService.groovy" \
  "$STUBS/securestore/SecureStoreService.groovy" "$STUBS/securestore/UserCredential.groovy" \
  runner/lab/Engine.groovy runner/lab/IFlows.groovy runner/lab/Scenarios.groovy

# 4. Sobe os simuladores
python3 -I services/lab_services.py > "$B/services.log" 2>&1 &
SVC=$!
trap 'kill $SVC 2>/dev/null || true' EXIT
for i in $(seq 1 50); do
  curl -s -o /dev/null --noproxy '*' -X POST -d '{}' http://127.0.0.1:18090/_lab/state && break
  sleep 0.2
done

# 5. Roda os cenários
JAVA_TOOL_OPTIONS= java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dhttp.nonProxyHosts='localhost|127.*' -cp "$CP$B/classes" lab.Scenarios "$SCRIPTS" "$B/keys" "$MODE" "$ONLY"
