from pathlib import Path
import re
import unittest


class WorkflowInventorySqlTest(unittest.TestCase):
    def test_inventory_is_read_only_and_covers_drain_objects(self):
        path = Path(__file__).parents[1] / "maintenance" / "workflow-inventory.sql"
        sql = path.read_text(encoding="utf-8").upper()
        executable = re.sub(r"--[^\n]*", "", sql)
        self.assertNotRegex(executable, r"\b(INSERT|UPDATE|DELETE|ALTER|DROP|CREATE|TRUNCATE|LOCK)\b")
        for table in (
            "AGENT_WORKFLOW_RUN",
            "AGENT_QUESTION_CARD",
            "AGENT_WORKFLOW_CHECKPOINT",
            "EXTERNAL_ACTION_COMMAND",
            "AGENT_TURN",
            "AGENT_GRAPH_SNAPSHOT",
        ):
            self.assertIn(table, sql)


if __name__ == "__main__":
    unittest.main()
