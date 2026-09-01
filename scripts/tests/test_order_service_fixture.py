import json
import tempfile
import threading
import unittest
from datetime import datetime, timezone
from http.client import HTTPConnection
from pathlib import Path

from scripts.acceptance.order_service_fixture.server import OrderService, create_server


class OrderServiceFixtureTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        now = datetime(2026, 8, 23, 12, 0, tzinfo=timezone.utc)
        self.service = OrderService(
            str(Path(self.temp_dir.name) / "order-service.db"),
            clock=lambda: now,
        )
        self.server = create_server(self.service, "127.0.0.1", 0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp_dir.cleanup()

    def request(self, method, path, body=None, headers=None):
        connection = HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        payload = None if body is None else json.dumps(body).encode("utf-8")
        request_headers = {"X-User-Id": "demo-user-1"}
        if payload is not None:
            request_headers["Content-Type"] = "application/json"
        if headers:
            request_headers.update(headers)
        connection.request(method, path, body=payload, headers=request_headers)
        response = connection.getresponse()
        data = json.loads(response.read().decode("utf-8"))
        connection.close()
        return response.status, data

    def test_search_and_logistics_are_served_from_independent_fixture_store(self):
        status, orders = self.request(
            "GET",
            "/orders/search?createdFrom=2026-08-23T00:00:00Z&visibility=ACTIVE&limit=10",
        )
        self.assertEqual(200, status)
        self.assertTrue(any(order["orderId"] == "ORDER-EXT-TODAY-001" for order in orders))

        status, events = self.request("GET", "/orders/ORDER-EXT-STALLED-001/logistics")
        self.assertEqual(200, status)
        self.assertGreaterEqual(len(events), 2)

    def test_refund_replay_mutates_order_once_and_survives_new_connection(self):
        key = "fixture-refund-once"
        status, first = self.request(
            "POST",
            "/orders/ORDER-EXT-REFUND-001/refund",
            {"reason": "商品不符"},
            {"Idempotency-Key": key},
        )
        self.assertEqual(200, status)
        self.assertEqual("REFUNDED", first["code"])

        status, second = self.request(
            "POST",
            "/orders/ORDER-EXT-REFUND-001/refund",
            {"reason": "商品不符"},
            {"Idempotency-Key": key},
        )
        self.assertEqual(200, status)
        self.assertEqual(first, second)

        status, order = self.request("GET", "/orders/ORDER-EXT-REFUND-001")
        self.assertEqual(200, status)
        self.assertEqual("REFUNDED", order["status"])
        status, stats = self.request("GET", "/_fixture/stats")
        self.assertEqual(200, status)
        self.assertEqual(1, stats["idempotencyRecords"])
        self.assertEqual(1, stats["businessMutations"])

    def test_visibility_action_is_removed(self):
        status, response = self.request(
            "POST",
            "/orders/ORDER-EXT-TODAY-001/visibility",
            {"visibility": "HIDDEN"},
            {"Idempotency-Key": "fixture-hide-removed"},
        )
        self.assertEqual(404, status)
        self.assertEqual("NOT_FOUND", response["code"])

    def test_delete_removes_order_and_logistics_once(self):
        key = "fixture-delete-once"
        status, first = self.request(
            "DELETE",
            "/orders/ORDER-EXT-TODAY-001",
            headers={"Idempotency-Key": key},
        )
        self.assertEqual(200, status)
        self.assertEqual("ORDER_DELETED", first["code"])

        status, second = self.request(
            "DELETE",
            "/orders/ORDER-EXT-TODAY-001",
            headers={"Idempotency-Key": key},
        )
        self.assertEqual(200, status)
        self.assertEqual(first, second)
        status, missing = self.request("GET", "/orders/ORDER-EXT-TODAY-001")
        self.assertEqual(404, status)
        self.assertEqual("ORDER_NOT_FOUND", missing["code"])
        status, logistics = self.request("GET", "/orders/ORDER-EXT-TODAY-001/logistics")
        self.assertEqual(404, status)
        self.assertEqual([], logistics)
        status, stats = self.request("GET", "/_fixture/stats")
        self.assertEqual(200, status)
        self.assertEqual(1, stats["idempotencyRecords"])
        self.assertEqual(1, stats["businessMutations"])

    def test_expedite_transient_failures_do_not_create_idempotency_or_mutate(self):
        service = OrderService(
            str(Path(self.temp_dir.name) / "retryable-order-service.db"),
            clock=self.service.clock,
            expedite_transient_failures=2,
        )
        key = "fixture-expedite-retry"
        for attempt in range(2):
            status, response = service.action(
                "demo-user-1",
                "ORDER-EXT-TODAY-001",
                "expedite",
                key,
                {},
            )
            self.assertEqual(200, status)
            self.assertFalse(response["success"])
            self.assertTrue(response["retryable"])
            self.assertEqual("FIXTURE_TRANSIENT_FAILURE", response["code"])
            self.assertIn(str(attempt + 1), response["message"])
            self.assertEqual(
                {
                    "idempotencyRecords": 0,
                    "businessMutations": 0,
                    "injectedFailures": attempt + 1,
                },
                service.stats(),
            )

        status, response = service.action(
            "demo-user-1",
            "ORDER-EXT-TODAY-001",
            "expedite",
            key,
            {},
        )
        self.assertEqual(200, status)
        self.assertEqual("EXPEDITED", response["code"])
        self.assertEqual(
            {
                "idempotencyRecords": 1,
                "businessMutations": 1,
                "injectedFailures": 2,
            },
            service.stats(),
        )
        self.assertEqual(
            "EXPEDITE_REQUESTED",
            service.find_order("demo-user-1", "ORDER-EXT-TODAY-001")[1]["logisticsStatus"],
        )

    def test_accepts_chunked_json_body_from_java_http_client(self):
        connection = HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        body = json.dumps({"reason": "商品不符"}, ensure_ascii=False).encode("utf-8")
        connection.putrequest("POST", "/orders/ORDER-EXT-REFUND-001/refund")
        connection.putheader("X-User-Id", "demo-user-1")
        connection.putheader("Idempotency-Key", "fixture-chunked-refund")
        connection.putheader("Content-Type", "application/json")
        connection.putheader("Transfer-Encoding", "chunked")
        connection.endheaders()
        connection.send(f"{len(body):X}\r\n".encode("ascii") + body + b"\r\n0\r\n\r\n")
        response = connection.getresponse()
        payload = json.loads(response.read().decode("utf-8"))
        connection.close()

        self.assertEqual(200, response.status)
        self.assertEqual("REFUNDED", payload["code"])


if __name__ == "__main__":
    unittest.main()
