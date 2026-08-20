from pathlib import Path
import unittest

from scripts.convention_check.checker import (
    DIRECT_DEPENDENCIES,
    FORBIDDEN_DIRECT_DEPENDENCIES,
    FORBIDDEN_SOURCE_TEXT,
    check_repository,
)


class ConventionCheckTest(unittest.TestCase):
    """验证代码库持续满足 Commerce Guardian Agent 边界约定。"""

    def test_repository_conventions(self) -> None:
        issues = check_repository(Path(__file__).parents[2])
        self.assertEqual((), issues, "\n".join(issue.format() for issue in issues))

    def test_current_provider_and_json_contract_is_not_legacy_rule(self) -> None:
        infrastructure = "commerce-guardian-agent-infrastructure"
        self.assertNotIn("deepseek", FORBIDDEN_SOURCE_TEXT)
        self.assertIn(
            ("tools.jackson.core", "jackson-databind"),
            DIRECT_DEPENDENCIES[infrastructure],
        )
        self.assertIn(
            ("com.fasterxml.jackson.core", "jackson-databind"),
            FORBIDDEN_DIRECT_DEPENDENCIES[infrastructure],
        )
