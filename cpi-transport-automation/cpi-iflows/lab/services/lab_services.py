#!/usr/bin/env python3
"""
Laboratório do transporte CPI: simuladores HTTP dos sistemas externos.

Cada sistema sobe numa porta própria e fala HTTP de verdade com os iFlows:

  18080  ServiceNow   /oauth_token.do (grant jwt-bearer, JWT RS256 conferido com o
                      certificado X.509 cadastrado) e /api/now/table/<tabela>
  18081  XSUAA        /oauth/token (client credentials) dos service keys
  18082  cTMS         /v2/nodes, fila, import por ID, ações
  18084  CPI QAS      /api/v1/DeployIntegrationDesigntimeArtifact e IntegrationRuntimeArtifacts
  18085  Content Agent (Etapa B) - API HIPOTÉTICA, só para exercitar os scripts
  18090  Controle     /_lab/* (zerar, semear chamados, simular o clique em Transport,
                      injetar falhas, ler o estado e o registro de requisições)

IMPORTANTE: isto é um simulador escrito a partir da documentação pública e do que o
guia assume. Ele reproduz as regras que mais derrubam integrações (JWT, ACL, campos
ignorados em silêncio, filtros inválidos, 404 logo após o deploy), mas NÃO substitui
o teste no tenant de verdade. Ver lab/README.md.
"""
import base64
import json
import re
import sys
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

import jwt  # PyJWT
from cryptography import x509

LOCK = threading.RLock()
PORTS = {"snow": 18080, "xsuaa": 18081, "ctms": 18082, "cpiqas": 18084, "cas": 18085, "ctl": 18090}
SNOW_FMT = "%Y-%m-%d %H:%M:%S"
S = {}  # estado, recriado em reset()


def now_utc():
    return datetime.now(timezone.utc)


def reset(cfg=None):
    """Recria todo o estado. cfg traz o certificado e ajustes do cenário."""
    cfg = cfg or {}
    with LOCK:
        S.clear()
        S.update({
            "requests": [],          # tudo o que chegou em cada sistema
            "faults": [],            # falhas injetadas
            "seq": 1000,
            # ---------------- ServiceNow
            "snow": {
                "clients": {
                    "lab-client-id": {
                        "secret": "lab-client-secret",
                        "user_field": "user_name",
                        "clock_skew": 300,
                        "jti_check": True,
                        "verifier_maps": [{"kid": "trp-cpi-2026", "cert_pem": cfg.get("cert_pem", "")}],
                    }
                },
                "users": {"svc_cpi_transport": {"active": True, "roles": ["snc_internal", "itil"]}},
                "tokens": {},
                "seen_jti": set(),
                "invalid_query_returns_no_rows": False,
                # colunas existentes em sc_req_item (as u_cpi_* são as do Passo 3 do guia)
                "columns": {
                    "sys_id", "number", "state", "cat_item", "short_description", "assignment_group",
                    "sys_created_on", "work_notes",
                    "u_cpi_transport_status", "u_cpi_stage_since", "u_cpi_retries", "u_cpi_artifact_id",
                    "u_cpi_artifact_version", "u_cpi_package_id", "u_cpi_operation_id", "u_cpi_tr_id",
                    "u_cpi_action_id", "u_cpi_message",
                },
                "integer_columns": {"u_cpi_retries", "state"},
                "datetime_columns": {"u_cpi_stage_since", "sys_created_on"},
                "write_denied": set(),     # campos que o usuário não pode gravar (ACL)
                "records": {},             # sys_id -> registro
                "journal": {},             # sys_id -> [work notes]
            },
            # ---------------- XSUAA (service keys)
            "xsuaa": {
                "clients": {
                    "sb-ctms-lab": {"secret": "ctms-secret", "aud": "ctms", "scopes": ["transport.Import", "transport.Read"]},
                    "sb-cpiqas-lab": {"secret": "cpiqas-secret", "aud": "cpiqas",
                                      "scopes": ["WorkspacePackagesRead", "WorkspaceArtifactsDeploy", "MonitoringDataRead"]},
                    "sb-cas-lab": {"secret": "cas-secret", "aud": "cas", "scopes": ["Export"]},
                },
                "tokens": {},
            },
            # ---------------- cTMS
            "ctms": {
                "nodes": {1: {"id": 1, "name": "DEV"}, 2: {"id": 2, "name": "QAS"}},
                "queues": {1: [], 2: []},
                "actions": {},
                "import_polls": 1,        # consultas da ação que ainda respondem "running"
                "import_result": "succeeded",
                "status_style": "word",   # "word" (succeeded) ou "code" (SU)
            },
            # ---------------- CPI QAS
            "cpiqas": {
                "designtime": {},         # id -> {version, package, broken}
                "runtime": {},            # id -> {Version, Status, DeployedOn, polls}
                "deploy_404_polls": 1,
                "deploy_starting_polls": 1,
                "require_csrf": False,
            },
            # ---------------- Content Agent (hipotético)
            "cas": {"operations": {}, "export_polls": 1, "designtime_dev": {}},
        })
        for path, value in (cfg.get("set") or {}).items():
            set_path(path, value)


def set_path(path, value):
    """Ajusta um valor do estado, ex.: 'ctms.import_polls' = 3."""
    keys = path.split(".")
    node = S
    for k in keys[:-1]:
        node = node[int(k) if isinstance(node, dict) and k.isdigit() and int(k) in node else k]
    last = keys[-1]
    if isinstance(node.get(last), set):
        value = set(value)
    node[last] = value


def next_id():
    with LOCK:
        S["seq"] += 1
        return S["seq"]


# ------------------------------------------------------------------ ServiceNow

def snow_error(handler, status, message, detail=""):
    handler.reply(status, {"error": {"message": message, "detail": detail}, "status": "failure"})


def snow_token(handler, form):
    """POST /oauth_token.do - grant jwt-bearer do ServiceNow."""
    sn = S["snow"]

    def deny(reason):
        # O ServiceNow real responde de forma genérica; lab_reason existe só para o laboratório.
        handler.reply(401, {"error": "server_error", "error_description": "access_denied", "lab_reason": reason})

    if form.get("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer":
        return handler.reply(400, {"error": "unsupported_grant_type", "error_description": "grant_type inválido"})
    client = sn["clients"].get(form.get("client_id", ""))
    if not client or client["secret"] != form.get("client_secret"):
        return deny("client_id ou client_secret inválido")
    assertion = form.get("assertion")
    if not assertion:
        return deny("parâmetro 'assertion' ausente")
    try:
        header = jwt.get_unverified_header(assertion)
    except Exception as e:  # noqa
        return deny("JWT malformado: %s" % e)
    if header.get("alg") != "RS256":
        return deny("alg diferente de RS256")
    vmap = next((m for m in client["verifier_maps"] if m["kid"] == header.get("kid")), None)
    if not vmap:
        return deny("kid '%s' não existe no JWT Verifier Map" % header.get("kid"))
    try:
        public_key = x509.load_pem_x509_certificate(vmap["cert_pem"].encode()).public_key()
        claims = jwt.decode(assertion, public_key, algorithms=["RS256"], audience=form["client_id"],
                            leeway=client["clock_skew"], options={"require": ["exp", "aud", "iss", "sub"]})
    except Exception as e:  # assinatura, aud, exp, nbf...
        return deny("JWT recusado: %s: %s" % (type(e).__name__, e))
    if claims.get("iss") != form["client_id"]:
        return deny("iss diferente do client_id")
    if client["jti_check"]:
        jti = claims.get("jti")
        if not jti:
            return deny("jti ausente")
        if jti in sn["seen_jti"]:
            return deny("jti repetido (replay)")
        sn["seen_jti"].add(jti)
    user = sn["users"].get(claims.get("sub"))
    if client["user_field"] != "user_name" or not user or not user.get("active"):
        return deny("usuário do claim sub não encontrado ou inativo")
    token = "snow-" + uuid.uuid4().hex + uuid.uuid4().hex
    sn["tokens"][token] = {"user": claims["sub"], "exp": time.time() + 1799}
    handler.reply(200, {"access_token": token, "scope": "useraccount", "token_type": "Bearer", "expires_in": 1799})


def snow_user(handler):
    auth = handler.headers.get("Authorization", "")
    tok = S["snow"]["tokens"].get(auth[7:]) if auth.startswith("Bearer ") else None
    if not tok or tok["exp"] < time.time():
        snow_error(handler, 401, "User Not Authenticated", "Required to provide Auth information")
        return None
    return S["snow"]["users"][tok["user"]]


def snow_parse_query(q):
    """Devolve (condições, ordenação, inválido). Campo desconhecido torna a condição inválida."""
    sn = S["snow"]
    conds, order, invalid = [], None, False
    for term in [t for t in (q or "").split("^") if t]:
        if term.startswith("ORDERBYDESC"):
            order = (term[11:], True)
        elif term.startswith("ORDERBY"):
            order = (term[7:], False)
        else:
            m = re.match(r"^([a-z_0-9.]+)(!=|=|IN|ISEMPTY|ISNOTEMPTY)(.*)$", term)
            if not m or m.group(1) not in sn["columns"]:
                invalid = True
                continue
            conds.append(m.groups())
    return conds, order, invalid


def snow_match(rec, conds):
    for field, op, val in conds:
        cur = str(rec.get(field, "") or "")
        ok = {"=": cur == val, "!=": cur != val, "IN": cur in val.split(","),
              "ISEMPTY": cur == "", "ISNOTEMPTY": cur != ""}[op]
        if not ok:
            return False
    return True


def snow_view(rec, params):
    """Aplica sysparm_fields / display_value / exclude_reference_link."""
    out = dict(rec)
    out["work_notes"] = ""  # campo de diário: a Table API devolve vazio
    display = (params.get("sysparm_display_value") or "false") == "true"
    if display:
        # Com display_value=true as datas saem no fuso do usuário (aqui, Brasília) e não em UTC.
        for f in S["snow"]["datetime_columns"]:
            if out.get(f):
                d = datetime.strptime(out[f], SNOW_FMT) - timedelta(hours=3)
                out[f] = d.strftime("%d/%m/%Y %H:%M:%S")
    if (params.get("sysparm_exclude_reference_link") or "false") != "true" and out.get("cat_item"):
        out["cat_item"] = {"link": "http://lab/api/now/table/sc_cat_item/" + out["cat_item"], "value": out["cat_item"]}
    fields = params.get("sysparm_fields")
    if fields:
        out = {k: out.get(k, "") for k in fields.split(",") if k in S["snow"]["columns"]}
    return out


def snow_table(handler, method, table, sys_id, params, body):
    sn = S["snow"]
    user = snow_user(handler)
    if user is None:
        return
    if table != "sc_req_item":
        return snow_error(handler, 400, "Invalid table " + table)
    if "itil" not in user["roles"]:
        return snow_error(handler, 403, "User Not Authorized", "ACL Exception Read/Update Failed due to security constraints")

    if method == "GET" and not sys_id:
        conds, order, invalid = snow_parse_query(params.get("sysparm_query"))
        if invalid and sn["invalid_query_returns_no_rows"]:
            rows = []
        else:
            rows = [r for r in sn["records"].values() if snow_match(r, conds)]
        if order:
            rows.sort(key=lambda r: str(r.get(order[0], "")), reverse=order[1])
        limit = int(params.get("sysparm_limit") or 10000)
        return handler.reply(200, {"result": [snow_view(r, params) for r in rows[:limit]]})

    rec = sn["records"].get(sys_id)
    if rec is None:
        return snow_error(handler, 404, "No Record found", "Record doesn't exist or ACL restricts the record retrieval")
    if method == "GET":
        return handler.reply(200, {"result": snow_view(rec, params)})

    if method in ("PATCH", "PUT"):
        try:
            data = json.loads(body or "{}")
        except ValueError:
            return snow_error(handler, 400, "Exception while reading request", "The payload is not valid JSON.")
        for field, value in data.items():
            # Como no ServiceNow real: coluna inexistente ou sem permissão de escrita é ignorada EM SILÊNCIO.
            if field not in sn["columns"] or field in sn["write_denied"] or field in ("sys_id", "number"):
                continue
            value = "" if value is None else str(value)
            if field == "work_notes":
                if value:
                    sn["journal"].setdefault(sys_id, []).append(value)
                continue
            if field in sn["integer_columns"]:
                value = str(int(value)) if re.fullmatch(r"-?\d+", value) else ""
            if field in sn["datetime_columns"] and value:
                try:
                    datetime.strptime(value, SNOW_FMT)   # entrada interpretada como UTC (formato interno)
                except ValueError:
                    value = ""
            rec[field] = value
        snow_flow(rec)
        return handler.reply(200, {"result": snow_view(rec, params)})
    snow_error(handler, 405, "Method not supported")


def snow_flow(rec):
    """O que o Flow do item faz quando a automação termina (seção 6.4 do guia)."""
    st = rec.get("u_cpi_transport_status")
    if st == "SUCCESS":
        rec["state"] = "3"                       # Closed Complete
    elif st == "FAILED":
        rec["assignment_group"] = "suporte-integracao"


# ------------------------------------------------------------------ XSUAA

def xsuaa_token(handler, form):
    cid, secret = form.get("client_id"), form.get("client_secret")
    auth = handler.headers.get("Authorization", "")
    if auth.startswith("Basic "):
        cid, _, secret = base64.b64decode(auth[6:]).decode().partition(":")
    client = S["xsuaa"]["clients"].get(cid or "")
    if form.get("grant_type") != "client_credentials":
        return handler.reply(400, {"error": "unsupported_grant_type"})
    if not client or client["secret"] != secret:
        return handler.reply(401, {"error": "unauthorized", "error_description": "Bad credentials"})
    token = "xs-" + uuid.uuid4().hex
    S["xsuaa"]["tokens"][token] = {"aud": client["aud"], "scopes": list(client["scopes"]), "exp": time.time() + 43199}
    handler.reply(200, {"access_token": token, "token_type": "bearer", "expires_in": 43199,
                        "scope": " ".join(client["scopes"]), "jti": uuid.uuid4().hex})


def xsuaa_check(handler, aud, scope=None):
    auth = handler.headers.get("Authorization", "")
    tok = S["xsuaa"]["tokens"].get(auth[7:]) if auth.startswith("Bearer ") else None
    if not tok or tok["aud"] != aud or tok["exp"] < time.time():
        handler.reply(401, {"error": "unauthorized", "error_description": "Token ausente, inválido ou de outro serviço"})
        return False
    if scope and scope not in tok["scopes"]:
        handler.reply(403, {"error": "insufficient_scope", "error_description": "Falta a role " + scope})
        return False
    return True


# ------------------------------------------------------------------ cTMS

def ctms_status(word):
    if S["ctms"]["status_style"] == "code":
        return {"initial": "IN", "running": "RU", "succeeded": "SU", "error": "ER", "fatal": "FA", "repeatable": "RE"}[word]
    return word


def ctms_tr_view(tr):
    out = {k: v for k, v in tr.items() if k != "content"}
    out["status"] = ctms_status(tr["status"])
    return out


def ctms_finish(action):
    """Conclui a ação de import: muda a request e entrega o conteúdo no design-time do CPI QAS."""
    result = S["ctms"]["import_result"]
    action["status"] = result
    action["endedAt"] = now_utc().isoformat()
    for tr in S["ctms"]["queues"][action["nodeId"]]:
        if tr["id"] in action["transportRequests"]:
            tr["status"] = result
            tr["importedBy"] = action["triggeredBy"]
            tr["importedAt"] = action["endedAt"]
            if result == "succeeded" and tr.get("content"):
                c = tr["content"]
                S["cpiqas"]["designtime"][c["artifact"]] = {"version": c["version"], "package": c["package"],
                                                             "broken": c.get("broken", False)}


def ctms(handler, method, path, params, body):
    if not xsuaa_check(handler, "ctms"):
        return
    ct = S["ctms"]
    if method == "GET" and path == "/v2/nodes":
        return handler.reply(200, {"nodes": list(ct["nodes"].values())})

    m = re.fullmatch(r"/v2/nodes/(\d+)/transportRequests", path)
    if m and method == "GET":
        node = int(m.group(1))
        if node not in ct["nodes"]:
            return handler.reply(404, {"message": "Node %d not found" % node})
        # o import anda sozinho no cTMS, mesmo que ninguém consulte a ação
        for action in ct["actions"].values():
            if action["status"] == "running" and action["nodeId"] == node:
                action["polls"] += 1
                if action["polls"] > ct["import_polls"]:
                    ctms_finish(action)
        rows = [ctms_tr_view(t) for t in ct["queues"][node]]
        if params.get("status"):
            wanted = params["status"].lower().split(",")
            rows = [t for t in rows if t["status"].lower() in wanted]
        return handler.reply(200, {"transportRequests": rows})

    m = re.fullmatch(r"/v2/nodes/(\d+)/transportRequests/import", path)
    if m and method == "POST":
        node = int(m.group(1))
        try:
            data = json.loads(body or "{}")
        except ValueError:
            return handler.reply(400, {"message": "Corpo não é JSON"})
        if node not in ct["nodes"]:
            return handler.reply(404, {"message": "Node %d not found" % node})
        if not data.get("namedUser"):
            return handler.reply(400, {"message": "namedUser is mandatory"})
        ids = data.get("transportRequests") or []
        if not ids:
            return handler.reply(400, {"message": "transportRequests is mandatory"})
        by_id = {t["id"]: t for t in ct["queues"][node]}
        for i in ids:
            if not isinstance(i, int):
                return handler.reply(400, {"message": "transportRequests must contain numeric IDs"})
            if i not in by_id:
                return handler.reply(404, {"message": "Transport request %s not found in node %d" % (i, node)})
            if by_id[i]["status"] not in ("initial", "repeatable"):
                return handler.reply(400, {"message": "Transport request %s is not importable (status %s)" % (i, by_id[i]["status"])})
        aid = next_id()
        for i in ids:
            by_id[i]["status"] = "running"
        ct["actions"][aid] = {"actionId": aid, "type": "I", "status": "running", "nodeId": node,
                              "triggeredBy": data["namedUser"], "transportRequests": ids,
                              "startedAt": now_utc().isoformat(), "endedAt": None, "polls": 0}
        if ct["import_polls"] <= 0:
            ctms_finish(ct["actions"][aid])
        return handler.reply(200, {"actionId": aid, "monitoringURL": "http://lab/v2/actions/%d" % aid})

    m = re.fullmatch(r"/v2/actions/(\d+)", path)
    if m and method == "GET":
        action = ct["actions"].get(int(m.group(1)))
        if not action:
            return handler.reply(404, {"message": "Action not found"})
        if action["status"] == "running":
            action["polls"] += 1
            if action["polls"] > ct["import_polls"]:
                ctms_finish(action)
        out = {k: v for k, v in action.items() if k != "polls"}
        out["status"] = ctms_status(action["status"])
        return handler.reply(200, out)
    handler.reply(404, {"message": "Recurso não existe: " + path})


# ------------------------------------------------------------------ CPI QAS (OData API)

def odata_error(handler, status, code, message):
    handler.reply(status, {"error": {"code": code, "message": {"lang": "en", "value": message}}})


def cpiqas(handler, method, path, params, body):
    cq = S["cpiqas"]
    if path == "/api/v1/DeployIntegrationDesigntimeArtifact" and method == "POST":
        if not xsuaa_check(handler, "cpiqas", "WorkspaceArtifactsDeploy"):
            return
        if cq["require_csrf"] and not handler.headers.get("X-CSRF-Token"):
            return handler.reply_text(403, "CSRF token validation failed")
        m = re.fullmatch(r"'([^']+)'", params.get("Id", ""))
        if not m or params.get("Version") not in ("'active'",) and not re.fullmatch(r"'[\d.]+'", params.get("Version", "")):
            return odata_error(handler, 400, "Bad Request", "Parâmetros Id e Version devem vir entre aspas simples")
        art = m.group(1)
        dt = cq["designtime"].get(art)
        if not dt:
            return odata_error(handler, 404, "Not Found", "Integration design time artifact not found")
        old = cq["runtime"].get(art)
        cq["runtime"][art] = {"Version": dt["version"], "broken": dt.get("broken", False), "polls": 0,
                              "DeployedOn": int(time.time() * 1000), "Status": "STARTING",
                              # enquanto a versão nova não sobe, o runtime continua mostrando a antiga
                              "old": dict(old, old=None) if old and old.get("Status") == "STARTED" else None}
        return handler.reply_text(202, uuid.uuid4().hex)

    m = re.fullmatch(r"/api/v1/IntegrationRuntimeArtifacts\('([^']+)'\)", path)
    if m and method == "GET":
        if not xsuaa_check(handler, "cpiqas", "MonitoringDataRead"):
            return
        art = m.group(1)
        rt = cq["runtime"].get(art)
        if rt is not None and "polls" in rt:
            rt["polls"] += 1
            if rt["polls"] <= cq["deploy_404_polls"]:
                rt = rt.get("old")                      # ainda não trocou: versão antiga, ou 404
            elif rt["polls"] > cq["deploy_404_polls"] + cq["deploy_starting_polls"]:
                rt["Status"] = "ERROR" if rt.get("broken") else "STARTED"
        if rt is None:
            return odata_error(handler, 404, "Not Found", "Integration Runtime Artifact not found")
        if "json" not in handler.headers.get("Accept", ""):
            # Sem Accept: application/json a API OData responde em Atom/XML.
            return handler.reply_text(200, "<?xml version='1.0'?><entry><id>%s</id><m:properties/></entry>" % art,
                                      "application/atom+xml;type=entry;charset=utf-8")
        return handler.reply(200, {"d": {"__metadata": {"type": "com.sap.hci.api.IntegrationRuntimeArtifact"},
                                         "Id": art, "Version": rt["Version"], "Name": art, "Type": "INTEGRATION_FLOW",
                                         "DeployedBy": rt.get("DeployedBy", "sb-cpiqas-lab"),
                                         "DeployedOn": "/Date(%d)/" % rt["DeployedOn"], "Status": rt["Status"]}})
    handler.reply(404, {"error": "Recurso não existe: " + path})


# ------------------------------------------------------------------ Content Agent (HIPOTÉTICO)

def cas(handler, method, path, params, body):
    """API inventada para a Etapa B. O contrato real precisa ser confirmado no tenant."""
    if not xsuaa_check(handler, "cas", "Export"):
        return
    c = S["cas"]
    if method == "POST" and path == "/lab/export":
        data = json.loads(body or "{}")
        dt = c["designtime_dev"].get(data.get("artifactId"))
        if not dt:
            return handler.reply(404, {"message": "Conteúdo não encontrado em DEV"})
        op = "op-%d" % next_id()
        c["operations"][op] = {"id": op, "status": "RUNNING", "polls": 0, "data": data, "dt": dt}
        return handler.reply(202, {"id": op})
    m = re.fullmatch(r"/lab/export/(op-\d+)", path)
    if m and method == "GET":
        op = c["operations"].get(m.group(1))
        if not op:
            return handler.reply(404, {"message": "Operação não encontrada"})
        if op["status"] == "RUNNING":
            op["polls"] += 1
            if op["polls"] > c["export_polls"]:
                tr_id = next_id()
                S["ctms"]["queues"][2].append(new_tr(tr_id, op["data"].get("description", ""), {
                    "artifact": op["data"]["artifactId"], "package": op["data"].get("packageId"),
                    "version": op["dt"]["version"]}))
                op.update(status="COMPLETED", transportRequestId=tr_id)
        return handler.reply(200, {k: v for k, v in op.items() if k not in ("polls", "data", "dt")})
    handler.reply(404, {"message": "Recurso não existe: " + path})


def new_tr(tr_id, description, content, status="initial"):
    return {"id": tr_id, "status": status, "archived": False, "position": tr_id, "createdBy": "dev@lab",
            "importedBy": None, "importedAt": None, "description": description, "origin": "DEV",
            "entries": [{"id": tr_id, "storageType": "FILE", "contentType": "MTA", "uri": "%d" % tr_id}],
            "content": content}


# ------------------------------------------------------------------ controle do laboratório

def control(handler, method, path, params, body):
    data = json.loads(body) if body else {}
    if path == "/_lab/reset":
        reset(data)
        return handler.reply(200, {"ok": True})
    if path == "/_lab/set":                     # {"ctms.import_polls": 3, ...}
        for k, v in data.items():
            set_path(k, v)
        return handler.reply(200, {"ok": True})
    if path == "/_lab/snow/record":             # cria ou altera um chamado, como um administrador faria
        sn = S["snow"]
        sys_id = data.get("sys_id") or uuid.uuid4().hex
        rec = sn["records"].setdefault(sys_id, {c: "" for c in sn["columns"] if c != "work_notes"})
        rec.update(data)
        rec["sys_id"] = sys_id
        rec.setdefault("sys_created_on", "")
        if not rec["sys_created_on"]:
            rec["sys_created_on"] = now_utc().strftime(SNOW_FMT)
        return handler.reply(200, {"sys_id": sys_id})
    if path == "/_lab/ctms/transport":          # o dev clicou em Transport no CPI DEV
        tr_id = next_id()
        S["ctms"]["queues"][int(data.get("node", 2))].append(
            new_tr(tr_id, data.get("description", ""), data.get("content"), data.get("status", "initial")))
        return handler.reply(200, {"id": tr_id})
    if path == "/_lab/fault":                   # {"system","method","path_regex","status","body","times"}
        S["faults"].append(dict(data))
        return handler.reply(200, {"ok": True})
    if path == "/_lab/state":
        def clean(o):
            if isinstance(o, dict):
                return {str(k): clean(v) for k, v in o.items()}
            if isinstance(o, (set, list)):
                return [clean(v) for v in o]
            return o
        return handler.reply(200, clean({k: v for k, v in S.items()}))
    handler.reply(404, {"error": "controle desconhecido"})


# ------------------------------------------------------------------ servidor HTTP

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    system = "?"

    def log_message(self, *a):
        pass

    def reply(self, status, obj):
        self.reply_text(status, json.dumps(obj), "application/json;charset=UTF-8")

    def reply_text(self, status, text, ctype="text/plain;charset=UTF-8"):
        raw = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        if self.system != "ctl":
            self.send_header("Set-Cookie", "lab_session=%s; Path=/; HttpOnly" % uuid.uuid4().hex[:8])
            self.send_header("X-Lab-System", self.system)
        self.end_headers()
        self.wfile.write(raw)
        self._status = status

    def handle_any(self):
        method = self.command
        split = urlsplit(self.path)
        path = unquote(split.path)
        params = {k: v[0] for k, v in parse_qs(split.query, keep_blank_values=True).items()}
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8") if length else ""
        self._status = 0
        with LOCK:
            try:
                if self.system == "ctl":
                    return control(self, method, path, params, body)
                entry = {"system": self.system, "method": method, "path": path, "raw_query": split.query,
                         "params": params, "headers": {k: v for k, v in self.headers.items()}, "body": body}
                S["requests"].append(entry)
                for f in S["faults"]:
                    if f.get("times", 1) > 0 and f["system"] == self.system and f.get("method", method) == method \
                            and re.search(f.get("path_regex", "."), path):
                        f["times"] = f.get("times", 1) - 1
                        self.reply_text(int(f["status"]), f.get("body", '{"error":"falha injetada pelo laboratório"}'),
                                        "application/json")
                        entry["status"] = self._status
                        return
                self.route(method, path, params, body)
                entry["status"] = self._status
            except Exception as e:  # erro do próprio simulador
                import traceback
                traceback.print_exc()
                self.reply(599, {"lab_bug": "%s: %s" % (type(e).__name__, e)})

    def route(self, method, path, params, body):
        ctype = self.headers.get("Content-Type", "")
        if self.system == "snow":
            if path == "/oauth_token.do" and method == "POST":
                if "application/x-www-form-urlencoded" not in ctype:
                    return self.reply(400, {"error": "invalid_request", "error_description": "Content-Type deve ser form-urlencoded"})
                return snow_token(self, {k: v[0] for k, v in parse_qs(body).items()})
            m = re.fullmatch(r"/api/now/table/([a-z_0-9]+)(?:/([0-9a-f]{32}))?", path)
            if m:
                if method in ("PATCH", "PUT") and "application/json" not in ctype:
                    return snow_error(self, 415, "Unsupported Media Type", "Content-Type deve ser application/json")
                return snow_table(self, method, m.group(1), m.group(2), params, body)
            return snow_error(self, 404, "Requested URI does not represent any resource: " + path)
        if self.system == "xsuaa":
            if path == "/oauth/token" and method == "POST":
                return xsuaa_token(self, {k: v[0] for k, v in parse_qs(body).items()})
            return self.reply(404, {"error": "not_found"})
        if self.system == "ctms":
            return ctms(self, method, path, params, body)
        if self.system == "cpiqas":
            return cpiqas(self, method, path, params, body)
        if self.system == "cas":
            return cas(self, method, path, params, body)

    do_GET = do_POST = do_PATCH = do_PUT = do_DELETE = handle_any


def main():
    reset()
    servers = []
    for name, port in PORTS.items():
        cls = type("H_" + name, (Handler,), {"system": name})
        srv = ThreadingHTTPServer(("127.0.0.1", port), cls)
        srv.daemon_threads = True
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        servers.append(srv)
    print("lab services prontos: " + ", ".join("%s=%d" % kv for kv in PORTS.items()), flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
