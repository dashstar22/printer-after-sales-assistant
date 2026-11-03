"""Reproducible isolated portfolio runtime; Python 3.10+, Java 17, Docker Compose v2.

Creates only a new named Compose project. It never removes volumes, reseeds an
existing instance, imports local manuals, or sends email/notifications externally.
"""
from pathlib import Path
import argparse
import hashlib
import html
import http.cookiejar
import ipaddress
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import webbrowser
import zipfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / ".local-validation/portfolio"
FLAGS = getattr(subprocess, "CREATE_NO_WINDOW", 0)
CONFIG_KEYS = {"AI_API_KEY", "AI_CHAT_MODEL", "AI_BASE_URL", "KNOWLEDGE_EMBEDDING_BASE_URL",
               "KNOWLEDGE_RERANK_URL", "EXTERNAL_DAILY_CALLS", "EXTERNAL_DAILY_INPUT_BYTES"}


class Failure(RuntimeError):
    pass


def read(path):
    return json.loads(Path(path).read_text("utf-8"))


def write(path, value):
    path = Path(path)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2), "utf-8")
    temp.replace(path)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(args, **kwargs):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, creationflags=FLAGS, **kwargs)
    if result.returncode:
        raise Failure("COMMAND_FAILED: " + Path(str(args[0])).name)
    return result.stdout.decode("utf-8", errors="replace") if isinstance(result.stdout, bytes) else result.stdout


def wait(check, seconds=120):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        try:
            value = check()
            if value:
                return value
        except (OSError, ValueError, Failure):
            pass
        time.sleep(.5)
    raise Failure("DEPENDENCY_OR_APPLICATION_NOT_READY: inspect private logs")


def freeport():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def tcp(port):
    with socket.create_connection(("127.0.0.1", port), timeout=2):
        return True


def process(pid):
    if not pid:
        return None
    if os.name == "nt":
        script = f'Get-CimInstance Win32_Process -Filter "ProcessId={int(pid)}" | Select-Object CommandLine,CreationDate | ConvertTo-Json -Compress'
        value = run(["powershell.exe", "-NoProfile", "-Command", script]).strip()
        return json.loads(value) if value else None
    path = Path(f"/proc/{int(pid)}")
    try:
        return {"CommandLine": (path / "cmdline").read_bytes().replace(b"\0", b" ").decode(),
                "CreationDate": (path / "stat").read_text().split(") ", 1)[1].split()[19]}
    except (OSError, IndexError):
        return None


def owned(record):
    p = process(record.get("pid"))
    return p and p["CreationDate"] == record["created"] and record["marker"] in (p["CommandLine"] or "")


def spawn(args, marker, log, env):
    with Path(log).open("ab") as stream:
        child = subprocess.Popen(args, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                 env=env, creationflags=FLAGS, start_new_session=os.name != "nt")
    info = process(child.pid)
    if not info:
        raise Failure("PROCESS_EXITED: inspect private logs")
    return {"pid": child.pid, "created": info["CreationDate"], "marker": marker}


def clean_environment():
    # Do not inherit a prior evaluation's proxy, Flyway target, or static overrides.
    prefixes = ("AI_", "KNOWLEDGE_", "APP_", "SPRING_", "MYSQL_", "RABBITMQ_", "REDIS_",
                "EMAIL_", "EXTERNAL_", "AUTOMATION_", "CONTROLLED_", "ASSISTANT_", "SEED_",
                "ADMIN_INIT_", "SERVER_", "IDENTITY_", "SESSION_")
    return {k: v for k, v in os.environ.items() if not k.startswith(prefixes)}


def compose(c, *args):
    env = dict(os.environ, SECRETS_DIR=str(Path(c["folder"]) / "secrets"))
    for key, port in c["ports"].items():
        env["PORTFOLIO_" + key.upper() + "_PORT"] = str(port)
    # Required values of unused parent services are parsed by Compose extends.
    env.update(APP_IMAGE="portfolio-unused:local", SITE_HOST="portfolio.test", CADDYFILE=str(ROOT / "deploy/linux/Caddyfile"))
    env.update(PORTFOLIO_PRIVATE_NETWORK=c["project"] + "_data", PORTFOLIO_PRIVATE_SUBNET=c["subnets"][0], PORTFOLIO_HOST_SUBNET=c["subnets"][1])
    result = subprocess.run(["docker", "compose", "-p", c["project"], "-f", str(ROOT / "deploy/portfolio/compose.yaml"), *args],
                            cwd=ROOT, capture_output=True, env=env, creationflags=FLAGS)
    if result.returncode:
        with (Path(c["folder"]) / "compose-errors.log").open("ab") as log:
            log.write(result.stdout + result.stderr)
        raise Failure("COMPOSE_FAILED: inspect private compose-errors.log")
    return result.stdout.decode("utf-8", errors="replace")


def allocate_subnets():
    """Avoid exhausting Docker's default pool; never remove another project's network."""
    ids = run(["docker", "network", "ls", "-q"]).split()
    networks = json.loads(run(["docker", "network", "inspect", *ids])) if ids else []
    occupied = [ipaddress.ip_network(row["Subnet"], strict=False) for network in networks
                for row in (network.get("IPAM", {}).get("Config") or []) if row.get("Subnet")]
    selected = []
    for subnet in ipaddress.ip_network("10.240.0.0/16").subnets(new_prefix=24):
        if not any(subnet.version == item.version and subnet.overlaps(item) for item in occupied):
            selected.append(str(subnet))
        if len(selected) == 2:
            return selected
    raise Failure("NO_FREE_PORTFOLIO_SUBNET: inspect Docker network allocation")


def environment(c):
    folder = Path(c["folder"])
    if digest(c["jar"]) != c["jar_sha256"]:
        raise Failure("PACKAGE_HASH_MISMATCH")
    env = dict(clean_environment(), **c["env"])
    for key, filename in {"MYSQL_PASSWORD": "mysql_password", "REDIS_PASSWORD": "redis_password", "RABBITMQ_PASSWORD": "rabbit_password"}.items():
        env[key] = (folder / "secrets" / filename).read_text("utf-8").strip()
    if c["mode"] == "real":
        key = (folder / "secrets/ai_api_key").read_text("utf-8").strip()
        if not key:
            raise Failure("REAL_AI_API_KEY_REQUIRED")
        env.update(AI_API_KEY=key, KNOWLEDGE_EMBEDDING_API_KEY=key, KNOWLEDGE_RERANK_API_KEY=key)
    else:
        env.update(AI_API_KEY="offline-protocol", KNOWLEDGE_EMBEDDING_API_KEY="", KNOWLEDGE_RERANK_API_KEY="")
    return env


def stop(c, dependencies=False):
    folder = Path(c["folder"])
    state = read(folder / "state.json") if (folder / "state.json").exists() else {}
    for key in ("java", "supplier"):
        record = state.get(key, {})
        if record and owned(record):
            if os.name == "nt":
                run(["taskkill", "/PID", str(record["pid"]), "/T", "/F"])
            else:
                import signal
                os.kill(record["pid"], signal.SIGTERM)
                wait(lambda: not owned(record), 20)
    write(folder / "state.json", {})
    if dependencies:
        compose(c, "stop")


def start(c):
    folder = Path(c["folder"])
    env = environment(c)  # Missing credentials fail before any dependency starts.
    state = read(folder / "state.json") if (folder / "state.json").exists() else {}
    if state.get("java") and owned(state["java"]):
        wait(lambda: urllib.request.urlopen(state["base"] + "/api/auth/csrf", timeout=3).status == 200, 10)
        return state
    stop(c)
    compose(c, "up", "-d")
    wait(lambda: "healthy" in compose(c, "ps", "mysql", "--format", "{{.Health}}"))
    for name in ("redis", "rabbit", "qdrant", "smtp", "mail"):
        wait(lambda name=name: tcp(c["ports"][name]))
    state = {"base": "http://127.0.0.1:" + str(c["ports"]["app"])}
    try:
        if c["mode"] == "offline":
            state["supplier"] = spawn([sys.executable, str(ROOT / "scripts/portfolio_supplier.py"), str(folder), str(c["ports"]["protocol"])],
                                      str(folder), folder / "protocol.log", env)
            write(folder / "state.json", state)
            wait(lambda: tcp(c["ports"]["protocol"]))
        state["java"] = spawn([c["java"], "-jar", c["jar"]], c["jar"], folder / "application.log", env)
        write(folder / "state.json", state)
        wait(lambda: urllib.request.urlopen(state["base"] + "/api/auth/csrf", timeout=3).status == 200)
    except BaseException:
        stop(c)
        raise
    return state


class Client:
    def __init__(self, base):
        self.base, self.token = base, None
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf()

    def request(self, method, path, body=None, form=None, raw=None, content_type=None):
        data = raw if raw is not None else urllib.parse.urlencode(form).encode() if form is not None else json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        headers = {"Content-Type": content_type or ("application/x-www-form-urlencoded" if form is not None else "application/json")}
        if self.token:
            headers["X-CSRF-TOKEN"] = self.token
        try:
            with self.opener.open(urllib.request.Request(self.base + path, data=data, headers=headers, method=method), timeout=40) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as error:
            try:
                code = json.loads(error.read()).get("code", "UNKNOWN")
            except (ValueError, AttributeError):
                code = "UNKNOWN"
            raise Failure(f"HTTP_{error.code}_{code}: {method} {path}") from None

    def csrf(self):
        self.token = self.request("GET", "/api/auth/csrf")["token"]
        return self

    def login(self, username, password):
        self.request("POST", "/api/auth/login", form={"username": username, "password": password})
        return self.csrf()


def capture(c, path):
    with urllib.request.urlopen("http://127.0.0.1:" + str(c["ports"]["mail"]) + path, timeout=10) as response:
        return json.loads(response.read())


def token(c, email, purpose):
    def find():
        for item in capture(c, "/api/v1/messages?limit=500")["messages"]:
            if email in [x["Address"] for x in item.get("To", [])]:
                message = capture(c, "/api/v1/message/" + item["ID"])
                found = re.search(r"email-action=" + purpose + r"&token=([A-Za-z0-9_-]{43})", message["Text"])
                if found:
                    return found[1]
    return wait(find, 15)


def seed(c):
    """Public identity and administrative APIs; only first-admin is an offline command."""
    folder = Path(c["folder"])
    if c.get("initialized"):
        return
    state = start(c)
    base = state["base"]
    admin_role = c["roles"]["admin"]
    if not c.get("admin_initialized"):
        env = environment(c)
        env.update(ADMIN_INIT_USERNAME="portfolio_admin", ADMIN_INIT_PASSWORD=admin_role["password"], KNOWLEDGE_JOBS_ENABLED="false", KNOWLEDGE_HYBRID_ENABLED="false", AI_ENABLED="false")
        with (folder / "initialize-admin.log").open("ab") as log:
            result = subprocess.run([c["java"], "-jar", c["jar"], "--initialize-admin"], cwd=ROOT, env=env,
                                    stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
        if result.returncode:
            raise Failure("FIRST_ADMIN_INITIALIZATION_FAILED")
        c["admin_initialized"] = True
        write(folder / "runtime.json", c)
    if not admin_role.get("id"):
        admin = Client(base).login("portfolio_admin", admin_role["password"])
        admin.request("POST", "/api/auth/email-bind/request", {"email": admin_role["email"], "currentPassword": admin_role["password"]})
        admin.request("POST", "/api/auth/email-bind/confirm", {"token": token(c, admin_role["email"], "BIND")})
        admin = Client(base).login(admin_role["email"], admin_role["password"])
        admin_role["id"] = admin.request("GET", "/api/auth/me")["id"]
        write(folder / "runtime.json", c)
    admin = Client(base).login(admin_role["email"], admin_role["password"])
    for role in ("customer", "other", "staff"):
        account = c["roles"][role]
        if account.get("id"):
            continue
        client = Client(base)
        purpose = "INVITE" if role == "staff" else "REGISTER"
        if role == "staff":
            admin.request("POST", "/api/admin/email-invitations", {"email": account["email"], "requestId": str(uuid.uuid4())})
            path = "/api/auth/invitations/accept"
        else:
            client.request("POST", "/api/auth/register", {"email": account["email"]})
            path = "/api/auth/register/confirm"
        client.request("POST", path, {"token": token(c, account["email"], purpose), "password": account["password"], "passwordConfirmation": account["password"]})
        account["id"] = client.login(account["email"], account["password"]).request("GET", "/api/auth/me")["id"]
        write(folder / "runtime.json", c)
    if not c.get("model_id"):
        model = admin.request("POST", "/api/admin/models", {"modelCode": "PRINT-DEMO-01", "brand": "纸序虚构演示设备", "aliases": ["纸序演示一号"], "isDemo": True, "requestId": c["requests"]["model"]})
        c["model_id"] = model["id"]
        write(folder / "runtime.json", c)
    c.setdefault("order_ids", {})
    for role in ("customer", "other"):
        if role not in c["order_ids"]:
            order = admin.request("POST", "/api/admin/orders", {"orderNo": "DEMO-PORTFOLIO-" + role.upper(), "customerId": c["roles"][role]["id"], "modelId": c["model_id"], "serialNo": "SYNTHETIC-" + role.upper(), "purchaseDate": "2026-09-01", "isDemo": True, "verificationNote": "全新隔离环境的合成演示记录，不代表真实购买或客户", "requestId": c["requests"][role]})
            c["order_ids"][role] = order["id"]
            write(folder / "runtime.json", c)
    c["initialized"] = True
    write(folder / "runtime.json", c)
    write(folder / "initialization-receipt.json", {"status": "PASS", "mode": c["mode"], "project": c["project"], "jar_sha256": c["jar_sha256"], "identity_setup": "FIRST_ADMIN_COMMAND_THEN_REAL_HTTP_LOCAL_MAIL_CAPTURE", "synthetic_data": True, "external_emails": 0, "external_notifications": 0, "knowledge_publications": 0, "model_calls_during_setup": 0})


def init(args):
    settings = {}
    if args.config:
        for line in Path(args.config).read_text("utf-8-sig").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            key, separator, value = line.partition("=")
            if not separator or key.strip() not in CONFIG_KEYS:
                raise Failure("CONFIG_KEY_NOT_ALLOWED")
            settings[key.strip()] = value.strip()
    if args.mode == "real":
        settings["AI_API_KEY"] = settings.get("AI_API_KEY") or os.environ.get("AI_API_KEY") or os.environ.get("DASHSCOPE_API_KEY", "")
        if not settings["AI_API_KEY"]:
            raise Failure("REAL_AI_API_KEY_REQUIRED: use --config private.env or AI_API_KEY")
        for key in ("AI_BASE_URL", "KNOWLEDGE_EMBEDDING_BASE_URL", "KNOWLEDGE_RERANK_URL"):
            if settings.get(key) and not settings[key].startswith("https://dashscope.aliyuncs.com/"):
                raise Failure("REAL_ENDPOINT_MUST_BE_DASHSCOPE_HTTPS")
    jar = Path(args.jar).resolve()
    if not jar.is_file():
        raise Failure("JAR_NOT_FOUND: build the backend first and pass --jar")
    validate_jar(jar)
    java = shutil.which("java")
    if not java:
        raise Failure("JAVA_17_REQUIRED")
    run(["docker", "compose", "version"])
    run(["docker", "info", "--format", "{{.ServerVersion}}"])
    name = args.instance or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S").lower() + "-" + args.mode + "-" + secrets.token_hex(2)
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{3,50}", name):
        raise Failure("INSTANCE_NAME_INVALID")
    folder = LOCAL / name
    if folder.exists():
        raise Failure("INSTANCE_ALREADY_EXISTS: use start or seed, never reinitialize existing data")
    folder.mkdir(parents=True)
    (folder / "secrets").mkdir()
    for filename in ("mysql_password", "mysql_root_password", "redis_password", "rabbit_password"):
        (folder / "secrets" / filename).write_text(secrets.token_hex(24), "utf-8")
    if args.mode == "real":
        (folder / "secrets/ai_api_key").write_text(settings.pop("AI_API_KEY"), "utf-8")
    fixed = folder / "application.jar"
    shutil.copyfile(jar, fixed)
    ports = {}
    for key in ("mysql", "redis", "rabbit", "qdrant", "smtp", "mail", "app", "protocol"):
        candidate = freeport()
        while candidate in ports.values():
            candidate = freeport()
        ports[key] = candidate
    profile = "portfolio-" + args.mode + "-v1"
    env = dict(SPRING_CONFIG_IMPORT="", SERVER_ADDRESS="127.0.0.1", APP_PORT=str(ports["app"]),
        DB_URL=f"jdbc:mysql://127.0.0.1:{ports['mysql']}/printer?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=UTF-8", MYSQL_USER="printer",
        REDIS_HOST="127.0.0.1", REDIS_PORT=str(ports["redis"]), REDIS_ENABLED="true", REDIS_HEALTH_ENABLED="true",
        RABBITMQ_HOST="127.0.0.1", RABBITMQ_PORT=str(ports["rabbit"]), RABBITMQ_USER="printer",
        KNOWLEDGE_JOBS_ENABLED="true", KNOWLEDGE_HYBRID_ENABLED="true", KNOWLEDGE_EXTERNAL_CALLS_ENABLED=str(args.mode == "real").lower(),
        KNOWLEDGE_STORAGE_ROOT=str(folder / "knowledge"), KNOWLEDGE_LUCENE_ROOT=str(folder / "lucene"), APP_TICKETS_PHOTO_ROOT=str(folder / "photos"),
        QDRANT_URL=f"http://127.0.0.1:{ports['qdrant']}", KNOWLEDGE_HYBRID_COLLECTION="portfolio_knowledge",
        KNOWLEDGE_PROCESSING_CONFIGURATIONS=",".join(x + ":" + profile for x in ("embedding", "rerank", "chat")),
        KNOWLEDGE_EMBEDDING_CONFIGURATION=profile, KNOWLEDGE_RERANK_CONFIGURATION=profile, KNOWLEDGE_CHAT_CONFIGURATION=profile,
        KNOWLEDGE_EMBEDDING_DIMENSIONS="1024", KNOWLEDGE_EMBEDDING_MODEL="text-embedding-v4", KNOWLEDGE_RERANK_MODEL="qwen3-rerank",
        KNOWLEDGE_RETRIEVAL_CONFIGURATION="hybrid-rerank-v1", KNOWLEDGE_EMBEDDING_TIMEOUT_MS="15000", KNOWLEDGE_RERANK_TIMEOUT_MS="10000",
        AI_ENABLED="true", AI_CHAT_MODEL=settings.get("AI_CHAT_MODEL", "qwen-plus") if args.mode == "real" else "offline-no-chat",
        ASSISTANT_MODEL_MODE="AUTO" if args.mode == "real" else "PROTOCOL_SIMULATION", PORTFOLIO_MODE=args.mode,
        EXTERNAL_DAILY_CALLS=settings.get("EXTERNAL_DAILY_CALLS", "120"), EXTERNAL_DAILY_INPUT_BYTES=settings.get("EXTERNAL_DAILY_INPUT_BYTES", "1200000"),
        EXTERNAL_TURN_CALLS="12", EXTERNAL_TURN_INPUT_BYTES="120000", EXTERNAL_TASK_CALLS="30", EXTERNAL_TASK_INPUT_BYTES="300000", EXTERNAL_MAX_CONCURRENT="2",
        EMAIL_ENABLED="true", EMAIL_CAPTURE_ONLY="true", EMAIL_SMTP_HOST="127.0.0.1", EMAIL_SMTP_PORT=str(ports["smtp"]), EMAIL_SMTP_USERNAME="", EMAIL_SMTP_PASSWORD="", EMAIL_SMTP_STARTTLS="false", EMAIL_SMTP_SSL="false",
        EMAIL_SITE_ORIGIN="https://portfolio.test", EMAIL_FROM="service@portfolio.test", EMAIL_DAILY_CAP="40", LEGACY_LOGIN_MODE="MIGRATION_ONLY",
        AUTOMATION_ENABLED="false", AUTOMATION_ENABLED_TARGETS="", CONTROLLED_DISPATCH_REQUIRED="false", JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8")
    if args.mode == "real":
        env.update(AI_BASE_URL=settings.get("AI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"), KNOWLEDGE_EMBEDDING_BASE_URL=settings.get("KNOWLEDGE_EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"), KNOWLEDGE_RERANK_URL=settings.get("KNOWLEDGE_RERANK_URL", "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"))
    else:
        base = "http://127.0.0.1:" + str(ports["protocol"])
        env.update(AI_BASE_URL=base + "/v1", KNOWLEDGE_EMBEDDING_BASE_URL=base, KNOWLEDGE_RERANK_URL=base + "/rerank")
    c = dict(version=1, folder=str(folder), mode=args.mode, project="printer-portfolio-" + name, subnets=allocate_subnets(), ports=ports, env=env,
             jar=str(fixed), jar_sha256=digest(fixed), java=java, profile=profile, initialized=False,
             roles={r: {"email": r + "@portfolio.test", "password": secrets.token_hex(18)} for r in ("admin", "staff", "customer", "other")},
             requests={r: str(uuid.uuid4()) for r in ("model", "customer", "other")})
    write(folder / "runtime.json", c)
    write(LOCAL / "current.json", {"instance": name})
    print("Created isolated instance: " + name, flush=True)
    seed(c)
    return c


def publish_sample(c):
    """Explicit opt-in operation, limited to the repository's CC0 synthetic sample."""
    folder = Path(c["folder"])
    state = start(c)
    account = c["roles"]["admin"]
    admin = Client(state["base"]).login(account["email"], account["password"])
    source = ROOT / "samples/portfolio/PRINT-DEMO-01.md"
    source_hash = digest(source)
    if c.get("sample"):
        if c["sample"]["sha256"] != source_hash:
            raise Failure("SAMPLE_CHANGED: publish a reviewed new revision through the application")
        if c["sample"].get("published"):
            existing = admin.request("GET", f"/api/admin/knowledge/{c['sample']['document_id']}")
            if existing.get("active") is not True:
                raise Failure("SAMPLE_NO_LONGER_ACTIVE: inspect knowledge version/status in the application")
            return c["sample"]
    metadata = dict(requestId=str(uuid.uuid4()), documentKey="portfolio-print-demo-01-v1", title="PRINT-DEMO-01 合成演示知识手册",
                    version="DEMO-2026-11-03-v1", sourceType="self_authored_simulation", isSimulated=True, modelScope="MODELS", models=["PRINT-DEMO-01"],
                    source={"description": "本仓库原创 CC0-1.0 合成资料，非厂商手册或真实设备建议", "checkedOn": "2026-11-03"},
                    useCondition="仅用于虚构设备和合成订单演示；允许所选配置执行 embedding/rerank/chat", distributionAllowed=True,
                    processingPermissions={p: {"allowed": True, "configurationId": c["profile"]} for p in ("embedding", "rerank", "chat")}, chunkSize=500, chunkOverlap=50)
    boundary = "portfolio" + uuid.uuid4().hex
    payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n' + json.dumps(metadata, ensure_ascii=False)
               + f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="PRINT-DEMO-01.md"\r\nContent-Type: text/markdown\r\n\r\n').encode() + source.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
    if not c.get("sample"):
        document = admin.request("POST", "/api/admin/knowledge", raw=payload, content_type="multipart/form-data; boundary=" + boundary)
        c["sample"] = {"document_id": document["id"], "sha256": source_hash, "revision": 1, "published": False}
        write(folder / "runtime.json", c)
    doc_id = c["sample"]["document_id"]
    if not (folder / "sample-publish-request.json").exists():
        preview = admin.request("GET", f"/api/admin/knowledge/{doc_id}/preview?revision=1&pageSize=100")
        if not preview["reviewComplete"] or preview.get("nextCursor") is not None:
            raise Failure("SAMPLE_PREVIEW_INCOMPLETE")
        write(folder / "sample-preview.json", preview)
        body = {k: preview[k] for k in ("revision", "previewToken", "contentHash", "layoutHash", "configHash")}
        body.update(requestId=str(uuid.uuid4()), expectedVersion=preview["version"])
        published = admin.request("POST", f"/api/admin/knowledge/{doc_id}/publish", body)
        write(folder / "sample-publish-request.json", published)
    preview = read(folder / "sample-preview.json")
    def ready():
        detail = admin.request("GET", f"/api/admin/knowledge/{doc_id}")
        if detail.get("active") is True and detail.get("activeGeneration", {}).get("revision") == 1:
            return detail
    detail = wait(ready, 180)
    c["sample"] = {"document_id": doc_id, "sha256": source_hash, "revision": 1, "chunk_count": len(preview["items"]), "published": True}
    write(folder / "runtime.json", c)
    write(folder / "sample-publication.json", {"mode": c["mode"], "source": str(source.relative_to(ROOT)), **c["sample"], "detail": detail, "review": "AUTOMATED_PREVIEW_OF_AGENT_AUTHORED_SYNTHETIC_SAMPLE"})
    return c["sample"]


def validate_jar(jar):
    with zipfile.ZipFile(jar) as archive:
        if ("BOOT-INF/classes/local/printer/administration/AdministratorCommand.class" not in archive.namelist()
                or "BOOT-INF/classes/db/migration/V15__external_call_budgets.sql" not in archive.namelist()):
            raise Failure("STALE_JAR: build current backend; existing legacy target JAR is not the portfolio package")


def migration_manifest(jar):
    with zipfile.ZipFile(jar) as archive:
        entries = [n for n in archive.namelist() if re.fullmatch(r"BOOT-INF/classes/db/migration/V\d+__[^/]+\.sql", n)]
        if not entries or len(entries) != len(set(entries)):
            raise Failure("MIGRATION_MANIFEST_INVALID")
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in entries}


def compatible_migrations(current, replacement):
    """A new package may append migrations, never remove/rename/edit existing ones."""
    old, new = migration_manifest(current), migration_manifest(replacement)
    if any(name not in new or new[name] != checksum for name, checksum in old.items()):
        raise Failure("MIGRATION_DOWNGRADE_OR_REWRITE_REFUSED: restore a compatible backup instead")


def upgrade(c, jar):
    """Retain the old fixed package; DB schema downgrade is intentionally unsupported."""
    jar = Path(jar).resolve()
    validate_jar(jar)
    compatible_migrations(c["jar"], jar)
    folder = Path(c["folder"])
    stop(c)
    new_hash = digest(jar)
    if new_hash == c["jar_sha256"]:
        return
    history = folder / "packages"
    history.mkdir(exist_ok=True)
    shutil.copyfile(c["jar"], history / (c["jar_sha256"] + ".jar"))
    c.setdefault("package_history", []).append({"jar_sha256": c["jar_sha256"], "retained_at": datetime.now(timezone.utc).isoformat()})
    replacement = folder / "replacement.jar"
    shutil.copyfile(jar, replacement)
    if digest(replacement) != new_hash:
        raise Failure("PACKAGE_COPY_HASH_MISMATCH")
    replacement.replace(c["jar"])
    c["jar_sha256"] = new_hash
    write(folder / "runtime.json", c)


def load(instance):
    name = instance or read(LOCAL / "current.json")["instance"]
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{3,50}", name):
        raise Failure("INSTANCE_NAME_INVALID")
    c = read(LOCAL / name / "runtime.json")
    if Path(c["folder"]).resolve() != (LOCAL / name).resolve() or c["project"] != "printer-portfolio-" + name:
        raise Failure("INSTANCE_BINDING_MISMATCH")
    return c


def private_page(c, action):
    folder = Path(c["folder"])
    base = "http://127.0.0.1:" + str(c["ports"]["app"])
    if action == "accounts":
        body = "<h1>合成演示账号（仅本机）</h1><p>随机凭证；请勿分享此文件。</p><table><tr><th>身份</th><th>邮箱</th><th>密码</th></tr>"
        for role, account in c["roles"].items():
            body += "<tr>" + "".join("<td>" + html.escape(v) + "</td>" for v in (role, account["email"], account["password"])) + "</tr>"
        body += "</table>"
    elif action == "inbox":
        body = "<h1>本地邮件捕获</h1><p>未向外部邮箱投递；绑定链接须在原浏览器会话中打开。刷新请重运行 inbox。</p>"
        for item in capture(c, "/api/v1/messages?limit=100")["messages"]:
            text = capture(c, "/api/v1/message/" + item["ID"])["Text"].replace("https://portfolio.test", base)
            safe = html.escape(text)
            safe = re.sub(r"(http://127\.0\.0\.1:[^\s<]+)", r'<a href="\1" rel="noreferrer">打开本机动作链接</a>', safe)
            body += "<article><h2>" + html.escape(item.get("Subject", "")) + "</h2><pre>" + safe + "</pre></article>"
    else:
        label = "真实 DashScope AI（会产生模型用量）" if c["mode"] == "real" else "离线协议演示（Chat 明确不可用，向量/重排为合成协议）"
        body = "<h1>纸序 · 个人项目演示</h1><p>" + label + "</p><p>PRINT-DEMO-01 是虚构设备，知识与订单均为合成资料。邮件仅本机捕获；通知关闭。</p><p><a href='" + base + "/app/'>进入应用</a></p><p>样例：samples/portfolio/PRINT-DEMO-01.md；处理配置：" + c["profile"] + "</p>"
    target = folder / (action + ".html")
    target.write_text('<!doctype html><meta charset="utf-8"><meta name="referrer" content="no-referrer"><title>纸序项目演示</title><style>body{font:18px sans-serif;max-width:1000px;margin:48px auto;padding:24px;line-height:1.7}td,th{padding:10px;border:1px solid #ddd}pre{white-space:pre-wrap}article{border-top:1px solid #ccc}</style>' + body, "utf-8")
    webbrowser.open(target.as_uri())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["init", "start", "stop", "status", "seed", "publish-sample", "upgrade", "accounts", "inbox"])
    parser.add_argument("--instance")
    parser.add_argument("--mode", choices=["offline", "real"], default="offline")
    parser.add_argument("--jar", default="backend/target/printer-after-sales-0.0.1-SNAPSHOT.jar")
    parser.add_argument("--config", help="Private .env containing only keys shown in deploy/portfolio/real.env.sample")
    parser.add_argument("--open", action="store_true")
    parser.add_argument("--dependencies", action="store_true", help="With stop: also stop this project's containers; never remove data")
    args = parser.parse_args()
    c = init(args) if args.action == "init" else load(args.instance)
    if args.action == "stop":
        stop(c, args.dependencies)
        print("Stopped owned processes; all data and volumes retained.")
    elif args.action in ("accounts", "inbox"):
        private_page(c, args.action)
        print("Opened private local " + args.action + " page.")
    else:
        if args.action == "start":
            start(c)
        elif args.action == "seed":
            seed(c)
        elif args.action == "upgrade":
            upgrade(c, args.jar)
        elif args.action == "publish-sample":
            print(json.dumps(publish_sample(c), ensure_ascii=False))
        folder = Path(c["folder"])
        state = read(folder / "state.json") if (folder / "state.json").exists() else {}
        running = bool(state.get("java") and owned(state["java"]))
        print(json.dumps({"instance": folder.name, "mode": c["mode"], "running": running, "url": state.get("base", "") + "/app/" if running else None,
                          "initialized": c.get("initialized", False), "jar_sha256": c["jar_sha256"], "profile": c["profile"], "runtime_file": str(folder / "runtime.json")}, ensure_ascii=False))
        if args.open and running:
            private_page(c, "landing")


if __name__ == "__main__":
    try:
        main()
    except (Failure, OSError, ValueError, KeyError) as error:
        message = str(error) if isinstance(error, Failure) else type(error).__name__ + ": inspect private runtime/logs; no secrets printed"
        print("Portfolio runtime failed: " + message, file=sys.stderr)
        sys.exit(1)
