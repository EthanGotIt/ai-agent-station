"""独立订单 HTTP 服务夹具：用于验证 Agent 与外部订单服务的真实边界。"""

from __future__ import annotations

import hashlib
import json
import os
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable
from urllib.parse import parse_qs, unquote, urlsplit


DEFAULT_DATABASE_PATH = "/data/order-service.db"
DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8080
MAX_BODY_BYTES = 64 * 1024
MAX_IDEMPOTENCY_KEY_LENGTH = 200


def utc_now() -> datetime:
    """返回 UTC 当前时间，便于测试注入确定性时钟。"""

    return datetime.now(timezone.utc)


def format_instant(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_instant(value: str | None) -> datetime | None:
    if value is None or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def json_bytes(payload: object) -> bytes:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def normalize_idempotency_key(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = value.strip()
    if (
        not normalized
        or len(normalized) > MAX_IDEMPOTENCY_KEY_LENGTH
        or any(character.isspace() or ord(character) < 32 for character in normalized)
    ):
        return None
    return normalized


class OrderService:
    """独立订单服务：数据和幂等回执均不使用 Agent 的 MySQL。"""

    def __init__(
        self,
        database_path: str,
        clock: Callable[[], datetime] | None = None,
        expedite_transient_failures: int | None = None,
    ) -> None:
        self.database_path = database_path
        self.clock = clock or utc_now
        self.expedite_transient_failures = self._non_negative_int(
            expedite_transient_failures
            if expedite_transient_failures is not None
            else os.getenv("ORDER_SERVICE_FIXTURE_EXPEDITE_TRANSIENT_FAILURES", "0")
        )
        database_directory = os.path.dirname(database_path)
        if database_directory:
            os.makedirs(database_directory, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=10, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        return connection

    @contextmanager
    def _connection(self):
        connection = self._connect()
        try:
            yield connection
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self._connection() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS ORDERS (
                    ORDER_ID TEXT PRIMARY KEY,
                    USER_ID TEXT NOT NULL,
                    STATUS TEXT NOT NULL,
                    DAYS_SINCE_DELIVERY INTEGER,
                    CREATED_AT TEXT NOT NULL,
                    EXPECTED_DELIVERY_AT TEXT,
                    LAST_LOGISTICS_AT TEXT,
                    LOGISTICS_STATUS TEXT NOT NULL,
                    PAID_AMOUNT TEXT NOT NULL,
                    CURRENCY TEXT NOT NULL,
                    ITEM_SUMMARY TEXT NOT NULL,
                    HIDDEN_AT TEXT
                );
                CREATE INDEX IF NOT EXISTS IDX_ORDERS_USER_CREATED
                    ON ORDERS (USER_ID, CREATED_AT DESC);
                CREATE TABLE IF NOT EXISTS LOGISTICS_EVENTS (
                    EVENT_ID TEXT PRIMARY KEY,
                    ORDER_ID TEXT NOT NULL REFERENCES ORDERS (ORDER_ID),
                    STATUS TEXT NOT NULL,
                    LOCATION TEXT,
                    DESCRIPTION TEXT NOT NULL,
                    OCCURRED_AT TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS IDX_LOGISTICS_ORDER_OCCURRED
                    ON LOGISTICS_EVENTS (ORDER_ID, OCCURRED_AT);
                CREATE TABLE IF NOT EXISTS IDEMPOTENCY_RECORDS (
                    USER_ID TEXT NOT NULL,
                    IDEMPOTENCY_KEY TEXT NOT NULL,
                    ACTION TEXT NOT NULL,
                    ORDER_ID TEXT NOT NULL,
                    REQUEST_HASH TEXT NOT NULL,
                    HTTP_STATUS INTEGER NOT NULL,
                    RESPONSE_JSON TEXT NOT NULL,
                    MUTATED INTEGER NOT NULL,
                    CREATED_AT TEXT NOT NULL,
                    PRIMARY KEY (USER_ID, IDEMPOTENCY_KEY)
                );
                CREATE TABLE IF NOT EXISTS FIXTURE_FAULT_ATTEMPTS (
                    USER_ID TEXT NOT NULL,
                    ACTION TEXT NOT NULL,
                    ORDER_ID TEXT NOT NULL,
                    ATTEMPTS INTEGER NOT NULL,
                    PRIMARY KEY (USER_ID, ACTION, ORDER_ID)
                );
                """
            )
            self._seed_orders(connection)

    def _seed_orders(self, connection: sqlite3.Connection) -> None:
        users = [
            value.strip()
            for value in os.getenv("ORDER_SERVICE_FIXTURE_USERS", "demo-user-1").split(",")
            if value.strip()
        ]
        now = self.clock()
        definitions = [
            (
                "ORDER-EXT-TODAY-001",
                "PAID",
                None,
                now - timedelta(hours=2),
                now + timedelta(days=2),
                now - timedelta(hours=1),
                "待发货",
                "129.00",
                "CNY",
                "无线耳机",
            ),
            (
                "ORDER-EXT-STALLED-001",
                "SHIPPED",
                None,
                now - timedelta(days=4),
                now + timedelta(days=1),
                now - timedelta(days=3),
                "运输中",
                "99.00",
                "CNY",
                "家居收纳盒",
            ),
            (
                "ORDER-EXT-DELIVERED-001",
                "DELIVERED",
                2,
                now - timedelta(days=8),
                now - timedelta(days=2),
                now - timedelta(days=2),
                "已签收",
                "299.00",
                "CNY",
                "人体工学键盘",
            ),
            (
                "ORDER-EXT-REFUND-001",
                "PAID",
                None,
                now - timedelta(days=1),
                now + timedelta(days=2),
                now - timedelta(hours=6),
                "待发货",
                "199.00",
                "CNY",
                "智能台灯",
            ),
        ]
        for user_index, user_id in enumerate(users):
            suffix = "" if user_index == 0 else f"-{user_index + 1}"
            for definition in definitions:
                order_id = f"{definition[0]}{suffix}"
                inserted = connection.execute(
                    """
                    INSERT OR IGNORE INTO ORDERS (
                        ORDER_ID, USER_ID, STATUS, DAYS_SINCE_DELIVERY, CREATED_AT,
                        EXPECTED_DELIVERY_AT, LAST_LOGISTICS_AT, LOGISTICS_STATUS,
                        PAID_AMOUNT, CURRENCY, ITEM_SUMMARY, HIDDEN_AT
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                    """,
                    (
                        order_id,
                        user_id,
                        definition[1],
                        definition[2],
                        format_instant(definition[3]),
                        format_instant(definition[4]),
                        format_instant(definition[5]),
                        definition[6],
                        definition[7],
                        definition[8],
                        definition[9],
                    ),
                )
                if inserted.rowcount == 1:
                    self._seed_logistics(connection, order_id, definition[5], definition[6])

    @staticmethod
    def _seed_logistics(
        connection: sqlite3.Connection,
        order_id: str,
        last_event: datetime,
        status: str,
    ) -> None:
        event_time = last_event - timedelta(days=1)
        connection.executemany(
            """
            INSERT OR IGNORE INTO LOGISTICS_EVENTS
                (EVENT_ID, ORDER_ID, STATUS, LOCATION, DESCRIPTION, OCCURRED_AT)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            [
                (
                    f"{order_id}-LOG-001",
                    order_id,
                    "已揽收",
                    "杭州分拨中心",
                    "包裹已揽收",
                    format_instant(event_time),
                ),
                (
                    f"{order_id}-LOG-002",
                    order_id,
                    status,
                    "运输途中",
                    f"物流状态：{status}",
                    format_instant(last_event),
                ),
            ],
        )

    @staticmethod
    def _order_payload(row: sqlite3.Row) -> dict[str, object]:
        amount = Decimal(row["PAID_AMOUNT"])
        return {
            "accessDenied": False,
            "orderId": row["ORDER_ID"],
            "userId": row["USER_ID"],
            "status": row["STATUS"],
            "daysSinceDelivery": row["DAYS_SINCE_DELIVERY"],
            "createdAt": row["CREATED_AT"],
            "expectedDeliveryAt": row["EXPECTED_DELIVERY_AT"],
            "lastLogisticsAt": row["LAST_LOGISTICS_AT"],
            "logisticsStatus": row["LOGISTICS_STATUS"],
            "paidAmount": float(amount),
            "currency": row["CURRENCY"],
            "itemSummary": row["ITEM_SUMMARY"],
            "hiddenAt": row["HIDDEN_AT"],
        }

    def find_order(self, user_id: str, order_id: str) -> tuple[int, dict[str, object]]:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT * FROM ORDERS WHERE ORDER_ID = ?",
                (order_id,),
            ).fetchone()
        if row is None:
            return 404, {"code": "ORDER_NOT_FOUND", "message": "订单不存在"}
        if row["USER_ID"] != user_id:
            return 403, {"code": "ORDER_NOT_OWNED", "message": "订单不属于当前用户"}
        return 200, self._order_payload(row)

    def search_orders(
        self,
        user_id: str,
        query: dict[str, list[str]],
    ) -> tuple[int, list[dict[str, object]]]:
        visibility = self._first(query, "visibility", "ACTIVE").upper()
        statuses = {
            value.strip().upper()
            for value in self._first(query, "status", "").split(",")
            if value.strip()
        }
        created_from = parse_instant(self._first(query, "createdFrom", ""))
        created_to = parse_instant(self._first(query, "createdTo", ""))
        minimum = self._decimal(self._first(query, "minAmount", ""))
        maximum = self._decimal(self._first(query, "maxAmount", ""))
        keyword = self._first(query, "keyword", "").strip().lower()
        stalled_days = self._integer(self._first(query, "logisticsStalledDays", ""))
        limit = min(max(self._integer(self._first(query, "limit", "10")) or 10, 1), 50)
        if visibility not in {"ACTIVE", "HIDDEN", "ALL"}:
            return 400, []
        with self._connection() as connection:
            rows = connection.execute(
                "SELECT * FROM ORDERS WHERE USER_ID = ? ORDER BY CREATED_AT DESC",
                (user_id,),
            ).fetchall()
        filtered = []
        for row in rows:
            created_at = parse_instant(row["CREATED_AT"])
            last_logistics = parse_instant(row["LAST_LOGISTICS_AT"])
            amount = Decimal(row["PAID_AMOUNT"])
            if visibility == "ACTIVE" and row["HIDDEN_AT"] is not None:
                continue
            if visibility == "HIDDEN" and row["HIDDEN_AT"] is None:
                continue
            if created_from is not None and (created_at is None or created_at < created_from):
                continue
            if created_to is not None and (created_at is None or created_at > created_to):
                continue
            if minimum is not None and amount < minimum:
                continue
            if maximum is not None and amount > maximum:
                continue
            if statuses and row["STATUS"].upper() not in statuses:
                continue
            searchable = " ".join(
                (row["ORDER_ID"], row["ITEM_SUMMARY"], row["LOGISTICS_STATUS"])
            ).lower()
            if keyword and keyword not in searchable:
                continue
            if stalled_days is not None:
                cutoff = self.clock() - timedelta(days=stalled_days)
                if last_logistics is not None and last_logistics > cutoff:
                    continue
            filtered.append(self._order_payload(row))
            if len(filtered) >= limit:
                break
        return 200, filtered

    def logistics(self, user_id: str, order_id: str) -> tuple[int, list[dict[str, object]]]:
        order_status, _ = self.find_order(user_id, order_id)
        if order_status != 200:
            return order_status, []
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT EVENT_ID, STATUS, LOCATION, DESCRIPTION, OCCURRED_AT
                FROM LOGISTICS_EVENTS WHERE ORDER_ID = ? ORDER BY OCCURRED_AT
                """,
                (order_id,),
            ).fetchall()
        return 200, [
            {
                "eventId": row["EVENT_ID"],
                "status": row["STATUS"],
                "location": row["LOCATION"],
                "description": row["DESCRIPTION"],
                "occurredAt": row["OCCURRED_AT"],
            }
            for row in rows
        ]

    def action(
        self,
        user_id: str,
        order_id: str,
        action_name: str,
        idempotency_key: str | None,
        body: dict[str, object],
    ) -> tuple[int, dict[str, object]]:
        normalized_key = normalize_idempotency_key(idempotency_key)
        if normalized_key is None:
            return 400, {
                "success": False,
                "retryable": False,
                "code": "IDEMPOTENCY_KEY_INVALID",
                "message": "订单动作幂等键无效",
            }
        request_hash = hashlib.sha256(
            json.dumps(
                {"action": action_name, "orderId": order_id, "body": body},
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        ).hexdigest()
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                """
                SELECT REQUEST_HASH, HTTP_STATUS, RESPONSE_JSON
                FROM IDEMPOTENCY_RECORDS
                WHERE USER_ID = ? AND IDEMPOTENCY_KEY = ?
                """,
                (user_id, normalized_key),
            ).fetchone()
            if existing is not None:
                if existing["REQUEST_HASH"] != request_hash:
                    connection.rollback()
                    return 409, {
                        "success": False,
                        "retryable": False,
                        "code": "IDEMPOTENCY_KEY_REUSED",
                        "message": "幂等键已经绑定其他订单操作",
                    }
                connection.commit()
                return existing["HTTP_STATUS"], json.loads(existing["RESPONSE_JSON"])

            row = connection.execute(
                "SELECT * FROM ORDERS WHERE ORDER_ID = ?",
                (order_id,),
            ).fetchone()
            injected = self._maybe_inject_expedite_failure(
                connection, row, user_id, order_id, action_name
            )
            if injected is not None:
                connection.commit()
                return 200, injected
            response_status, response, mutated = self._apply_action(
                connection,
                row,
                user_id,
                order_id,
                action_name,
                body,
            )
            connection.execute(
                """
                INSERT INTO IDEMPOTENCY_RECORDS (
                    USER_ID, IDEMPOTENCY_KEY, ACTION, ORDER_ID, REQUEST_HASH,
                    HTTP_STATUS, RESPONSE_JSON, MUTATED, CREATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    user_id,
                    normalized_key,
                    action_name,
                    order_id,
                    request_hash,
                    response_status,
                    json.dumps(response, ensure_ascii=False, separators=(",", ":")),
                    1 if mutated else 0,
                    format_instant(self.clock()),
                ),
            )
            connection.commit()
            return response_status, response
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _apply_action(
        self,
        connection: sqlite3.Connection,
        row: sqlite3.Row | None,
        user_id: str,
        order_id: str,
        action_name: str,
        body: dict[str, object],
    ) -> tuple[int, dict[str, object], bool]:
        if row is None:
            if action_name == "delete":
                return self._with_mutation(self._succeeded("ALREADY_DELETED", "订单记录已删除"), False)
            return 404, {
                "success": False,
                "retryable": False,
                "code": "ORDER_NOT_FOUND",
                "message": "订单不存在",
            }, False
        if row["USER_ID"] != user_id:
            return 403, {
                "success": False,
                "retryable": False,
                "code": "ORDER_NOT_OWNED",
                "message": "订单不属于当前用户",
            }, False
        if action_name == "refund":
            reason = body.get("reason")
            if not isinstance(reason, str) or not reason.strip():
                return self._with_mutation(
                    self._failed("REFUND_ARGUMENT_INVALID", "退款参数不完整"), False
                )
            if row["STATUS"] == "REFUNDED":
                return self._with_mutation(
                    self._succeeded("ALREADY_REFUNDED", "订单已完成退款"), False
                )
            if row["STATUS"] not in {"PAID", "SHIPPED", "DELIVERED"}:
                return self._with_mutation(
                    self._failed("REFUND_ORDER_STATE_INVALID", "当前订单状态不允许退款"), False
                )
            connection.execute(
                "UPDATE ORDERS SET STATUS = ? WHERE ORDER_ID = ?",
                ("REFUNDED", order_id),
            )
            return self._with_mutation(self._succeeded("REFUNDED", "订单已完成退款"), True)
        if action_name == "expedite":
            if row["LOGISTICS_STATUS"] == "EXPEDITE_REQUESTED":
                return self._with_mutation(
                    self._succeeded("ALREADY_EXPEDITED", "已记录催发货请求"), False
                )
            if row["STATUS"] != "PAID":
                return self._with_mutation(
                    self._failed("EXPEDITE_ORDER_STATE_INVALID", "仅已支付且待发货订单允许催发货"), False
                )
            now = format_instant(self.clock())
            connection.execute(
                "UPDATE ORDERS SET LOGISTICS_STATUS = ?, LAST_LOGISTICS_AT = ? WHERE ORDER_ID = ?",
                ("EXPEDITE_REQUESTED", now, order_id),
            )
            return self._with_mutation(self._succeeded("EXPEDITED", "已记录催发货请求"), True)
        if action_name == "delete":
            connection.execute("DELETE FROM LOGISTICS_EVENTS WHERE ORDER_ID = ?", (order_id,))
            connection.execute("DELETE FROM ORDERS WHERE ORDER_ID = ?", (order_id,))
            return self._with_mutation(self._succeeded("ORDER_DELETED", "订单记录已删除"), True)
        return self._with_mutation(self._failed("ACTION_UNSUPPORTED", "不支持的订单操作"), False)

    def _maybe_inject_expedite_failure(
        self,
        connection: sqlite3.Connection,
        row: sqlite3.Row | None,
        user_id: str,
        order_id: str,
        action_name: str,
    ) -> dict[str, object] | None:
        """在有效催发货订单上注入可重试失败，且不写入幂等记录或业务事实。"""

        if (
            action_name != "expedite"
            or self.expedite_transient_failures <= 0
            or row is None
            or row["USER_ID"] != user_id
            or row["STATUS"] != "PAID"
            or row["LOGISTICS_STATUS"] == "EXPEDITE_REQUESTED"
        ):
            return None
        fault = connection.execute(
            "SELECT ATTEMPTS FROM FIXTURE_FAULT_ATTEMPTS "
            "WHERE USER_ID = ? AND ACTION = ? AND ORDER_ID = ?",
            (user_id, action_name, order_id),
        ).fetchone()
        attempts = int(fault["ATTEMPTS"]) if fault is not None else 0
        if attempts >= self.expedite_transient_failures:
            return None
        next_attempts = attempts + 1
        connection.execute(
            "INSERT INTO FIXTURE_FAULT_ATTEMPTS (USER_ID, ACTION, ORDER_ID, ATTEMPTS) "
            "VALUES (?, ?, ?, ?) "
            "ON CONFLICT(USER_ID, ACTION, ORDER_ID) DO UPDATE SET ATTEMPTS = excluded.ATTEMPTS",
            (user_id, action_name, order_id, next_attempts),
        )
        return {
            "success": False,
            "retryable": True,
            "code": "FIXTURE_TRANSIENT_FAILURE",
            "message": f"验收夹具注入临时失败（第 {next_attempts} 次）",
        }

    @staticmethod
    def _with_mutation(
        result: tuple[int, dict[str, object]],
        mutated: bool,
    ) -> tuple[int, dict[str, object], bool]:
        status, response = result
        return status, response, mutated

    @staticmethod
    def _succeeded(code: str, message: str) -> tuple[int, dict[str, object]]:
        return 200, {"success": True, "retryable": False, "code": code, "message": message}

    @staticmethod
    def _failed(code: str, message: str) -> tuple[int, dict[str, object]]:
        return 200, {"success": False, "retryable": False, "code": code, "message": message}

    def stats(self) -> dict[str, int]:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT COUNT(*) AS TOTAL, COALESCE(SUM(MUTATED), 0) AS MUTATIONS "
                "FROM IDEMPOTENCY_RECORDS"
            ).fetchone()
            fault = connection.execute(
                "SELECT COALESCE(SUM(ATTEMPTS), 0) AS INJECTED "
                "FROM FIXTURE_FAULT_ATTEMPTS"
            ).fetchone()
        return {
            "idempotencyRecords": row["TOTAL"],
            "businessMutations": row["MUTATIONS"],
            "injectedFailures": fault["INJECTED"],
        }

    @staticmethod
    def _first(query: dict[str, list[str]], name: str, default: str) -> str:
        values = query.get(name)
        return values[0] if values else default

    @staticmethod
    def _integer(value: str) -> int | None:
        try:
            return int(value) if value.strip() else None
        except ValueError:
            return None

    @staticmethod
    def _non_negative_int(value: object) -> int:
        try:
            return max(0, int(str(value).strip()))
        except (TypeError, ValueError):
            return 0

    @staticmethod
    def _decimal(value: str) -> Decimal | None:
        if not value.strip():
            return None
        try:
            return Decimal(value)
        except InvalidOperation:
            return None


def create_server(
    service: OrderService,
    host: str = DEFAULT_HOST,
    port: int = DEFAULT_PORT,
) -> ThreadingHTTPServer:
    """创建可供 Docker 和测试复用的 HTTP 服务实例。"""

    class RequestHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 - stdlib handler contract
            parsed = urlsplit(self.path)
            if parsed.path == "/health":
                self._send_json(200, {"status": "UP"})
                return
            if parsed.path == "/_fixture/stats":
                self._send_json(200, service.stats())
                return
            user_id = self._user_id()
            if not user_id:
                self._send_json(401, {"code": "USER_REQUIRED", "message": "缺少 X-User-Id"})
                return
            path_parts = [unquote(part) for part in parsed.path.split("/") if part]
            if parsed.path == "/orders/search":
                status, response = service.search_orders(user_id, parse_qs(parsed.query))
                self._send_json(status, response)
                return
            if len(path_parts) == 2 and path_parts[0] == "orders":
                status, response = service.find_order(user_id, path_parts[1])
                self._send_json(status, response)
                return
            if len(path_parts) == 3 and path_parts[0] == "orders" and path_parts[2] == "logistics":
                status, response = service.logistics(user_id, path_parts[1])
                self._send_json(status, response)
                return
            self._send_json(404, {"code": "NOT_FOUND", "message": "接口不存在"})

        def do_POST(self) -> None:  # noqa: N802 - stdlib handler contract
            parsed = urlsplit(self.path)
            user_id = self._user_id()
            if not user_id:
                self._send_json(401, {"code": "USER_REQUIRED", "message": "缺少 X-User-Id"})
                return
            path_parts = [unquote(part) for part in parsed.path.split("/") if part]
            if len(path_parts) != 3 or path_parts[0] != "orders":
                self._send_json(404, {"code": "NOT_FOUND", "message": "接口不存在"})
                return
            action_name = path_parts[2]
            if action_name not in {"refund", "expedite"}:
                self._send_json(404, {"code": "NOT_FOUND", "message": "接口不存在"})
                return
            body = self._read_json_body()
            if body is None:
                self._send_json(400, {"code": "INVALID_JSON", "message": "请求体不是 JSON 对象"})
                return
            status, response = service.action(
                user_id,
                path_parts[1],
                action_name,
                self.headers.get("Idempotency-Key"),
                body,
            )
            self._send_json(status, response)

        def do_DELETE(self) -> None:  # noqa: N802 - stdlib handler contract
            parsed = urlsplit(self.path)
            user_id = self._user_id()
            if not user_id:
                self._send_json(401, {"code": "USER_REQUIRED", "message": "缺少 X-User-Id"})
                return
            path_parts = [unquote(part) for part in parsed.path.split("/") if part]
            if len(path_parts) != 2 or path_parts[0] != "orders":
                self._send_json(404, {"code": "NOT_FOUND", "message": "接口不存在"})
                return
            status, response = service.action(
                user_id,
                path_parts[1],
                "delete",
                self.headers.get("Idempotency-Key"),
                {},
            )
            self._send_json(status, response)

        def _user_id(self) -> str:
            return (self.headers.get("X-User-Id") or "").strip()

        def _read_json_body(self) -> dict[str, object] | None:
            if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
                raw_body = self._read_chunked_body()
            else:
                raw_body = self._read_length_body()
            if raw_body is None:
                return None
            try:
                payload = json.loads(raw_body or b"{}")
            except (json.JSONDecodeError, UnicodeDecodeError):
                return None
            return payload if isinstance(payload, dict) else None

        def _read_length_body(self) -> bytes | None:
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                return None
            if length < 0 or length > MAX_BODY_BYTES:
                return None
            body = self.rfile.read(length)
            return body if len(body) == length else None

        def _read_chunked_body(self) -> bytes | None:
            chunks = bytearray()
            while True:
                line = self.rfile.readline(128)
                if not line or len(line) > 128:
                    return None
                size_text = line.split(b";", 1)[0].strip()
                try:
                    chunk_size = int(size_text, 16)
                except ValueError:
                    return None
                if chunk_size < 0 or len(chunks) + chunk_size > MAX_BODY_BYTES:
                    return None
                if chunk_size == 0:
                    while True:
                        trailer = self.rfile.readline(128)
                        if not trailer or trailer in {b"\r\n", b"\n"}:
                            return bytes(chunks) if trailer else None
                chunk = self.rfile.read(chunk_size)
                if len(chunk) != chunk_size or self.rfile.read(2) != b"\r\n":
                    return None
                chunks.extend(chunk)

        def _send_json(self, status: int, payload: object) -> None:
            body = json_bytes(payload)
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format: str, *args: object) -> None:
            # 不记录用户身份、幂等键或请求体，避免验收日志携带业务数据。
            return

    return ThreadingHTTPServer((host, port), RequestHandler)


def main() -> None:
    service = OrderService(os.getenv("ORDER_SERVICE_DATABASE_PATH", DEFAULT_DATABASE_PATH))
    server = create_server(
        service,
        os.getenv("ORDER_SERVICE_HOST", DEFAULT_HOST),
        int(os.getenv("ORDER_SERVICE_PORT", str(DEFAULT_PORT))),
    )
    print("order-service-fixture listening", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
