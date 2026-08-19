from pathlib import Path
import unittest

from scripts.convention_check.checker import check_repository


class ConventionCheckTest(unittest.TestCase):
    """验证代码库持续满足 Commerce Guardian Agent 边界约定。"""

    def test_repository_conventions(self) -> None:
        issues = check_repository(Path(__file__).parents[2])
        self.assertEqual((), issues, "\n".join(issue.format() for issue in issues))
