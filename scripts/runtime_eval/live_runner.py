"""本机真实模型评测摘要收口；不会在 CI 中调用或保存敏感响应。"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Iterable, Mapping


SAFE_KEYS = (
    "scenarioId",
    "repetition",
    "actualDecision",
    "itemKinds",
    "openInteractions",
    "mutationCount",
    "safetyPassed",
    "routingPassed",
)
FORBIDDEN_KEYS = frozenset(
    {"prompt", "thinking", "response", "rawResponse", "apiKey", "secret", "headers"}
)


def sanitize_observation(observation: Mapping[str, object]) -> dict[str, object]:
    """只保留可审计字段，拒绝把原始模型输出写入报告。"""

    forbidden = FORBIDDEN_KEYS.intersection(observation)
    if forbidden:
        raise ValueError("真实模型报告不得包含原始 Prompt、Thinking、响应或密钥字段")
    return {key: observation[key] for key in SAFE_KEYS if key in observation}


def write_summary(observations: Iterable[Mapping[str, object]], report_dir: Path) -> None:
    """写入本机忽略目录下的 JSON/Markdown 摘要。"""

    records = [sanitize_observation(observation) for observation in observations]
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / "live-summary.json").write_text(
        json.dumps({"cases": records}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (report_dir / "live-summary.md").write_text(
        "# Live Agent Quality Eval\n\n"
        f"- cases: {len(records)}\n"
        "- Only structured decisions and item kinds are retained.\n",
        encoding="utf-8",
    )


def main(arguments: list[str] | None = None) -> int:
    """只允许本机读取预先脱敏的 JSON；CI 中明确失败。"""

    if os.environ.get("CI", "").lower() in {"1", "true", "yes"}:
        print("真实模型评测仅允许在本机执行。", file=sys.stderr)
        return 2
    parser = argparse.ArgumentParser(description="收口本机真实模型评测摘要")
    parser.add_argument("input", type=Path, help="预先脱敏的结构化 JSON 数组")
    parser.add_argument("--report-dir", type=Path, default=Path("output/runtime_eval"))
    options = parser.parse_args(arguments)
    try:
        observations = json.loads(options.input.read_text(encoding="utf-8"))
        if not isinstance(observations, list):
            raise ValueError("输入必须是 JSON 数组")
        write_summary(observations, options.report_dir)
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"本机评测摘要失败：{failure}", file=sys.stderr)
        return 1
    print(f"已写入本机评测摘要：{options.report_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
