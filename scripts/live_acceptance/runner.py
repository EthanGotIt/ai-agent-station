"""以本机数据库和真实百炼模型执行端到端验收。"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


DROP_CONFIRMATION = "DROP_LOCAL_AI_AGENT_STATION_SCHEMAS"
RESET_SCHEMAS = ("AI_AGENT_STATION", "ai-agent-station", "ai-agent-station-phase8")
LOCAL_DATABASE_HOSTS = {"127.0.0.1", "localhost", "::1"}
SENSITIVE_KEY_MARKERS = ("PASSWORD", "API_KEY", "TOKEN", "SECRET")
JDBC_PATTERN = re.compile(
    r"^jdbc:mysql://(?P<host>\[[^]]+\]|[^:/?]+)(?::(?P<port>\d+))?"
    r"/(?P<schema>[A-Za-z0-9_-]+)(?:\?.*)?$"
)
KEY_PATTERN = re.compile(r"\bsk-[A-Za-z0-9_-]{12,}\b")


class AcceptanceFailure(RuntimeError):
    """真实验收未满足预期时抛出的稳定异常。"""


@dataclass(frozen=True)
class JdbcConnectionModel:
    """从 JDBC URL 中提取的非敏感数据库连接信息。"""

    host: str
    port: int
    schema: str


@dataclass(frozen=True)
class SseEventModel:
    """单条 SSE 事件及其本机接收时刻。"""

    event_type: str
    data: str
    received_at: float


@dataclass
class AcceptanceCaseModel:
    """单项端到端验收结果。"""

    name: str
    status: str = "PENDING"
    duration_ms: int = 0
    detail: dict[str, Any] = field(default_factory=dict)


class SseConversation:
    """在后台消费一个 SSE 请求，并提供按事件等待的同步接口。"""

    def __init__(
            self,
            host: str,
            port: int,
            user_id: str,
            payload: Mapping[str, Any],
            timeout_seconds: float,
    ) -> None:
        self._host = host
        self._port = port
        self._user_id = user_id
        self._payload = dict(payload)
        self._timeout_seconds = timeout_seconds
        self._events: list[SseEventModel] = []
        self._error: str | None = None
        self._finished = threading.Event()
        self._changed = threading.Condition()
        self._thread = threading.Thread(
            target=self._consume,
            name="live-acceptance-sse",
            daemon=True,
        )

    @property
    def events(self) -> tuple[SseEventModel, ...]:
        """返回当前已经收到的事件快照。"""

        with self._changed:
            return tuple(self._events)

    @property
    def error(self) -> str | None:
        """返回传输错误的脱敏摘要。"""

        with self._changed:
            return self._error

    def start(self) -> None:
        """启动 SSE 消费线程。"""

        self._thread.start()

    def wait_for(
            self,
            event_type: str,
            data: str | None = None,
            timeout_seconds: float | None = None,
    ) -> SseEventModel:
        """等待一条匹配事件，超时后抛出验收失败。"""

        deadline = time.monotonic() + (timeout_seconds or self._timeout_seconds)
        with self._changed:
            while True:
                for event in self._events:
                    if event.event_type == event_type and (
                            data is None or event.data == data
                    ):
                        return event
                    if event_type == "done" and data is not None and event.event_type == "done":
                        raise AcceptanceFailure(f"SSE 请求以终态 {event.data} 结束，预期为 {data}")
                    if event_type != "done" and event.event_type == "done":
                        raise AcceptanceFailure(
                            f"SSE 请求在收到 {event_type} 前以终态 {event.data} 结束"
                        )
                if self._error is not None:
                    raise AcceptanceFailure(
                        f"SSE 请求传输失败：{self._error}"
                    )
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    expected = f"{event_type}={data}" if data else event_type
                    raise AcceptanceFailure(f"等待 SSE 事件超时：{expected}")
                self._changed.wait(timeout=remaining)

    def wait_until_finished(self, timeout_seconds: float | None = None) -> None:
        """等待稳定终态事件或传输结束，并检查传输层错误。"""

        deadline = time.monotonic() + (timeout_seconds or self._timeout_seconds)
        with self._changed:
            while True:
                if any(event.event_type == "done" for event in self._events):
                    return
                if self._error is not None:
                    raise AcceptanceFailure(f"SSE 请求传输失败：{self._error}")
                if self._finished.is_set():
                    return
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise AcceptanceFailure("SSE 请求未在限定时间进入终态")
                self._changed.wait(timeout=remaining)

    def _consume(self) -> None:
        connection: http.client.HTTPConnection | None = None
        try:
            connection = http.client.HTTPConnection(
                self._host,
                self._port,
                timeout=self._timeout_seconds,
            )
            payload = json.dumps(self._payload, ensure_ascii=False).encode("utf-8")
            connection.request(
                "POST",
                "/api/v1/agent/chat/stream",
                body=payload,
                headers={
                    "X-User-Id": self._user_id,
                    "Content-Type": "application/json; charset=utf-8",
                    "Accept": "text/event-stream",
                },
            )
            response = connection.getresponse()
            if response.status != 200:
                response.read()
                raise AcceptanceFailure(f"SSE HTTP 状态异常：{response.status}")
            self._read_events(response)
        except (AcceptanceFailure, OSError, http.client.HTTPException) as exception:
            with self._changed:
                self._error = redact_text(str(exception))
                self._changed.notify_all()
        finally:
            if connection is not None:
                connection.close()
            self._finished.set()
            with self._changed:
                self._changed.notify_all()

    def _read_events(self, response: http.client.HTTPResponse) -> None:
        event_type = "message"
        data_lines: list[str] = []
        while True:
            raw_line = response.fp.readline() if response.fp is not None else b""
            if raw_line == b"":
                self._dispatch(event_type, data_lines)
                return
            line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
            if not line:
                self._dispatch(event_type, data_lines)
                event_type = "message"
                data_lines = []
                continue
            if line.startswith("event:"):
                event_type = line[6:].strip()
            elif line.startswith("data:"):
                data_lines.append(line[5:].lstrip())

    def _dispatch(self, event_type: str, data_lines: Iterable[str]) -> None:
        data = "\n".join(data_lines)
        if not data:
            return
        with self._changed:
            self._events.append(SseEventModel(event_type, data, time.monotonic()))
            self._changed.notify_all()


def parse_dotenv(path: Path) -> dict[str, str]:
    """解析严格且不执行 shell 的 dotenv 文件。"""

    if not path.is_file():
        raise AcceptanceFailure(f"未找到环境文件：{path}")
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line or line.startswith("export "):
            raise AcceptanceFailure(f"环境文件第 {line_number} 行格式不合法")
        key, value = line.split("=", 1)
        key = key.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise AcceptanceFailure(f"环境文件第 {line_number} 行变量名不合法")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
    return values


def parse_jdbc_connection(url: str) -> JdbcConnectionModel:
    """验证本项目 MySQL JDBC URL 并提取主机、端口和库名。"""

    match = JDBC_PATTERN.fullmatch(url.strip())
    if match is None:
        raise AcceptanceFailure("MYSQL_URL 必须是 MySQL JDBC URL")
    return JdbcConnectionModel(
        host=match.group("host").strip("[]").lower(),
        port=int(match.group("port") or "3306"),
        schema=match.group("schema"),
    )


def require_local_reset(
        connection: JdbcConnectionModel,
        confirmation: str | None,
) -> None:
    """限制数据库重置只作用于明确确认的本机实例。"""

    if connection.host not in LOCAL_DATABASE_HOSTS:
        raise AcceptanceFailure("数据库重置只允许 localhost、127.0.0.1 或 ::1")
    if confirmation != DROP_CONFIRMATION:
        raise AcceptanceFailure("数据库重置缺少精确的 --confirm-drop 确认值")


def redact_text(value: str, secret_values: Iterable[str] = ()) -> str:
    """清理报告、异常摘要中可能出现的密钥。"""

    redacted = KEY_PATTERN.sub("[REDACTED_API_KEY]", value)
    for secret in secret_values:
        if secret:
            redacted = redacted.replace(secret, "[REDACTED]")
    return redacted


def safe_environment_summary(environment: Mapping[str, str]) -> dict[str, Any]:
    """仅暴露验收报告真正需要的非敏感环境信息。"""

    connection = parse_jdbc_connection(environment["MYSQL_URL"])
    return {
        "database": {
            "host": connection.host,
            "port": connection.port,
            "schema": connection.schema,
        },
        "models": {
            "router": environment.get("AI_AGENT_ROUTER_MODEL", ""),
            "react": environment.get("AI_AGENT_REACT_MODEL", ""),
        },
        "routerThinking": {
            "enabled": environment.get(
                "AI_AGENT_ROUTER_THINKING_ENABLED", ""
            ).lower() == "true",
            "budget": environment.get("AI_AGENT_ROUTER_THINKING_BUDGET", ""),
        },
        "thinkingEnabled": environment.get(
            "AI_AGENT_REACT_THINKING_ENABLED", ""
        ).lower() == "true",
        "externalToolPolicy": "MCP_ONLY",
    }


def _required_environment(environment: Mapping[str, str]) -> None:
    required = ("MYSQL_URL", "MYSQL_USERNAME", "MYSQL_PASSWORD", "DASHSCOPE_API_KEY")
    missing = [key for key in required if not environment.get(key, "").strip()]
    if missing:
        raise AcceptanceFailure(f"缺少真实验收必需环境变量：{', '.join(missing)}")
    if environment.get("AI_AGENT_LIVE_TEST_ENABLED", "").lower() != "true":
        raise AcceptanceFailure("必须在本机 .env 明确设置 AI_AGENT_LIVE_TEST_ENABLED=true")
    if not environment.get("AI_AGENT_ROUTER_MODEL", "").startswith("qwen3.7-plus"):
        raise AcceptanceFailure("真实验收要求 Router 使用 qwen3.7-plus")
    if not environment.get("AI_AGENT_REACT_MODEL", "").startswith("qwen3.7-plus"):
        raise AcceptanceFailure("真实验收要求 ReAct 使用 qwen3.7-plus")
    if environment.get("AI_AGENT_ROUTER_THINKING_ENABLED", "").lower() != "true":
        raise AcceptanceFailure("真实验收要求开启 Router Thinking")
    try:
        router_thinking_budget = int(
            environment.get("AI_AGENT_ROUTER_THINKING_BUDGET", "")
        )
    except ValueError as exception:
        raise AcceptanceFailure("Router Thinking 预算必须是整数") from exception
    if not 1 <= router_thinking_budget <= 2048:
        raise AcceptanceFailure("Router Thinking 预算必须在 1 到 2048 之间")
    if environment.get("AI_AGENT_REACT_THINKING_ENABLED", "").lower() != "true":
        raise AcceptanceFailure("真实验收要求开启 ReAct Thinking")


def _mysql_client(environment: Mapping[str, str]) -> str:
    configured = environment.get("MYSQL_CLIENT", "").strip()
    candidates = [configured] if configured else []
    candidates.extend((
        shutil.which("mysql") or "",
        r"D:\Environment\MySQL\bin\mysql.exe",
    ))
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise AcceptanceFailure("未找到 mysql 客户端；可在 .env 设置 MYSQL_CLIENT")


def _mysql_command(
        client: str,
        connection: JdbcConnectionModel,
        user: str,
) -> list[str]:
    return [
        client,
        "--protocol=tcp",
        f"--host={connection.host}",
        f"--port={connection.port}",
        f"--user={user}",
        "--default-character-set=utf8mb4",
    ]


def _run_mysql(
        client: str,
        connection: JdbcConnectionModel,
        environment: Mapping[str, str],
        sql: str,
        database: str | None = None,
) -> str:
    child_environment = os.environ.copy()
    child_environment["MYSQL_PWD"] = environment["MYSQL_PASSWORD"]
    command = _mysql_command(client, connection, environment["MYSQL_USERNAME"])
    if database:
        command.append(f"--database={database}")
    try:
        completed = subprocess.run(
            command,
            input=sql,
            text=True,
            encoding="utf-8",
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=child_environment,
            check=False,
            timeout=45,
        )
    except (OSError, subprocess.SubprocessError) as exception:
        raise AcceptanceFailure("MySQL 命令无法执行") from exception
    if completed.returncode != 0:
        raise AcceptanceFailure("MySQL 初始化或校验失败，请检查本机连接与权限")
    return completed.stdout


def reset_database(root: Path, environment: Mapping[str, str], confirmation: str | None) -> None:
    """按明确授权重建本机验收库与历史旧库，确保 DDL 和演示数据从干净状态开始。"""

    connection = parse_jdbc_connection(environment["MYSQL_URL"])
    require_local_reset(connection, confirmation)
    client = _mysql_client(environment)
    statements = "\n".join(
        f"DROP DATABASE IF EXISTS `{schema}`;" for schema in RESET_SCHEMAS
    )
    _run_mysql(client, connection, environment, statements)

    ddl_path = root / "docs/dev-ops/mysql/sql/ai-agent-station.sql"
    if not ddl_path.is_file():
        raise AcceptanceFailure("缺少 AI_AGENT_STATION 初始化 DDL")
    _run_mysql(client, connection, environment, ddl_path.read_text(encoding="utf-8"))

    output = _run_mysql(
        client,
        connection,
        environment,
        "SELECT UPPER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES "
        "WHERE TABLE_SCHEMA = 'AI_AGENT_STATION' "
        "ORDER BY UPPER(TABLE_NAME);",
    )
    expected_tables = {
        "DEMO_ORDER",
        "AI_SESSION",
        "AI_SESSION_EVENT",
        "WORKFLOW_RUN",
        "WORKFLOW_RUN_EVENT",
        "AGENT_MEMORY_SOURCE",
        "AGENT_MEMORY_ENTRY",
        "AGENT_MEMORY_EVIDENCE",
    }
    actual_tables = {line.strip().upper() for line in output.splitlines() if line.strip()}
    if not expected_tables.issubset(actual_tables):
        raise AcceptanceFailure("AI_AGENT_STATION 初始化后缺少预期表")


def _maven_command(environment: Mapping[str, str]) -> str:
    """定位 Maven 命令，兼容 Windows 上未加入 Python PATH 的 mvn.cmd。"""

    configured = environment.get("MAVEN_CMD", "").strip()
    candidates = [configured] if configured else []
    candidates.extend((
        shutil.which("mvn.cmd") or "",
        shutil.which("mvn") or "",
        r"D:\Environment\Maven\apache-maven-3.8.8\bin\mvn.cmd",
    ))
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise AcceptanceFailure("未找到 Maven；可在 .env 设置 MAVEN_CMD")


def _run_maven(root: Path, environment: Mapping[str, str]) -> None:
    try:
        completed = subprocess.run(
            [
                _maven_command(environment),
                "-pl",
                "ai-agent-station-app",
                "-am",
                "package",
                "-DskipTests",
            ],
            cwd=root,
            check=False,
            timeout=300,
        )
    except (OSError, subprocess.SubprocessError) as exception:
        raise AcceptanceFailure("Maven 打包无法执行") from exception
    if completed.returncode != 0:
        raise AcceptanceFailure("Maven 打包失败")


def _http_json(
        host: str,
        port: int,
        method: str,
        path: str,
        user_id: str,
        body: Mapping[str, Any] | None = None,
        timeout_seconds: float = 30,
) -> tuple[int, dict[str, Any]]:
    connection = http.client.HTTPConnection(host, port, timeout=timeout_seconds)
    try:
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers = {"X-User-Id": user_id, "Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
        connection.request(method, path, body=payload, headers=headers)
        response = connection.getresponse()
        response_body = response.read().decode("utf-8", errors="replace")
        try:
            return response.status, json.loads(response_body) if response_body else {}
        except json.JSONDecodeError as exception:
            raise AcceptanceFailure(f"HTTP 响应不是 JSON，状态码：{response.status}") from exception
    except (OSError, http.client.HTTPException) as exception:
        raise AcceptanceFailure("本机 Agent HTTP 请求失败") from exception
    finally:
        connection.close()


def _wait_for_health(host: str, port: int, process: subprocess.Popen[str]) -> None:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise AcceptanceFailure("应用启动进程提前退出，请检查验收日志")
        try:
            connection = http.client.HTTPConnection(host, port, timeout=2)
            connection.request("GET", "/actuator/health")
            response = connection.getresponse()
            response.read()
            if response.status == 200:
                return
        except (OSError, http.client.HTTPException):
            pass
        finally:
            try:
                connection.close()
            except UnboundLocalError:
                pass
        time.sleep(1)
    raise AcceptanceFailure("应用未在 60 秒内通过健康检查")


def _start_application(
        root: Path,
        environment: Mapping[str, str],
        log_path: Path,
) -> tuple[subprocess.Popen[str], Any]:
    jar = root / "ai-agent-station-app/target/ai-agent-station-app.jar"
    if not jar.is_file():
        raise AcceptanceFailure("未找到打包后的应用 Jar")
    port = int(environment.get("SERVER_PORT", "8090"))
    try:
        probe = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
        probe.connect()
    except OSError:
        pass
    else:
        probe.close()
        raise AcceptanceFailure(f"本机端口 {port} 已被占用，拒绝覆盖运行中的服务")
    finally:
        try:
            probe.close()
        except UnboundLocalError:
            pass

    runtime_environment = os.environ.copy()
    runtime_environment.update(environment)
    # 真实 ASK 验收只在专用 Profile 启用可逆探针，避免它进入任何普通运行环境。
    runtime_environment["SPRING_PROFILES_ACTIVE"] = "acceptance"
    runtime_environment["AI_AGENT_REACT_ACCEPTANCE_CONFIRMATION_PROBE_ENABLED"] = "true"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_file = log_path.open("w", encoding="utf-8")
    try:
        process = subprocess.Popen(
            ["java", "-jar", str(jar)],
            cwd=root,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            text=True,
            env=runtime_environment,
        )
    except (OSError, subprocess.SubprocessError) as exception:
        log_file.close()
        raise AcceptanceFailure("无法启动应用 Jar") from exception
    return process, log_file


def _stop_application(process: subprocess.Popen[str] | None, log_file: Any | None) -> None:
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
    if log_file is not None:
        log_file.close()


def _assert_response(response: Mapping[str, Any], route: str, status: str) -> None:
    if response.get("route") != route or response.get("status") != status:
        raise AcceptanceFailure(
            f"响应路由或状态不符合预期：route={response.get('route')}, "
            f"status={response.get('status')}"
        )


def _answer_question(
        host: str,
        port: int,
        user_id: str,
        session_id: str,
        response: Mapping[str, Any],
        answers: Mapping[str, str],
) -> dict[str, Any]:
    question = response.get("question") or {}
    workflow_run = response.get("workflowRun") or {}
    if not question.get("questionId") or not workflow_run.get("runId"):
        raise AcceptanceFailure("WAITING_USER_INPUT 响应缺少 QuestionCard 或 WorkflowRun")
    payload = {
        "requestId": str(uuid.uuid4()),
        "sessionId": session_id,
        "questionId": question["questionId"],
        "checkpointId": workflow_run["checkpointId"],
        "expectedVersion": workflow_run["version"],
        "answers": dict(answers),
        "memory": {"generate": False, "use": False},
    }
    code, completed = _http_json(
        host, port, "POST", f"/api/v1/agent/workflow-runs/{workflow_run['runId']}/answers",
        user_id, payload,
    )
    if code != 200:
        raise AcceptanceFailure(f"QuestionCard 回答状态码异常：{code}")
    return completed


def _payload(session_id: str, message: str) -> dict[str, Any]:
    return {
        "requestId": str(uuid.uuid4()),
        "sessionId": session_id,
        "message": message,
    }


def _record_case(
        report_cases: list[AcceptanceCaseModel],
        name: str,
        operation: Any,
) -> Any:
    started = time.monotonic()
    item = AcceptanceCaseModel(name=name)
    report_cases.append(item)
    try:
        result = operation()
        item.status = "PASSED"
        if isinstance(result, Mapping):
            item.detail = dict(result)
        return result
    except (AcceptanceFailure, AssertionError) as exception:
        item.status = "FAILED"
        item.detail = {"failure": redact_text(str(exception))}
        raise
    finally:
        item.duration_ms = int((time.monotonic() - started) * 1000)


def _run_deterministic_cases(
        host: str,
        port: int,
        cases: list[AcceptanceCaseModel],
) -> None:
    user_id = "demo-user-1"

    def atomic() -> dict[str, Any]:
        request = _payload("live-atomic-session", "当前时间")
        code, response = _http_json(host, port, "POST", "/api/v1/agent/chat", user_id, request)
        if code != 200:
            raise AcceptanceFailure(f"原子请求状态码异常：{code}")
        _assert_response(response, "ATOMIC", "COMPLETED")
        return {"requestId": request["requestId"], "route": response["route"]}

    def order() -> dict[str, Any]:
        request = _payload("live-order-session", "查询订单 ORDER-PAID-001")
        code, response = _http_json(host, port, "POST", "/api/v1/agent/chat", user_id, request)
        if code != 200:
            raise AcceptanceFailure(f"订单请求状态码异常：{code}")
        _assert_response(response, "WORKFLOW", "COMPLETED")
        if "ORDER-PAID-001" not in str(response.get("content", "")):
            raise AcceptanceFailure("订单 Workflow 未返回目标订单")
        return {"requestId": request["requestId"], "route": response["route"]}

    def question_card() -> dict[str, Any]:
        session_id = "live-question-card-session"
        initial = _payload(session_id, "查询我的订单")
        code, response = _http_json(host, port, "POST", "/api/v1/agent/chat", user_id, initial)
        if code != 200:
            raise AcceptanceFailure(f"QuestionCard 首请求状态码异常：{code}")
        _assert_response(response, "WORKFLOW", "WAITING_USER_INPUT")
        workflow_run = response.get("workflowRun") or {}
        completed = _answer_question(
            host, port, user_id, session_id, response, {"orderId": "ORDER-DELIVERED-001"}
        )
        _assert_response(completed, "WORKFLOW", "COMPLETED")
        if "ORDER-DELIVERED-001" not in str(completed.get("content", "")):
            raise AcceptanceFailure("QuestionCard 未完成预期订单查询")
        return {
            "initialRequestId": initial["requestId"],
            "runId": workflow_run["runId"],
        }

    def order_diagnosis() -> dict[str, Any]:
        request = _payload(
            "live-order-diagnosis-session",
            "请诊断订单 ORDER-PAID-001 为什么还没发货",
        )
        code, response = _http_json(host, port, "POST", "/api/v1/agent/chat", user_id, request)
        if code != 200:
            raise AcceptanceFailure(f"订单诊断请求状态码异常：{code}")
        _assert_response(response, "WORKFLOW", "COMPLETED")
        content = str(response.get("content", ""))
        if "发货延迟" not in content:
            raise AcceptanceFailure("订单诊断 Workflow 未返回发货延迟结论")
        return {"requestId": request["requestId"], "route": response["route"]}

    def auto_refund() -> dict[str, Any]:
        session_id = "live-auto-refund-session"
        request = _payload(session_id, "订单 ORDER-PAID-001 退款")
        code, initial = _http_json(host, port, "POST", "/api/v1/agent/chat", user_id, request)
        if code != 200:
            raise AcceptanceFailure(f"自动退款首请求状态码异常：{code}")
        _assert_response(initial, "WORKFLOW", "WAITING_USER_INPUT")
        confirmation = _answer_question(
            host, port, user_id, session_id, initial, {"refundReason": "NOT_RECEIVED"}
        )
        _assert_response(confirmation, "WORKFLOW", "WAITING_USER_INPUT")
        completed = _answer_question(
            host, port, user_id, session_id, confirmation, {"decision": "CONFIRM"}
        )
        _assert_response(completed, "WORKFLOW", "COMPLETED")
        result = completed.get("result") or {}
        if result.get("cardType") != "after_sales_result":
            raise AcceptanceFailure("自动退款未返回售后结果卡")
        return {"requestId": request["requestId"], "handlingMode": "AUTO_REFUND"}

    def manual_review() -> dict[str, Any]:
        session_id = "live-manual-review-session"
        request = _payload(session_id, "订单 ORDER-SHIPPED-STALLED-001 退款")
        code, initial = _http_json(host, port, "POST", "/api/v1/agent/chat", user_id, request)
        if code != 200:
            raise AcceptanceFailure(f"人工审核首请求状态码异常：{code}")
        _assert_response(initial, "WORKFLOW", "WAITING_USER_INPUT")
        confirmation = _answer_question(
            host, port, user_id, session_id, initial, {"refundReason": "NOT_RECEIVED"}
        )
        _assert_response(confirmation, "WORKFLOW", "WAITING_USER_INPUT")
        completed = _answer_question(
            host, port, user_id, session_id, confirmation, {"decision": "CONFIRM"}
        )
        _assert_response(completed, "WORKFLOW", "COMPLETED")
        content = str(completed.get("content", ""))
        if "人工审核" not in content:
            raise AcceptanceFailure("人工审核申请未返回人工审核结论")
        return {"requestId": request["requestId"], "handlingMode": "MANUAL_REVIEW"}

    def after_sales_status() -> dict[str, Any]:
        request = _payload("live-after-sales-status-session", "查询订单 ORDER-PAID-001 的售后状态")
        code, response = _http_json(host, port, "POST", "/api/v1/agent/chat", user_id, request)
        if code != 200:
            raise AcceptanceFailure(f"售后状态请求状态码异常：{code}")
        _assert_response(response, "WORKFLOW", "COMPLETED")
        if (response.get("result") or {}).get("cardType") != "after_sales_status":
            raise AcceptanceFailure("售后状态查询未返回状态卡")
        return {"requestId": request["requestId"]}

    _record_case(cases, "atomic_rule", atomic)
    _record_case(cases, "order_workflow", order)
    _record_case(cases, "workflow_question_card", question_card)
    _record_case(cases, "order_diagnosis_workflow", order_diagnosis)
    _record_case(cases, "after_sales_auto_refund", auto_refund)
    _record_case(cases, "after_sales_manual_review", manual_review)
    _record_case(cases, "after_sales_status", after_sales_status)


def _run_router_thinking_case(
        host: str,
        port: int,
        cases: list[AcceptanceCaseModel],
) -> AcceptanceCaseModel:
    """验证未命中规则的模糊订单请求由 Flash Thinking 路由为结构化澄清。"""

    def router_thinking() -> dict[str, Any]:
        request = _payload(
            "live-router-thinking-session",
            "我有一个订单方面的问题，但没有订单号，也没有足够描述，我该怎么补充？",
        )
        code, response = _http_json(
            host,
            port,
            "POST",
            "/api/v1/agent/chat",
            "demo-user-1",
            request,
        )
        if code != 200:
            raise AcceptanceFailure(f"Plus 路由请求状态码异常：{code}")
        _assert_response(response, "CLARIFY", "COMPLETED")
        return {"requestId": request["requestId"], "route": response["route"]}

    _record_case(cases, "plus_router_thinking", router_thinking)
    return cases[-1]


def _run_restart_recovery_case(
        host: str,
        port: int,
        restart: Any,
        cases: list[AcceptanceCaseModel],
) -> None:
    """在持久化 QuestionCard 后重启应用，再用原版本继续流程。"""

    def recover() -> dict[str, Any]:
        session_id = "live-restart-recovery-session"
        initial = _payload(session_id, "帮我查询物流")
        code, waiting = _http_json(host, port, "POST", "/api/v1/agent/chat", "demo-user-1", initial)
        if code != 200:
            raise AcceptanceFailure(f"重启恢复首请求状态码异常：{code}")
        _assert_response(waiting, "WORKFLOW", "WAITING_USER_INPUT")
        run = waiting.get("workflowRun") or {}
        restart()
        completed = _answer_question(
            host, port, "demo-user-1", session_id, waiting,
            {"orderId": "ORDER-SHIPPED-STALLED-001"},
        )
        _assert_response(completed, "WORKFLOW", "COMPLETED")
        if (completed.get("result") or {}).get("cardType") != "logistics_timeline":
            raise AcceptanceFailure("服务重启后的 QuestionCard 未恢复物流流程")
        return {"runId": run.get("runId"), "checkpointId": run.get("checkpointId")}

    _record_case(cases, "workflow_restart_recovery", recover)


def _run_react_case(
        host: str,
        port: int,
        cases: list[AcceptanceCaseModel],
) -> AcceptanceCaseModel:

    def react() -> dict[str, Any]:
        transient_failures: list[str] = []
        for attempt in range(1, 3):
            request = _payload(
                f"live-react-session-{attempt}",
                "这是开放式业务复盘和比较，不执行任何写操作。"
                "请严格调用 get_order_snapshot 查询订单 ORDER-PAID-001，"
                "再基于商品、金额和订单状态给出两点非执行性分析。",
            )
            conversation = SseConversation(host, port, "demo-user-1", request, 150)
            try:
                conversation.start()
                conversation.wait_for("progress", "queued", 20)
                conversation.wait_for("progress", "request_started", 30)
                conversation.wait_for("route", "REACT", 60)
                conversation.wait_for("progress", "model_call_started", 90)
                conversation.wait_for("progress", "thinking_started", 90)
                conversation.wait_for("tool", "get_order_snapshot", 120)
                conversation.wait_for("progress", "thinking_completed", 120)
                conversation.wait_for("done", "COMPLETED", 150)
            except AcceptanceFailure as exception:
                if attempt == 2:
                    raise
                transient_failures.append(redact_text(str(exception)))
                conversation.wait_until_finished(30)
                continue
            contents = "".join(
                event.data for event in conversation.events if event.event_type == "content"
            )
            if not contents.strip():
                raise AcceptanceFailure("真实 ReAct 未输出最终内容")
            if any("内部推理" in event.data for event in conversation.events):
                raise AcceptanceFailure("SSE 不应暴露 Thinking 原文")
            return {
                "requestId": request["requestId"],
                "attempt": attempt,
                "transientFailures": transient_failures,
                "eventTypes": sorted({event.event_type for event in conversation.events}),
                "contentPreview": redact_text(contents[:500]),
            }
        raise AcceptanceFailure("真实 ReAct 分析未产生可验收结果")

    _record_case(cases, "agentscope_react_analysis", react)
    return cases[-1]


def _run_react_read_tools_case(
        host: str,
        port: int,
        cases: list[AcceptanceCaseModel],
) -> None:
    """用明确任务逐个验收五个生产只读工具均可被真实 ReAct 调用。"""

    prompts = (
        ("list_recent_orders", "这是开放式订单比较，不执行事务。请结合订单 ORDER-PAID-001，严格调用 list_recent_orders 查询我的近期订单，再按金额和状态归纳两点趋势。"),
        ("get_order_snapshot", "这是开放式业务复盘和比较，不启动 Workflow。请严格调用 get_order_snapshot 查询订单 ORDER-PAID-001，再从商品和金额角度简要分析。"),
        ("get_logistics_trace", "这是开放式履约比较，不催办物流。请严格调用 get_logistics_trace 查询订单 ORDER-SHIPPED-STALLED-001，再概括轨迹风险。"),
        ("get_after_sales_status", "这是开放式服务比较。请严格调用 get_after_sales_status 查询订单 ORDER-PAID-001 的申请记录，再说明当前可做什么。"),
        ("get_after_sales_policy", "这是开放式能力比较。请针对订单 ORDER-PAID-001 严格调用 get_after_sales_policy，并用一句话说明工具给出的处理边界。"),
    )

    for tool_name, message in prompts:
        def execute(current_tool: str = tool_name, current_message: str = message) -> dict[str, Any]:
            request = _payload(f"live-read-{current_tool}", current_message)
            conversation = SseConversation(host, port, "demo-user-1", request, 120)
            conversation.start()
            conversation.wait_for("route", "REACT", 60)
            conversation.wait_for("tool", current_tool, 100)
            conversation.wait_for("done", "COMPLETED", 120)
            return {"requestId": request["requestId"], "tool": current_tool}

        _record_case(cases, f"agentscope_read_{tool_name}", execute)


def _intervention_payload(event: SseEventModel) -> tuple[str, list[str], list[str]]:
    try:
        intervention = json.loads(event.data)
        reply_id = str(intervention["replyId"])
        tool_call_ids = [str(tool["toolCallId"]) for tool in intervention["tools"]]
        tool_names = [str(tool["toolName"]) for tool in intervention["tools"]]
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exception:
        raise AcceptanceFailure("intervention 事件格式不合法") from exception
    if not reply_id or not tool_call_ids:
        raise AcceptanceFailure("intervention 缺少 replyId 或 toolCallIds")
    return reply_id, tool_call_ids, tool_names


def _require_only_tool_names(tool_names: Sequence[str], expected_name: str, scenario: str) -> None:
    """确认 ASK 只包含允许的同类 Tool，支持一次请求保存多项会话偏好。"""

    if not tool_names or any(tool_name != expected_name for tool_name in tool_names):
        raise AcceptanceFailure(f"{scenario} 未请求允许的 Tool：{tool_names}")


def _run_react_ask_case(
        host: str,
        port: int,
        cases: list[AcceptanceCaseModel],
) -> AcceptanceCaseModel:
    """验证 AgentScope ASK 在原 SSE 回合内被旁路决定后继续。"""

    user_id = "demo-user-1"

    def confirm() -> dict[str, Any]:
        session_id = "live-ask-confirm-session"
        request = _payload(
            session_id,
            "请严格调用 save_session_preference，将 response.language 保存为 en-US；确认后说明结果。",
        )
        conversation = SseConversation(host, port, user_id, request, 120)
        conversation.start()
        conversation.wait_for("route", "REACT", 60)
        intervention = conversation.wait_for("intervention", timeout_seconds=100)
        reply_id, tool_call_ids, tool_names = _intervention_payload(intervention)
        if tool_names != ["save_session_preference"]:
            raise AcceptanceFailure(f"真实 ASK 未请求会话偏好写入：{tool_names}")
        path = f"/api/v1/agent/requests/{request['requestId']}/interventions/{reply_id}"
        foreign_code, _ = _http_json(host, port, "POST", path, "demo-user-2", {
            "sessionId": session_id,
            "toolCallIds": tool_call_ids,
            "decision": "CONFIRM",
        }, 30)
        if foreign_code != 409:
            raise AcceptanceFailure("越权 intervention 决定必须返回 409")
        code, result = _http_json(host, port, "POST", path, user_id, {
            "sessionId": session_id,
            "toolCallIds": tool_call_ids,
            "decision": "CONFIRM",
        }, 30)
        if code != 200 or result.get("accepted") is not True:
            raise AcceptanceFailure("ASK CONFIRM 旁路接口未接受决定")
        duplicate_code, _ = _http_json(host, port, "POST", path, user_id, {
            "sessionId": session_id,
            "toolCallIds": tool_call_ids,
            "decision": "CONFIRM",
        }, 30)
        if duplicate_code != 409:
            raise AcceptanceFailure("重复 intervention 决定必须返回 409")
        conversation.wait_for("tool", "save_session_preference:SUCCESS", 90)
        conversation.wait_for("done", "COMPLETED", 120)
        code, entries = _http_json(
            host, port, "GET", f"/api/v1/agent/memories?sessionId={session_id}", user_id
        )
        if code != 200 or not isinstance(entries, list) or not any(
                entry.get("memoryKey") == "response.language" and entry.get("value") == "en-US"
                for entry in entries
        ):
            raise AcceptanceFailure("CONFIRM 后未找到已规范化的会话偏好记忆")
        preference_request = _payload(
            session_id,
            "请复盘订单 ORDER-PAID-001 的商品、金额和订单状态，只做只读分析。",
        )
        preference_request["memory"] = {"generate": False, "use": True}
        preference_conversation = SseConversation(host, port, user_id, preference_request, 120)
        preference_conversation.start()
        preference_conversation.wait_for("route", "REACT", 60)
        preference_conversation.wait_for("done", "COMPLETED", 120)
        preference_content = "".join(
            event.data for event in preference_conversation.events if event.event_type == "content"
        )
        if not preference_content.strip() or re.search(r"[\u4e00-\u9fff]", preference_content):
            raise AcceptanceFailure("写入的 response.language 未在下一轮 ReAct 输出中实际生效")
        return {
            "requestId": request["requestId"],
            "replyId": reply_id,
            "decision": "CONFIRM",
            "preferenceRequestId": preference_request["requestId"],
        }

    def reject() -> dict[str, Any]:
        session_id = "live-ask-reject-session"
        request = _payload(
            session_id,
            "请严格调用 save_session_preference，将 response.detail 保存为 concise；确认后说明结果。",
        )
        conversation = SseConversation(host, port, user_id, request, 120)
        conversation.start()
        conversation.wait_for("route", "REACT", 60)
        intervention = conversation.wait_for("intervention", timeout_seconds=100)
        reply_id, tool_call_ids, tool_names = _intervention_payload(intervention)
        if tool_names != ["save_session_preference"]:
            raise AcceptanceFailure(f"真实 ASK 未请求会话偏好写入：{tool_names}")
        code, result = _http_json(
            host, port,
            "POST",
            f"/api/v1/agent/requests/{request['requestId']}/interventions/{reply_id}",
            user_id,
            {"sessionId": session_id, "toolCallIds": tool_call_ids, "decision": "REJECT"},
            30,
        )
        if code != 200 or result.get("accepted") is not True:
            raise AcceptanceFailure("ASK REJECT 旁路接口未接受决定")
        conversation.wait_until_finished(120)
        if any(event.event_type == "tool" and event.data == "save_session_preference:SUCCESS"
               for event in conversation.events):
            raise AcceptanceFailure("REJECT 后会话偏好工具不应执行")
        code, entries = _http_json(
            host, port, "GET", f"/api/v1/agent/memories?sessionId={session_id}", user_id
        )
        if code != 200 or (isinstance(entries, list) and entries):
            raise AcceptanceFailure("REJECT 后不应创建会话偏好记忆")
        return {"requestId": request["requestId"], "replyId": reply_id, "decision": "REJECT"}

    def cancel() -> dict[str, Any]:
        session_id = "live-ask-cancel-session"
        request = _payload(
            session_id,
            "请严格调用 save_session_preference，将 response.format 保存为 markdown；确认后说明结果。",
        )
        conversation = SseConversation(host, port, user_id, request, 120)
        conversation.start()
        conversation.wait_for("route", "REACT", 60)
        intervention = conversation.wait_for("intervention", timeout_seconds=100)
        reply_id, tool_call_ids, _ = _intervention_payload(intervention)
        code, result = _http_json(
            host, port, "DELETE", f"/api/v1/agent/requests/{request['requestId']}", user_id
        )
        if code != 200 or result.get("cancelled") is not True:
            raise AcceptanceFailure("ASK 等待中的请求取消失败")
        conversation.wait_until_finished(30)
        decision_code, _ = _http_json(
            host, port, "POST", f"/api/v1/agent/requests/{request['requestId']}/interventions/{reply_id}",
            user_id, {"sessionId": session_id, "toolCallIds": tool_call_ids, "decision": "CONFIRM"}, 30,
        )
        if decision_code != 409:
            raise AcceptanceFailure("取消后的 intervention 决定必须返回 409")
        code, entries = _http_json(
            host, port, "GET", f"/api/v1/agent/memories?sessionId={session_id}", user_id
        )
        if code != 200 or (isinstance(entries, list) and entries):
            raise AcceptanceFailure("取消后的 ASK 不应写入会话记忆")
        return {"requestId": request["requestId"], "replyId": reply_id, "decision": "CANCEL"}

    def timeout() -> dict[str, Any]:
        session_id = "live-ask-timeout-session"
        request = _payload(
            session_id,
            "请严格调用 save_session_preference，将 response.detail 保存为 detailed；确认后说明结果。",
        )
        conversation = SseConversation(host, port, user_id, request, 150)
        conversation.start()
        conversation.wait_for("route", "REACT", 60)
        intervention = conversation.wait_for("intervention", timeout_seconds=100)
        reply_id, tool_call_ids, _ = _intervention_payload(intervention)
        conversation.wait_until_finished(150)
        decision_code, _ = _http_json(
            host, port, "POST", f"/api/v1/agent/requests/{request['requestId']}/interventions/{reply_id}",
            user_id, {"sessionId": session_id, "toolCallIds": tool_call_ids, "decision": "CONFIRM"}, 30,
        )
        if decision_code != 409:
            raise AcceptanceFailure("超时后的 intervention 决定必须返回 409")
        code, entries = _http_json(
            host, port, "GET", f"/api/v1/agent/memories?sessionId={session_id}", user_id
        )
        if code != 200 or (isinstance(entries, list) and entries):
            raise AcceptanceFailure("超时后的 ASK 不应写入会话记忆")
        return {"requestId": request["requestId"], "replyId": reply_id, "decision": "TIMEOUT"}

    _record_case(cases, "agentscope_ask_confirm", confirm)
    _record_case(cases, "agentscope_ask_reject", reject)
    _record_case(cases, "agentscope_ask_cancel", cancel)
    _record_case(cases, "agentscope_ask_timeout", timeout)
    return cases[-1]


def _tool_names(events: Sequence[SseEventModel]) -> list[str]:
    """从 SSE Tool 生命周期事件提取稳定的 Tool 名称，忽略执行状态后缀。"""

    return [
        event.data.split(":", 1)[0]
        for event in events
        if event.event_type == "tool" and event.data
    ]


def _assert_tool_subsequence(events: Sequence[SseEventModel], expected: Sequence[str]) -> None:
    """验证 Tool 生命周期至少按预期子序列出现，不约束无关的框架事件。"""

    observed = _tool_names(events)
    cursor = 0
    for tool_name in observed:
        if cursor < len(expected) and tool_name == expected[cursor]:
            cursor += 1
    if cursor != len(expected):
        raise AcceptanceFailure(
            f"Tool 有序子序列不符合预期：expected={list(expected)}, observed={observed}"
        )


def _run_skill_stability_cases(
        host: str,
        port: int,
        cases: list[AcceptanceCaseModel],
        runs: int,
        summary: dict[str, Any],
) -> None:
    """重复验收 Router/Skill 场景，不重置数据库或重复完整 Workflow 套件。"""

    scenarios = (
        (
            "recent_order_comparison",
            "请比较我最近几笔订单的金额和状态趋势，只做只读分析。",
            ("load_skill_through_path", "list_recent_orders"),
            False,
        ),
        (
            "single_order_review",
            "请复盘订单 ORDER-PAID-001 的商品、金额和订单状态，只做只读分析。",
            ("load_skill_through_path", "get_order_snapshot"),
            False,
        ),
        (
            "order_and_logistics",
            "请综合分析订单 ORDER-SHIPPED-STALLED-001 的当前状态与物流轨迹风险，不执行任何操作。",
            ("load_skill_through_path", "get_order_snapshot", "get_logistics_trace"),
            False,
        ),
        (
            "after_sales_policy",
            "请比较订单 ORDER-PAID-001 的已有售后状态与系统支持范围，不要申请退款。",
            ("load_skill_through_path", "get_after_sales_status", "get_after_sales_policy"),
            False,
        ),
        (
            "session_preference",
            "以后请默认使用英文回答，并保持简洁；请保存这个会话偏好。",
            ("load_skill_through_path", "save_session_preference"),
            True,
        ),
    )
    summary["runs"] = runs
    summary["scenarios"] = {}
    failures: list[str] = []
    for scenario_name, message, expected_tools, requires_confirmation in scenarios:
        passed_runs = 0
        for run_index in range(1, runs + 1):
            def execute(
                    current_name: str = scenario_name,
                    current_message: str = message,
                    current_tools: Sequence[str] = expected_tools,
                    current_confirmation: bool = requires_confirmation,
                    current_run: int = run_index,
            ) -> dict[str, Any]:
                session_id = f"live-skill-{current_name}-{current_run}"
                request = _payload(session_id, current_message)
                conversation = SseConversation(host, port, "demo-user-1", request, 150)
                conversation.start()
                conversation.wait_for("route", "REACT", 60)
                if current_confirmation:
                    intervention = conversation.wait_for("intervention", timeout_seconds=120)
                    reply_id, tool_call_ids, tool_names = _intervention_payload(intervention)
                    _require_only_tool_names(
                        tool_names, "save_session_preference", "Skill 偏好场景"
                    )
                    code, result = _http_json(
                        host,
                        port,
                        "POST",
                        f"/api/v1/agent/requests/{request['requestId']}/interventions/{reply_id}",
                        "demo-user-1",
                        {
                            "sessionId": session_id,
                            "toolCallIds": tool_call_ids,
                            "decision": "CONFIRM",
                        },
                        30,
                    )
                    if code != 200 or result.get("accepted") is not True:
                        raise AcceptanceFailure("Skill 偏好场景确认未被接受")
                conversation.wait_for("done", "COMPLETED", 150)
                _assert_tool_subsequence(conversation.events, current_tools)
                return {"requestId": request["requestId"], "run": current_run}

            try:
                _record_case(cases, f"skill_stability_{scenario_name}_{run_index}", execute)
                passed_runs += 1
            except AcceptanceFailure as exception:
                failures.append(f"{scenario_name} 第 {run_index}/{runs} 次：{redact_text(str(exception))}")
        summary["scenarios"][scenario_name] = {
            "passedRuns": passed_runs,
            "requiredRuns": runs,
        }
    if failures:
        raise AcceptanceFailure("Skill 稳定性验收未达到全部命中：" + "；".join(failures))


def _run_cancellation_fifo_case(
        host: str,
        port: int,
        cases: list[AcceptanceCaseModel],
) -> AcceptanceCaseModel:

    def cancellation_fifo() -> dict[str, Any]:
        session_id = "live-cancellation-session"
        first_payload = _payload(
            session_id,
            "这是开放式订单复盘，不要启动 Workflow。请依次调用 list_recent_orders、"
            "get_order_snapshot 和 get_logistics_trace，比较近期订单、商品金额与物流状态，"
            "并输出一份不少于三点的分析。",
        )
        first = SseConversation(host, port, "demo-user-1", first_payload, 150)
        first.start()
        first.wait_for("progress", "model_call_started", 100)

        second_payload = _payload(session_id, "当前时间")
        second = SseConversation(host, port, "demo-user-1", second_payload, 150)
        second.start()
        queued = second.wait_for("progress", "queued", 20)
        code, cancellation = _http_json(
            host,
            port,
            "DELETE",
            f"/api/v1/agent/requests/{first_payload['requestId']}",
            "demo-user-1",
            None,
            20,
        )
        if code != 200 or cancellation.get("cancelled") is not True:
            raise AcceptanceFailure("活动 ReAct 取消接口未确认取消")
        cancelled = first.wait_for("done", "CANCELLED", 45)
        started = second.wait_for("progress", "request_started", 45)
        second.wait_for("done", "COMPLETED", 45)
        if queued.received_at >= cancelled.received_at:
            raise AcceptanceFailure("后续请求未在前一 ReAct 活动期间进入排队状态")
        if started.received_at < cancelled.received_at:
            raise AcceptanceFailure("同 Session FIFO 被破坏：取消完成前启动了后续请求")
        return {
            "cancelledRequestId": first_payload["requestId"],
            "followingRequestId": second_payload["requestId"],
            "firstEventTypes": sorted({event.event_type for event in first.events}),
            "secondEventTypes": sorted({event.event_type for event in second.events}),
        }

    _record_case(cases, "react_cancellation_fifo", cancellation_fifo)
    return cases[-1]


def _verify_react_tokens(log_path: Path, request_id: str) -> dict[str, int]:
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
        line = next(
            (item for item in text.splitlines() if request_id in item and "inputTokens" in item),
            "",
        )
        match = re.search(r"inputTokens=(\d+).*outputTokens=(\d+)", line)
        if match is not None:
            input_tokens, output_tokens = int(match.group(1)), int(match.group(2))
            if input_tokens <= 0 or output_tokens <= 0:
                raise AcceptanceFailure("真实 ReAct Token 统计必须均大于零")
            return {"inputTokens": input_tokens, "outputTokens": output_tokens}
        time.sleep(0.1)
    raise AcceptanceFailure("应用日志未找到真实 ReAct 的 Token 统计")


def _write_report(root: Path, report: Mapping[str, Any]) -> Path:
    """同时写入机器可读 JSON 与脱敏的人工可读 Markdown 报告。"""

    output_directory = root / "target/live-acceptance"
    output_directory.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    report_path = output_directory / f"report-{timestamp}.json"
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    markdown_path = output_directory / f"report-{timestamp}.md"
    markdown_path.write_text(
        _render_report_markdown(report),
        encoding="utf-8",
    )
    return report_path


def _render_report_markdown(report: Mapping[str, Any]) -> str:
    """渲染不含 Prompt、密钥和记忆正文的验收摘要。"""

    environment = report.get("environment", {})
    models = environment.get("models", {}) if isinstance(environment, Mapping) else {}
    lines = [
        "# AI Agent Station 真实验收报告",
        "",
        f"- 状态：**{report.get('status', 'UNKNOWN')}**",
        f"- 开始时间：{report.get('startedAt', 'UNKNOWN')}",
        f"- 完成时间：{report.get('finishedAt', 'UNKNOWN')}",
        f"- Router 模型：`{models.get('router', 'UNKNOWN')}`",
        f"- ReAct 模型：`{models.get('react', 'UNKNOWN')}`",
        f"- 外部能力策略：`{environment.get('externalToolPolicy', 'UNKNOWN')}`",
        f"- 数据库重置：{report.get('databaseReset', 'UNKNOWN')}",
        "",
        "## 场景结果",
        "",
        "| 场景 | 状态 | 耗时（ms） |",
        "| --- | --- | ---: |",
    ]
    cases = report.get("cases", [])
    if isinstance(cases, list):
        for case in cases:
            if not isinstance(case, Mapping):
                continue
            name = str(case.get("name", "UNKNOWN")).replace("|", "\\|")
            status = str(case.get("status", "UNKNOWN")).replace("|", "\\|")
            duration = case.get("duration_ms", 0)
            lines.append(f"| `{name}` | {status} | {duration} |")
    stability = report.get("skillStability")
    if isinstance(stability, Mapping):
        lines.extend(("", "## Skill 稳定性", "", "| 场景 | 成功率 |", "| --- | ---: |"))
        scenarios = stability.get("scenarios", {})
        if isinstance(scenarios, Mapping):
            for name, result in scenarios.items():
                if not isinstance(result, Mapping):
                    continue
                passed_runs = result.get("passedRuns", 0)
                required_runs = result.get("requiredRuns", stability.get("runs", 0))
                lines.append(f"| `{name}` | {passed_runs}/{required_runs} |")
    failure = report.get("failure")
    if failure:
        lines.extend(("", "## 失败摘要", "", str(failure)))
    lines.append("")
    return "\n".join(lines)


def run_live_acceptance(options: argparse.Namespace) -> Path:
    """执行完整真实模型验收并返回脱敏报告路径。"""

    root = repository_root(options.root)
    if options.skill_stability_runs < 1 or options.skill_stability_runs > 10:
        raise AcceptanceFailure("--skill-stability-runs 必须在 1 到 10 之间")
    environment = parse_dotenv((root / options.env).resolve())
    _required_environment(environment)
    report: dict[str, Any] = {
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "status": "FAILED",
        "environment": safe_environment_summary(environment),
        "cases": [],
    }
    case_results: list[AcceptanceCaseModel] = []
    process: subprocess.Popen[str] | None = None
    log_file: Any | None = None
    log_path = root / "target/live-acceptance/app.log"
    try:
        if options.reset_database:
            reset_database(root, environment, options.confirm_drop)
            report["databaseReset"] = "COMPLETED"
        else:
            report["databaseReset"] = "SKIPPED"
        if not options.skip_build:
            _run_maven(root, environment)
        process, log_file = _start_application(root, environment, log_path)
        port = int(environment.get("SERVER_PORT", "8090"))
        _wait_for_health("127.0.0.1", port, process)

        def restart() -> None:
            nonlocal process, log_file
            _stop_application(process, log_file)
            process, log_file = _start_application(root, environment, log_path)
            _wait_for_health("127.0.0.1", port, process)

        _run_deterministic_cases("127.0.0.1", port, case_results)
        _run_restart_recovery_case("127.0.0.1", port, restart, case_results)
        _run_router_thinking_case("127.0.0.1", port, case_results)
        react_case = _run_react_case("127.0.0.1", port, case_results)
        token_usage = _verify_react_tokens(log_path, react_case.detail["requestId"])
        react_case.detail["tokenUsage"] = token_usage
        _run_react_read_tools_case("127.0.0.1", port, case_results)
        _run_react_ask_case("127.0.0.1", port, case_results)
        if options.skill_stability_runs > 1:
            stability_summary: dict[str, Any] = {}
            report["skillStability"] = stability_summary
            _run_skill_stability_cases(
                "127.0.0.1", port, case_results, options.skill_stability_runs, stability_summary
            )
        _run_cancellation_fifo_case("127.0.0.1", port, case_results)
        report["status"] = "PASSED"
    except (AcceptanceFailure, ValueError, OSError, subprocess.SubprocessError) as exception:
        report["failure"] = redact_text(
            str(exception),
            _secret_values(environment),
        )
        raise
    finally:
        report["cases"] = [case.__dict__ for case in case_results]
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        _stop_application(process, log_file)
        report["applicationLog"] = "target/live-acceptance/app.log"
        report_path = _write_report(root, report)
    return report_path


def _secret_values(environment: Mapping[str, str]) -> tuple[str, ...]:
    return tuple(
        value for key, value in environment.items()
        if any(marker in key for marker in SENSITIVE_KEY_MARKERS) and value
    )


def repository_root(candidate: Path) -> Path:
    """定位同时拥有工程 POM 与协作规则的仓库根目录。"""

    for path in (candidate.resolve(), *candidate.resolve().parents):
        if (path / "pom.xml").is_file() and (path / "AGENTS.md").is_file():
            return path
    raise AcceptanceFailure("未找到 AI Agent Station 仓库根目录")


def main(arguments: Sequence[str] | None = None) -> int:
    """解析命令行并执行真实模型验收。"""

    parser = argparse.ArgumentParser(description="执行 AI Agent Station 真实百炼验收")
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="仓库根目录")
    parser.add_argument(
        "--env",
        type=Path,
        default=Path("ai-agent-station-app/.env"),
        help="本机 dotenv 文件，相对仓库根目录",
    )
    parser.add_argument("--reset-database", action="store_true", help="重建本机验收数据库")
    parser.add_argument("--confirm-drop", help="删除旧库的精确确认值")
    parser.add_argument("--skip-build", action="store_true", help="跳过 Maven 打包")
    parser.add_argument(
        "--skill-stability-runs",
        type=int,
        default=1,
        help="重复 Router/Skill 场景次数（2-10 启用，建议 5）",
    )
    options = parser.parse_args(arguments)
    try:
        report_path = run_live_acceptance(options)
    except AcceptanceFailure as exception:
        print(f"真实验收失败：{redact_text(str(exception))}", file=sys.stderr)
        return 1
    print(f"真实验收通过，脱敏报告：{report_path}")
    return 0
