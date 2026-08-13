"""以本机 MySQL 和可编排 HTTP 渠道验证退款生命周期。"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import subprocess
import threading
import time
import uuid
from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

from scripts.live_acceptance.runner import (
    AcceptanceFailure,
    JdbcConnectionModel,
    LOCAL_DATABASE_HOSTS,
    _mysql_client,
    _run_maven,
    _run_mysql,
    parse_dotenv,
    parse_jdbc_connection,
    redact_text,
    repository_root,
)


DROP_CONFIRMATION = "DROP_LOCAL_REFUND_ACCEPTANCE_SCHEMA"
EXPECTED_SCHEMA = "AI_AGENT_STATION"
USER_ID = "refund-acceptance-user"
OPERATOR_ID = "refund-acceptance-operator"
SENSITIVE_KEY_MARKERS = ("PASSWORD", "API_KEY", "TOKEN", "SECRET")

AUTO_SUCCESS_ORDER = "ORDER-REFUND-AUTO-001"
MANUAL_APPROVAL_ORDER = "ORDER-REFUND-MANUAL-001"
AUTO_RETRY_ORDER = "ORDER-REFUND-RETRY-001"
FINAL_FAILURE_ORDER = "ORDER-REFUND-FAIL-001"
RESTART_ORDER = "ORDER-REFUND-RESTART-001"


@dataclass(frozen=True)
class RefundChannelAction:
    """单次模拟渠道响应。"""

    status: str
    failure_code: str = ""
    delay_seconds: float = 0.0

    def __post_init__(self) -> None:
        normalized = self.status.strip().upper()
        if normalized not in {"COMPLETED", "FAILED"}:
            raise ValueError("refund channel action status is invalid")
        if normalized == "FAILED" and not self.failure_code.strip():
            raise ValueError("failed refund channel action requires failure code")
        if self.delay_seconds < 0 or self.delay_seconds > 30:
            raise ValueError("refund channel action delay is invalid")
        object.__setattr__(self, "status", normalized)
        object.__setattr__(self, "failure_code", self.failure_code.strip())


@dataclass
class AcceptanceCase:
    """单项退款验收的脱敏结果。"""

    name: str
    status: str = "PENDING"
    duration_ms: int = 0
    detail: dict[str, int] = field(default_factory=dict)


class RefundChannelSimulator:
    """本机 HTTP 退款渠道：按订单编排响应，并缓存成功幂等结果。"""

    def __init__(self) -> None:
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None
        self._condition = threading.Condition()
        self._actions: dict[str, deque[RefundChannelAction]] = {}
        self._calls: dict[str, list[str]] = defaultdict(list)
        self._completed_keys: set[str] = set()
        self._effect_orders: dict[str, str] = {}
        self._rejections: dict[str, int] = defaultdict(int)
        self._unexpected_calls = 0

    @property
    def base_url(self) -> str:
        if self._server is None:
            raise RuntimeError("refund channel simulator is not running")
        return f"http://127.0.0.1:{self._server.server_address[1]}"

    @property
    def unexpected_calls(self) -> int:
        with self._condition:
            return self._unexpected_calls

    @property
    def rejection_counts(self) -> dict[str, int]:
        """返回不包含请求正文的稳定拒绝原因计数。"""

        with self._condition:
            return dict(self._rejections)

    def configure(self, order_id: str, actions: Sequence[RefundChannelAction]) -> None:
        if not order_id or not actions:
            raise ValueError("refund channel script is incomplete")
        with self._condition:
            self._actions[order_id] = deque(actions)

    def start(self) -> None:
        if self._server is not None:
            raise RuntimeError("refund channel simulator is already running")
        simulator = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler protocol
                simulator._handle(self)

            def log_message(self, format_value: str, *args: object) -> None:
                return

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(
            target=self._server.serve_forever,
            name="refund-channel-simulator",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=5)
        self._server = None
        self._thread = None

    def call_count(self, order_id: str) -> int:
        with self._condition:
            return len(self._calls[order_id])

    def unique_key_count(self, order_id: str) -> int:
        with self._condition:
            return len(set(self._calls[order_id]))

    def effect_count(self, order_id: str) -> int:
        with self._condition:
            return sum(1 for value in self._effect_orders.values() if value == order_id)

    def wait_for_calls(self, order_id: str, count: int, timeout_seconds: float) -> None:
        deadline = time.monotonic() + timeout_seconds
        with self._condition:
            while len(self._calls[order_id]) < count:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise AcceptanceFailure("等待退款渠道调用超时")
                self._condition.wait(timeout=remaining)

    def _handle(self, handler: BaseHTTPRequestHandler) -> None:
        if handler.path != "/refunds":
            with self._condition:
                self._unexpected_calls += 1
                self._condition.notify_all()
            self._write(handler, 503, {"code": "MODEL_CALL_FORBIDDEN"})
            return
        raw_body, framing_error = self._read_request_body(handler)
        if framing_error is not None:
            self._reject(handler, framing_error)
            return
        try:
            payload = json.loads(raw_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._reject(handler, "INVALID_JSON")
            return
        if not isinstance(payload, dict):
            self._reject(handler, "INVALID_JSON_OBJECT")
            return
        refund_id = str(payload.get("refundId", "")).strip()
        order_id = str(payload.get("orderId", "")).strip()
        idempotency_key = str(handler.headers.get("Idempotency-Key", "")).strip()
        if not refund_id or not order_id:
            self._reject(handler, "MISSING_IDENTIFIERS")
            return
        if idempotency_key != refund_id:
            self._reject(handler, "IDEMPOTENCY_KEY_MISMATCH")
            return
        if set(payload) != {"refundId", "orderId", "amount", "currency"}:
            self._reject(handler, "UNEXPECTED_FIELDS")
            return

        with self._condition:
            self._calls[order_id].append(idempotency_key)
            if idempotency_key in self._completed_keys:
                action = RefundChannelAction("COMPLETED")
            else:
                actions = self._actions.get(order_id)
                action = actions.popleft() if actions else RefundChannelAction(
                    "FAILED", "SIMULATOR_SCRIPT_EXHAUSTED"
                )
                if action.status == "COMPLETED":
                    self._completed_keys.add(idempotency_key)
                    self._effect_orders[idempotency_key] = order_id
            self._condition.notify_all()

        if action.delay_seconds:
            time.sleep(action.delay_seconds)
        body: dict[str, str] = {"status": action.status}
        if action.failure_code:
            body["failureCode"] = action.failure_code
        self._write(handler, 200, body)

    def _read_request_body(
            self,
            handler: BaseHTTPRequestHandler,
    ) -> tuple[bytes, str | None]:
        content_length = handler.headers.get("Content-Length")
        if content_length is not None:
            try:
                length = int(content_length)
            except ValueError:
                return b"", "INVALID_CONTENT_LENGTH"
            if length < 1 or length > 4096:
                return b"", "INVALID_REQUEST_SIZE"
            body = handler.rfile.read(length)
            return (body, None) if len(body) == length else (b"", "INCOMPLETE_REQUEST_BODY")
        if handler.headers.get("Transfer-Encoding", "").lower() != "chunked":
            return b"", "MISSING_BODY_FRAMING"

        body = bytearray()
        while True:
            size_line = handler.rfile.readline(128)
            if not size_line or len(size_line) >= 128:
                return b"", "INVALID_CHUNK_SIZE"
            try:
                chunk_size = int(size_line.split(b";", 1)[0].strip(), 16)
            except ValueError:
                return b"", "INVALID_CHUNK_SIZE"
            if chunk_size < 0 or len(body) + chunk_size > 4096:
                return b"", "INVALID_REQUEST_SIZE"
            if chunk_size == 0:
                if handler.rfile.readline(128) not in {b"\r\n", b"\n"}:
                    return b"", "INVALID_CHUNK_TERMINATOR"
                return (bytes(body), None) if body else (b"", "INVALID_REQUEST_SIZE")
            chunk = handler.rfile.read(chunk_size)
            if len(chunk) != chunk_size:
                return b"", "INCOMPLETE_REQUEST_BODY"
            if handler.rfile.read(2) != b"\r\n":
                return b"", "INVALID_CHUNK_TERMINATOR"
            body.extend(chunk)

    def _reject(self, handler: BaseHTTPRequestHandler, reason: str) -> None:
        with self._condition:
            self._rejections[reason] += 1
            self._condition.notify_all()
        self._write(handler, 400, {"code": "INVALID_REFUND_REQUEST"})

    def _write(self, handler: BaseHTTPRequestHandler, status: int, payload: Mapping[str, str]) -> None:
        body = json.dumps(payload).encode("utf-8")
        try:
            handler.send_response(status)
            handler.send_header("Content-Type", "application/json")
            handler.send_header("Content-Length", str(len(body)))
            handler.end_headers()
            handler.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, OSError):
            # 进程重启验收会主动断开正在等待的渠道响应。
            return


def require_refund_reset(connection: JdbcConnectionModel, confirmation: str | None) -> None:
    """只允许显式删除本机当前项目 Schema。"""

    if connection.host not in LOCAL_DATABASE_HOSTS:
        raise AcceptanceFailure("退款验收只允许连接本机 MySQL")
    if connection.schema.upper() != EXPECTED_SCHEMA:
        raise AcceptanceFailure(f"退款验收数据库必须为 {EXPECTED_SCHEMA}")
    if confirmation != DROP_CONFIRMATION:
        raise AcceptanceFailure("退款验收缺少精确数据库重置确认值")


def required_environment(environment: Mapping[str, str]) -> JdbcConnectionModel:
    """校验退款验收所需的最小本机环境，不要求模型或支付渠道凭据。"""

    missing = [key for key in ("MYSQL_URL", "MYSQL_USERNAME", "MYSQL_PASSWORD") if key not in environment]
    if missing:
        raise AcceptanceFailure(f"缺少退款验收环境变量：{', '.join(missing)}")
    if not environment["MYSQL_USERNAME"].strip():
        raise AcceptanceFailure("MYSQL_USERNAME 不能为空")
    connection = parse_jdbc_connection(environment["MYSQL_URL"])
    if connection.host not in LOCAL_DATABASE_HOSTS:
        raise AcceptanceFailure("退款验收只允许连接本机 MySQL")
    if connection.schema.upper() != EXPECTED_SCHEMA:
        raise AcceptanceFailure(f"退款验收数据库必须为 {EXPECTED_SCHEMA}")
    try:
        port = int(environment.get("SERVER_PORT", "8090"))
    except ValueError as exception:
        raise AcceptanceFailure("SERVER_PORT 必须是整数") from exception
    if port < 1024 or port > 65535:
        raise AcceptanceFailure("SERVER_PORT 必须在 1024 到 65535 之间")
    return connection


def reset_database(root: Path, environment: Mapping[str, str], confirmation: str | None) -> None:
    """删除并重建唯一的本机 AI_AGENT_STATION，再写入退款验收订单。"""

    connection = required_environment(environment)
    require_refund_reset(connection, confirmation)
    client = _mysql_client(environment)
    _run_mysql(
        client,
        connection,
        environment,
        "DROP DATABASE IF EXISTS `AI_AGENT_STATION`;",
    )
    ddl = root / "docs/dev-ops/mysql/sql/ai-agent-station.sql"
    if not ddl.is_file():
        raise AcceptanceFailure("缺少 AI_AGENT_STATION 初始化脚本")
    _run_mysql(client, connection, environment, ddl.read_text(encoding="utf-8"))
    _run_mysql(client, connection, environment, _fixture_sql(), EXPECTED_SCHEMA)


def _fixture_sql() -> str:
    values = (
        (AUTO_SUCCESS_ORDER, "PAID"),
        (MANUAL_APPROVAL_ORDER, "SHIPPED"),
        (AUTO_RETRY_ORDER, "PAID"),
        (FINAL_FAILURE_ORDER, "PAID"),
        (RESTART_ORDER, "PAID"),
    )
    rows = ",\n".join(
        "(" + ", ".join((
            f"'{order_id}'",
            f"'{USER_ID}'",
            f"'{status}'",
            "NULL",
            "DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 24 HOUR)",
            "99.00",
            "'CNY'",
        )) + ")"
        for order_id, status in values
    )
    return (
        "INSERT INTO DEMO_ORDER "
        "(ORDER_ID, USER_ID, STATUS, DAYS_SINCE_DELIVERY, CREATED_AT, PAID_AMOUNT, CURRENCY) VALUES\n"
        + rows
        + ";"
    )


class ApplicationProcessManager:
    """启动、停止并重启退款验收应用进程。"""

    def __init__(
            self,
            root: Path,
            environment: Mapping[str, str],
            channel_url: str,
            log_path: Path,
    ) -> None:
        self._root = root
        self._environment = dict(environment)
        self._channel_url = channel_url
        self._log_path = log_path
        self._process: subprocess.Popen[str] | None = None
        self._log_file: Any | None = None
        self._started_once = False

    @property
    def port(self) -> int:
        return int(self._environment.get("SERVER_PORT", "8090"))

    def start(self) -> None:
        if self._process is not None and self._process.poll() is None:
            raise AcceptanceFailure("退款验收应用已经启动")
        jar = self._root / "ai-agent-station-app/target/ai-agent-station-app.jar"
        if not jar.is_file():
            raise AcceptanceFailure("未找到退款验收应用 Jar")
        _require_free_port(self.port)
        runtime = os.environ.copy()
        runtime.update(self._environment)
        runtime.update({
            "SPRING_PROFILES_ACTIVE": "refund-acceptance",
            "DASHSCOPE_API_KEY": "refund-acceptance-placeholder",
            "QWEN_BASE_URL": self._channel_url,
            "AI_AGENT_REACT_BASE_URL": self._channel_url,
            "AI_AGENT_REFUND_CHANNEL_MODE": "http",
            "AI_AGENT_REFUND_CHANNEL_BASE_URL": self._channel_url,
            "AI_AGENT_REFUND_CHANNEL_TIMEOUT": "PT10S",
            "AI_AGENT_REFUND_WORKER_POLL_INTERVAL": "PT0.1S",
            "AI_AGENT_REFUND_WORKER_INITIAL_DELAY": "PT0.1S",
            "AI_AGENT_REFUND_WORKER_BATCH_SIZE": "4",
            "AI_AGENT_REFUND_WORKER_MAX_ATTEMPTS": "3",
            "AI_AGENT_REFUND_WORKER_RETRY_DELAY": "PT0.2S",
            "AI_AGENT_REFUND_WORKER_LEASE_DURATION": "PT1S",
            "AI_AGENT_MEMORY_GENERATION_ENABLED": "false",
            "AI_AGENT_MEMORY_USAGE_ENABLED": "false",
        })
        self._log_path.parent.mkdir(parents=True, exist_ok=True)
        mode = "a" if self._started_once else "w"
        self._log_file = self._log_path.open(mode, encoding="utf-8")
        try:
            self._process = subprocess.Popen(
                ["java", "-jar", str(jar)],
                cwd=self._root,
                stdout=self._log_file,
                stderr=subprocess.STDOUT,
                text=True,
                env=runtime,
            )
        except (OSError, subprocess.SubprocessError) as exception:
            self._log_file.close()
            self._log_file = None
            raise AcceptanceFailure("无法启动退款验收应用") from exception
        self._started_once = True
        _wait_for_health("127.0.0.1", self.port, self._process)

    def restart(self) -> None:
        self.stop()
        self.start()

    def stop(self) -> None:
        if self._process is not None and self._process.poll() is None:
            self._process.terminate()
            try:
                self._process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self._process.kill()
                self._process.wait(timeout=10)
        if self._log_file is not None:
            self._log_file.close()
        self._process = None
        self._log_file = None


def _require_free_port(port: int) -> None:
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
    try:
        connection.connect()
    except OSError:
        return
    finally:
        connection.close()
    raise AcceptanceFailure(f"本机端口 {port} 已被占用")


def _wait_for_health(host: str, port: int, process: subprocess.Popen[str]) -> None:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise AcceptanceFailure("退款验收应用启动进程提前退出")
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
        time.sleep(0.5)
    raise AcceptanceFailure("退款验收应用未在 60 秒内通过健康检查")


def _http_json(
        port: int,
        method: str,
        path: str,
        headers: Mapping[str, str],
        body: Mapping[str, Any] | None = None,
        timeout_seconds: float = 20,
) -> tuple[int, dict[str, Any]]:
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout_seconds)
    try:
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request_headers = {"Accept": "application/json", **headers}
        if payload is not None:
            request_headers["Content-Type"] = "application/json; charset=utf-8"
        connection.request(method, path, body=payload, headers=request_headers)
        response = connection.getresponse()
        response_body = response.read().decode("utf-8", errors="replace")
        try:
            result = json.loads(response_body) if response_body else {}
        except json.JSONDecodeError as exception:
            raise AcceptanceFailure("退款验收 HTTP 响应不是 JSON") from exception
        return response.status, result
    except (OSError, http.client.HTTPException) as exception:
        raise AcceptanceFailure("退款验收 HTTP 请求失败") from exception
    finally:
        connection.close()


def _submit_refund(port: int, order_id: str, scenario: str) -> dict[str, Any]:
    session_id = f"refund-{scenario}-session"
    response = _agent_request(port, "/api/v1/agent/chat", {
        "requestId": str(uuid.uuid4()),
        "sessionId": session_id,
        "message": f"订单 {order_id} 退款",
        "memory": {"generate": False, "use": False},
    })
    for _ in range(4):
        if response.get("status") == "COMPLETED":
            result = response.get("result") or {}
            data = result.get("data") or {}
            if result.get("cardType") != "after_sales_result" or not data.get("caseId"):
                raise AcceptanceFailure("退款 Workflow 未返回售后结果卡")
            return data
        if response.get("status") != "WAITING_USER_INPUT":
            raise AcceptanceFailure("退款 Workflow 未进入预期状态")
        question = response.get("question") or {}
        run = response.get("workflowRun") or {}
        field_names = {field.get("name") for field in question.get("fields", [])}
        if "refundReason" in field_names:
            answers = {"refundReason": "NOT_RECEIVED"}
        elif "description" in field_names:
            answers = {"description": "退款验收使用的确定性问题说明。"}
        elif "decision" in field_names:
            answers = {"decision": "CONFIRM"}
        else:
            raise AcceptanceFailure("退款 Workflow 返回未知 QuestionCard")
        response = _agent_request(port, f"/api/v1/agent/workflow-runs/{run['runId']}/answers", {
            "requestId": str(uuid.uuid4()),
            "sessionId": session_id,
            "questionId": question["questionId"],
            "checkpointId": run["checkpointId"],
            "expectedVersion": run["version"],
            "answers": answers,
            "memory": {"generate": False, "use": False},
        })
    raise AcceptanceFailure("退款 Workflow QuestionCard 次数超过验收上限")


def _query_status(port: int, order_id: str) -> dict[str, Any]:
    response = _agent_request(port, "/api/v1/agent/chat", {
        "requestId": str(uuid.uuid4()),
        "sessionId": f"refund-status-{order_id.lower()}-session",
        "message": f"查询订单 {order_id} 的售后状态",
        "memory": {"generate": False, "use": False},
    })
    result = response.get("result") or {}
    if response.get("status") != "COMPLETED" or result.get("cardType") != "after_sales_status":
        raise AcceptanceFailure("售后状态查询未返回状态卡")
    return result.get("data") or {}


def _agent_request(port: int, path: str, body: Mapping[str, Any]) -> dict[str, Any]:
    code, response = _http_json(port, "POST", path, {"X-User-Id": USER_ID}, body)
    if code != 200:
        raise AcceptanceFailure(f"Agent 退款请求状态码异常：{code}")
    return response


def _case_detail(port: int, case_id: str) -> dict[str, Any]:
    code, response = _http_json(
        port,
        "GET",
        f"/api/v1/after-sales/cases/{case_id}",
        {"X-Operator-Id": OPERATOR_ID},
    )
    if code != 200:
        raise AcceptanceFailure(f"售后详情状态码异常：{code}")
    return response


def _wait_case_status(port: int, case_id: str, status: str, timeout_seconds: float = 20) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        last = _case_detail(port, case_id)
        if last.get("status") == status:
            return last
        time.sleep(0.1)
    raise AcceptanceFailure(
        f"售后申请未进入预期状态：actual={last.get('status')}, expected={status}"
    )


def _review(port: int, case: Mapping[str, Any], decision_id: str) -> dict[str, Any]:
    code, response = _http_json(
        port,
        "POST",
        f"/api/v1/after-sales/cases/{case['caseId']}/review-decisions",
        {"X-Operator-Id": OPERATOR_ID},
        {
            "decisionId": decision_id,
            "expectedVersion": case["version"],
            "decision": "APPROVE",
            "note": "退款可靠性验收批准",
        },
    )
    if code != 200:
        raise AcceptanceFailure(f"售后审核状态码异常：{code}")
    return response.get("caseModel") or {}


def _retry(port: int, case: Mapping[str, Any], retry_id: str) -> dict[str, Any]:
    code, response = _http_json(
        port,
        "POST",
        f"/api/v1/after-sales/cases/{case['caseId']}/refund-retries",
        {"X-Operator-Id": OPERATOR_ID},
        {"retryId": retry_id, "expectedVersion": case["version"]},
    )
    if code != 200:
        raise AcceptanceFailure(f"退款人工重试状态码异常：{code}")
    return response.get("caseModel") or {}


def _record_case(
        cases: list[AcceptanceCase],
        name: str,
        operation: Callable[[], dict[str, int]],
) -> None:
    result = AcceptanceCase(name=name)
    started = time.monotonic()
    try:
        result.detail = operation()
        result.status = "PASSED"
    except (AcceptanceFailure, KeyError, TypeError, ValueError) as exception:
        result.status = "FAILED"
        raise AcceptanceFailure(f"退款验收场景失败：{name}") from exception
    finally:
        result.duration_ms = int((time.monotonic() - started) * 1000)
        cases.append(result)


def _run_cases(
        application: ApplicationProcessManager,
        simulator: RefundChannelSimulator,
        cases: list[AcceptanceCase],
) -> None:
    port = application.port

    def automatic_success() -> dict[str, int]:
        data = _submit_refund(port, AUTO_SUCCESS_ORDER, "auto-success")
        current = _wait_case_status(port, data["caseId"], "COMPLETED")
        command = current.get("refundCommand") or {}
        if command.get("status") != "COMPLETED" or simulator.effect_count(AUTO_SUCCESS_ORDER) != 1:
            raise AcceptanceFailure("自动退款未完成唯一渠道效果")
        return {"attemptCount": command.get("attemptCount", 0), "channelCalls": 1, "uniqueEffects": 1}

    def manual_approval_idempotency() -> dict[str, int]:
        data = _submit_refund(port, MANUAL_APPROVAL_ORDER, "manual-approval")
        pending = _wait_case_status(port, data["caseId"], "PENDING_REVIEW")
        if simulator.call_count(MANUAL_APPROVAL_ORDER) != 0:
            raise AcceptanceFailure("人工审核前不应调用退款渠道")
        decision_id = str(uuid.uuid4())
        approved = _review(port, pending, decision_id)
        replayed = _review(port, pending, decision_id)
        if approved.get("refundId") != replayed.get("refundId"):
            raise AcceptanceFailure("重复审核未返回同一退款命令")
        current = _wait_case_status(port, data["caseId"], "COMPLETED")
        command = current.get("refundCommand") or {}
        if simulator.effect_count(MANUAL_APPROVAL_ORDER) != 1:
            raise AcceptanceFailure("重复审核产生了重复退款效果")
        return {
            "attemptCount": command.get("attemptCount", 0),
            "channelCalls": simulator.call_count(MANUAL_APPROVAL_ORDER),
            "uniqueEffects": 1,
        }

    def automatic_retry() -> dict[str, int]:
        data = _submit_refund(port, AUTO_RETRY_ORDER, "automatic-retry")
        current = _wait_case_status(port, data["caseId"], "COMPLETED")
        command = current.get("refundCommand") or {}
        if command.get("attemptCount") != 3 or simulator.call_count(AUTO_RETRY_ORDER) != 3:
            raise AcceptanceFailure("退款有限自动重试次数不符合预期")
        if simulator.unique_key_count(AUTO_RETRY_ORDER) != 1 or simulator.effect_count(AUTO_RETRY_ORDER) != 1:
            raise AcceptanceFailure("自动重试未保持外部幂等键")
        return {"attemptCount": 3, "channelCalls": 3, "uniqueEffects": 1}

    def failure_and_manual_retry() -> dict[str, int]:
        data = _submit_refund(port, FINAL_FAILURE_ORDER, "manual-retry")
        failed = _wait_case_status(port, data["caseId"], "REFUND_FAILED")
        command = failed.get("refundCommand") or {}
        if command.get("status") != "FAILED" or command.get("attemptCount") != 3:
            raise AcceptanceFailure("退款命令未在重试上限进入失败终态")
        simulator.configure(FINAL_FAILURE_ORDER, [RefundChannelAction("COMPLETED")])
        retry_id = str(uuid.uuid4())
        requeued = _retry(port, failed, retry_id)
        replayed = _retry(port, failed, retry_id)
        if requeued.get("refundId") != replayed.get("refundId"):
            raise AcceptanceFailure("重复人工重试未返回同一退款命令")
        completed = _wait_case_status(port, data["caseId"], "COMPLETED")
        final_command = completed.get("refundCommand") or {}
        if final_command.get("attemptCount") != 1 or simulator.effect_count(FINAL_FAILURE_ORDER) != 1:
            raise AcceptanceFailure("人工重试后的退款结果不符合预期")
        return {
            "attemptCount": 4,
            "channelCalls": simulator.call_count(FINAL_FAILURE_ORDER),
            "uniqueEffects": 1,
        }

    def restart_lease_recovery() -> dict[str, int]:
        data = _submit_refund(port, RESTART_ORDER, "restart-recovery")
        simulator.wait_for_calls(RESTART_ORDER, 1, 10)
        if simulator.effect_count(RESTART_ORDER) != 1:
            raise AcceptanceFailure("进程停止前渠道未记录成功幂等效果")
        application.restart()
        completed = _wait_case_status(application.port, data["caseId"], "COMPLETED", 20)
        command = completed.get("refundCommand") or {}
        if command.get("attemptCount") != 2:
            raise AcceptanceFailure("租约到期后未重新领取退款命令")
        if simulator.unique_key_count(RESTART_ORDER) != 1 or simulator.effect_count(RESTART_ORDER) != 1:
            raise AcceptanceFailure("进程重启产生了重复退款效果")
        return {
            "attemptCount": 2,
            "channelCalls": simulator.call_count(RESTART_ORDER),
            "uniqueEffects": 1,
        }

    def status_consistency() -> dict[str, int]:
        code, page = _http_json(
            application.port,
            "GET",
            "/api/v1/after-sales/cases?status=COMPLETED&page=0&size=20",
            {"X-Operator-Id": OPERATOR_ID},
        )
        if code != 200:
            raise AcceptanceFailure("售后完成列表查询失败")
        operator_case = next(
            (item for item in page.get("items", []) if item.get("orderId") == AUTO_RETRY_ORDER),
            None,
        )
        workflow_status = _query_status(application.port, AUTO_RETRY_ORDER)
        if operator_case is None or operator_case.get("status") != workflow_status.get("status"):
            raise AcceptanceFailure("操作员 API 与 Workflow 售后状态不一致")
        command = operator_case.get("refundCommand") or {}
        if command.get("status") != workflow_status.get("refundCommandStatus"):
            raise AcceptanceFailure("操作员 API 与 Workflow 退款命令状态不一致")
        if command.get("attemptCount") != workflow_status.get("attemptCount"):
            raise AcceptanceFailure("操作员 API 与 Workflow 尝试次数不一致")
        if command.get("failureCode", "") != workflow_status.get("refundFailureCode", ""):
            raise AcceptanceFailure("操作员 API 与 Workflow 退款失败码不一致")
        return {"attemptCount": command.get("attemptCount", 0), "channelCalls": 0, "uniqueEffects": 0}

    _record_case(cases, "automatic_refund_success", automatic_success)
    _record_case(cases, "manual_approval_idempotency", manual_approval_idempotency)
    _record_case(cases, "finite_automatic_retry", automatic_retry)
    _record_case(cases, "final_failure_and_manual_retry", failure_and_manual_retry)
    _record_case(cases, "restart_lease_recovery", restart_lease_recovery)
    _record_case(cases, "status_query_consistency", status_consistency)


def _configure_simulator(simulator: RefundChannelSimulator) -> None:
    simulator.configure(AUTO_SUCCESS_ORDER, [RefundChannelAction("COMPLETED")])
    simulator.configure(MANUAL_APPROVAL_ORDER, [RefundChannelAction("COMPLETED")])
    simulator.configure(AUTO_RETRY_ORDER, [
        RefundChannelAction("FAILED", "CHANNEL_TEMPORARY_FAILURE"),
        RefundChannelAction("FAILED", "CHANNEL_TEMPORARY_FAILURE"),
        RefundChannelAction("COMPLETED"),
    ])
    simulator.configure(FINAL_FAILURE_ORDER, [
        RefundChannelAction("FAILED", "CHANNEL_TEMPORARY_FAILURE"),
        RefundChannelAction("FAILED", "CHANNEL_TEMPORARY_FAILURE"),
        RefundChannelAction("FAILED", "CHANNEL_TEMPORARY_FAILURE"),
    ])
    simulator.configure(RESTART_ORDER, [RefundChannelAction("COMPLETED", delay_seconds=5)])


def _secret_values(environment: Mapping[str, str]) -> tuple[str, ...]:
    return tuple(
        value for key, value in environment.items()
        if any(marker in key for marker in SENSITIVE_KEY_MARKERS) and value
    )


def assert_report_safe(report: Mapping[str, Any], secrets: Sequence[str]) -> None:
    """在报告落盘前再次确认没有环境机密或 API Key 形态。"""

    serialized = json.dumps(report, ensure_ascii=False)
    if redact_text(serialized, secrets) != serialized:
        raise AcceptanceFailure("退款验收报告包含敏感值，已拒绝落盘")


def _write_report(root: Path, report: Mapping[str, Any], secrets: Sequence[str] = ()) -> Path:
    assert_report_safe(report, secrets)
    output = root / "target/refund-acceptance"
    output.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    json_path = output / f"report-{timestamp}.json"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_path = output / f"report-{timestamp}.md"
    markdown_path.write_text(_render_markdown(report), encoding="utf-8")
    return json_path


def _render_markdown(report: Mapping[str, Any]) -> str:
    lines = [
        "# V2.1 退款可靠性验收报告",
        "",
        f"- 状态：**{report.get('status', 'UNKNOWN')}**",
        "",
        "| 场景 | 状态 | 耗时（ms） | 尝试次数 | 渠道调用 | 唯一效果 |",
        "| --- | --- | ---: | ---: | ---: | ---: |",
    ]
    for case in report.get("cases", []):
        detail = case.get("detail", {})
        lines.append(
            f"| `{case.get('name', 'UNKNOWN')}` | {case.get('status', 'UNKNOWN')} "
            f"| {case.get('duration_ms', 0)} | {detail.get('attemptCount', 0)} "
            f"| {detail.get('channelCalls', 0)} | {detail.get('uniqueEffects', 0)} |"
        )
    lines.append("")
    return "\n".join(lines)


def run_refund_acceptance(options: argparse.Namespace) -> Path:
    """执行退款可靠性验收并返回脱敏 JSON 报告路径。"""

    root = repository_root(options.root)
    environment = parse_dotenv((root / options.env).resolve())
    required_environment(environment)
    if not options.reset_database:
        raise AcceptanceFailure("退款可靠性验收必须显式使用 --reset-database")
    report: dict[str, Any] = {"status": "FAILED", "cases": []}
    cases: list[AcceptanceCase] = []
    simulator = RefundChannelSimulator()
    application: ApplicationProcessManager | None = None
    try:
        reset_database(root, environment, options.confirm_drop)
        if not options.skip_build:
            _run_maven(root, environment)
        simulator.start()
        _configure_simulator(simulator)
        application = ApplicationProcessManager(
            root,
            environment,
            simulator.base_url,
            root / "target/refund-acceptance/app.log",
        )
        application.start()
        _run_cases(application, simulator, cases)
        if simulator.unexpected_calls != 0:
            raise AcceptanceFailure("退款验收期间发生了禁止的外部模型调用")
        report["status"] = "PASSED"
    except AcceptanceFailure:
        raise
    except (OSError, subprocess.SubprocessError, ValueError) as exception:
        raise AcceptanceFailure("退款可靠性验收执行失败") from exception
    finally:
        if application is not None:
            application.stop()
        simulator.stop()
        report["cases"] = [case.__dict__ for case in cases]
        report_path = _write_report(root, report, _secret_values(environment))
    return report_path


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="执行 V2.1 退款可靠性独立验收")
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="仓库根目录")
    parser.add_argument(
        "--env",
        type=Path,
        default=Path("ai-agent-station-app/.env"),
        help="只包含本机 MySQL 等配置的 dotenv 文件",
    )
    parser.add_argument("--reset-database", action="store_true", help="重建本机 AI_AGENT_STATION")
    parser.add_argument("--confirm-drop", help="数据库删除精确确认值")
    parser.add_argument("--skip-build", action="store_true", help="跳过 Maven 打包")
    options = parser.parse_args(arguments)
    try:
        report = run_refund_acceptance(options)
    except AcceptanceFailure as failure:
        print(f"退款可靠性验收失败：{failure}", file=os.sys.stderr)
        return 1
    print(f"退款可靠性验收通过：{report}")
    return 0
