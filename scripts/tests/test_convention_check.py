"""工程规范检查器测试。"""

from pathlib import Path
import unittest

from scripts.convention_check import check_repository


class ConventionCheckTest(unittest.TestCase):
    """验证当前仓库能够完整通过机械规范。"""

    def test_repository_conforms_to_conventions(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]

        issues = check_repository(repository_root)

        self.assertEqual((), issues, "\n".join(issue.format() for issue in issues))


if __name__ == "__main__":
    unittest.main()
