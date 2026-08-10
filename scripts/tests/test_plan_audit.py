"""最终执行计划审计器测试。"""

from pathlib import Path
import unittest

from scripts.plan_audit import audit_repository


class PlanAuditTest(unittest.TestCase):
    """验证计划审计器能够稳定报告当前仓库。"""

    def test_audit_has_unique_requirement_ids(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]

        results = audit_repository(repository_root)
        requirement_ids = [result.requirement_id for result in results]

        self.assertTrue(results)
        self.assertEqual(len(requirement_ids), len(set(requirement_ids)))
        self.assertEqual([f"P{number:02d}" for number in range(1, 24)], requirement_ids)


if __name__ == "__main__":
    unittest.main()
