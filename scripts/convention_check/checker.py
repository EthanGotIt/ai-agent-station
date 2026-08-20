"""使用 Python 标准库检查仓库内可以机械判定的工程规范。"""

from __future__ import annotations

import argparse
import re
import stat
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


JAVA_SOURCE_ROOTS = (
    "commerce-guardian-agent-core/src/main/java",
    "commerce-guardian-agent-core/src/test/java",
    "commerce-guardian-agent-infrastructure/src/main/java",
    "commerce-guardian-agent-infrastructure/src/test/java",
    "commerce-guardian-agent-app/src/main/java",
    "commerce-guardian-agent-app/src/test/java",
)

FORBIDDEN_SOURCE_TEXT = (
    "AI Agent Station",
    "ai-agent-station",
    "v3",
    "AliyunRespon" + "sesGateway",
    "cn.ethan.ai",
    "code_" + "interpreter",
    "deepseek",
    "qwen3.7-" + "max",
    "spring-statemachine",
    "spring-ai-agent-utils",
    "web_" + "extractor",
    "AgentScope",
    "agentscope",
    "spring-ai-session",
    "/api/v1",
    "AgentMemory",
    "AGENT_MEMORY",
    "AI_SESSION",
    "SessionExecution",
    "SessionService",
    "AfterSales",
    "DEMO_AFTER_SALES_CASE",
)
SCAN_SUFFIXES = {".java", ".xml", ".yml", ".yaml", ".sql", ".md", ".ts", ".tsx", ".css"}
IGNORED_DIRECTORIES = {".agents", ".codex", ".git", ".idea", "target", "node_modules", "dist"}
EMPTY_DIRECTORY_IGNORES = {".git", "target", "node_modules", "dist"}
TOP_LEVEL_TYPE_PATTERN = re.compile(
    r"^(?:public\s+)?(?:(?:final|abstract|sealed|non-sealed)\s+)*"
    r"(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)",
    re.MULTILINE,
)
PACKAGE_PATTERN = re.compile(r"^package\s+([\w.]+);", re.MULTILINE)
WILDCARD_IMPORT_PATTERN = re.compile(r"^import\s+[\w.]+\.\*;", re.MULTILINE)
ENUM_PATTERN = re.compile(r"\benum\s+([A-Z][A-Za-z0-9_]*)")
JAVADOC_DATE_PATTERN = re.compile(r"@date\s+\d{4}-\d{2}-\d{2}")
SQL_IDENTIFIER_PATTERN = re.compile(
    r"\b(?:FROM|JOIN|INTO|UPDATE|TABLE|DATABASE)"
    r"(?:\s+IF\s+NOT\s+EXISTS)?\s+`?([A-Za-z][A-Za-z0-9_]*)`?",
    re.IGNORECASE,
)
ANNOTATION_IDENTIFIER_PATTERN = re.compile(
    r"@(TableName|TableId|TableField)\(\"([A-Za-z][A-Za-z0-9_]*)\"\)"
)
SILENT_CATCH_PATTERN = re.compile(r"catch\s*\([^)]*\bignored\b[^)]*\)")
SOURCE_SECRET_PATTERN = re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b")
DIRECT_DEPENDENCIES = {
    "commerce-guardian-agent-infrastructure": {
        ("com.fasterxml.jackson.core", "jackson-databind"),
        ("com.baomidou", "mybatis-plus-core"),
        ("com.baomidou", "mybatis-plus-annotation"),
    },
}
FORBIDDEN_DIRECT_DEPENDENCIES = {
    "commerce-guardian-agent-infrastructure": {
        ("tools.jackson.core", "jackson-databind"),
        ("com.baomidou", "mybatis-plus"),
    },
}
DIRECT_INSTANT_NOW_PATTERN = re.compile(r"\bInstant\.now\(\s*\)")
THREAD_SLEEP_PATTERN = re.compile(r"\bThread\.sleep\s*\(")


@dataclass(frozen=True, order=True)
class CheckIssue:
    """单条规范问题。"""

    code: str
    path: str
    message: str
    line: int | None = None

    def format(self) -> str:
        """生成适合终端和 Git Hook 展示的稳定文本。"""

        location = f"{self.path}:{self.line}" if self.line else self.path
        return f"[{self.code}] {location} - {self.message}"


class ConventionChecker:
    """仓库规范检查器。"""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.issues: list[CheckIssue] = []
        self.dto_paths: dict[str, Path] = {}

    def run(self) -> tuple[CheckIssue, ...]:
        """执行全部检查并返回排序后的不可变结果。"""

        self._check_java_sources()
        self._check_application_config()
        self._check_empty_directories()
        self._check_forbidden_source_text()
        self._check_database_identifiers()
        self._check_module_dependencies()
        self._check_direct_dependencies()
        self._check_time_boundaries()
        self._check_build_safety()
        return tuple(sorted(set(self.issues)))

    def _check_java_sources(self) -> None:
        for source_root_text in JAVA_SOURCE_ROOTS:
            source_root = self.root / source_root_text
            if not source_root.exists():
                continue
            for path in sorted(source_root.rglob("*.java")):
                self._check_java_file(path, source_root)

    def _check_java_file(self, path: Path, source_root: Path) -> None:
        text = path.read_text(encoding="utf-8")
        relative_path = self._relative(path)
        package_match = PACKAGE_PATTERN.search(text)
        expected_package = ".".join(path.parent.relative_to(source_root).parts)
        if package_match is None:
            self._add("JAVA_PACKAGE", path, "缺少 package 声明")
        elif package_match.group(1) != expected_package:
            self._add(
                "JAVA_PACKAGE",
                path,
                f"package 应为 {expected_package}，实际为 {package_match.group(1)}",
            )
        elif not package_match.group(1).startswith("cn.ethan"):
            self._add("JAVA_ROOT_PACKAGE", path, "Java 根包必须为 cn.ethan")

        if package_match is not None:
            forbidden_package_parts = {
                "model", "models", "service", "services", "port", "ports", "entity", "entities",
                "mapper", "mappers", "gateway", "gateways", "controller", "controllers", "dto", "dtos",
                "handler", "handlers", "impl", "common", "support",
            }
            package_parts = set(package_match.group(1).split("."))
            forbidden = sorted(package_parts.intersection(forbidden_package_parts))
            if forbidden:
                self._add(
                    "JAVA_GENERIC_PACKAGE",
                    path,
                    f"禁止使用横向技术包：{', '.join(forbidden)}；请先按能力分包",
                )

        top_level_types = TOP_LEVEL_TYPE_PATTERN.findall(text)
        if len(top_level_types) != 1:
            self._add("JAVA_TOP_LEVEL_TYPE", path, "每个 Java 文件必须且只能有一个顶级类型")
            return

        type_kind, type_name = top_level_types[0]
        if path.stem != type_name:
            self._add("JAVA_FILE_NAME", path, f"文件名必须与顶级类型 {type_name} 一致")
        if WILDCARD_IMPORT_PATTERN.search(text):
            self._add("JAVA_WILDCARD_IMPORT", path, "禁止使用通配符 import")
        if SILENT_CATCH_PATTERN.search(text):
            self._add(
                "JAVA_SILENT_CATCH",
                path,
                "禁止使用 ignored 命名吞掉异常；应记录稳定上下文或向上转换",
            )
        if "@author ethan" not in text or JAVADOC_DATE_PATTERN.search(text) is None:
            self._add("JAVA_JAVADOC", path, "顶级类型必须包含统一的 @author 和 @date JavaDoc")

        is_test = "test" in path.relative_to(self.root).parts
        if is_test:
            if not type_name.endswith(("Test", "IT")):
                self._add("JAVA_TEST_SUFFIX", path, "测试类型必须使用 Test 或 IT 后缀")
        if "impl" in Path(relative_path).parts:
            self._add("JAVA_IMPL_PACKAGE", path, "禁止使用含义泛化的 impl 包")
        if type_name.endswith("Body"):
            self._add("JAVA_DTO_SUFFIX", path, "HTTP 请求和响应对象必须统一使用 Dto 后缀")
        if type_name.endswith("Dto") and not (
            "commerce-guardian-agent-app" in Path(relative_path).parts
            and ".agent.api" in (package_match.group(1) if package_match else "")
        ):
            self._add("JAVA_DTO_PACKAGE", path, "Dto 只能位于 app 的 agent.api 能力包")
        if type_name.endswith("Dto"):
            self.dto_paths[type_name] = path
            if not type_name.startswith("Agent"):
                self._add("JAVA_DTO_PREFIX", path, "本项目 DTO 必须使用 Agent 业务域前缀")
            for semantic in ("Request", "Response", "Event"):
                semantic_suffix = semantic + "Dto"
                if not type_name.endswith(semantic_suffix):
                    continue
                qualifier = type_name.removesuffix(semantic_suffix)
                qualifier_tokens = re.findall(
                    r"[A-Z]+(?=[A-Z][a-z]|\d|$)|[A-Z][a-z0-9]*",
                    qualifier,
                )
                if len(qualifier_tokens) < 2:
                    self._add(
                        "JAVA_DTO_DIMENSION",
                        path,
                        "接口 DTO 必须同时包含业务域、操作和语义维度",
                    )
                break
        if type_name.endswith("Entity") and not (
            "commerce-guardian-agent-infrastructure" in Path(relative_path).parts
            and ".persistence" in (package_match.group(1) if package_match else "")
        ):
            self._add("JAVA_ENTITY_PACKAGE", path, "Entity 只能位于 infrastructure 的 persistence 能力包")
        if type_name.endswith("Model") and "commerce-guardian-agent-core" not in Path(relative_path).parts:
            self._add("JAVA_MODEL_PACKAGE", path, "Model 必须位于 core 模块")

        for enum_name in ENUM_PATTERN.findall(text):
            if not enum_name.endswith("Enum"):
                self._add("JAVA_ENUM_SUFFIX", path, f"枚举 {enum_name} 必须使用 Enum 后缀")
        if type_name.endswith("Utils"):
            if type_kind != "class" or f"final class {type_name}" not in text:
                self._add("JAVA_UTILS_FINAL", path, "Utils 类型必须是 final class")
            private_constructor = re.compile(rf"private\s+{re.escape(type_name)}\s*\(")
            if private_constructor.search(text) is None:
                self._add("JAVA_UTILS_CONSTRUCTOR", path, "Utils 类型必须声明私有构造方法")

    def _check_application_config(self) -> None:
        resources = self.root / "commerce-guardian-agent-app/src/main/resources"
        if not resources.exists():
            self._add("APPLICATION_CONFIG", resources, "缺少应用资源目录")
            return
        configs = sorted(
            path
            for path in resources.iterdir()
            if path.is_file()
            and path.name.startswith("application")
            and path.suffix.lower() in {".yml", ".yaml", ".properties"}
        )
        if [path.name for path in configs] != ["application.yml"]:
            names = ", ".join(path.name for path in configs) or "无"
            self._add(
                "APPLICATION_CONFIG",
                resources,
                f"只允许一个 application.yml，当前为：{names}",
            )

    def _check_empty_directories(self) -> None:
        """扫描整个仓库，仅忽略 Git 内部目录与可再生成的构建产物。"""

        for path in sorted(self.root.rglob("*")):
            if not path.is_dir():
                continue
            relative_parts = path.relative_to(self.root).parts
            if EMPTY_DIRECTORY_IGNORES.intersection(relative_parts):
                continue
            try:
                next(path.iterdir())
            except StopIteration:
                self._add("EMPTY_DIRECTORY", path, "禁止保留无用途的空目录")

    def _check_forbidden_source_text(self) -> None:
        for path in self._iter_scanned_files():
            text = path.read_text(encoding="utf-8")
            for forbidden in FORBIDDEN_SOURCE_TEXT:
                if forbidden in text:
                    self._add(
                        "FORBIDDEN_SOURCE_TEXT",
                        path,
                        f"发现禁用内容：{forbidden}",
                        self._line_of(text, forbidden),
                    )
            secret_match = SOURCE_SECRET_PATTERN.search(text)
            if secret_match is not None:
                self._add(
                    "SOURCE_SECRET",
                    path,
                    "源码、配置和 SQL 中禁止出现疑似真实密钥",
                    self._line_at(text, secret_match.start()),
                )

    def _check_database_identifiers(self) -> None:
        for path in self._iter_scanned_files():
            if path.suffix.lower() not in {".sql", ".xml", ".java"}:
                continue
            text = path.read_text(encoding="utf-8")
            for match in SQL_IDENTIFIER_PATTERN.finditer(text):
                identifier = match.group(1)
                if any(character.islower() for character in identifier):
                    self._add(
                        "DATABASE_IDENTIFIER_CASE",
                        path,
                        f"数据库物理名称必须大写：{identifier}",
                        self._line_at(text, match.start(1)),
                    )
            for match in ANNOTATION_IDENTIFIER_PATTERN.finditer(text):
                identifier = match.group(2)
                if any(character.islower() for character in identifier):
                    self._add(
                        "DATABASE_IDENTIFIER_CASE",
                        path,
                        f"数据库映射名称必须大写：{identifier}",
                        self._line_at(text, match.start(2)),
                    )

    def _check_module_dependencies(self) -> None:
        allowed_dependencies = {
            "commerce-guardian-agent-core": set(),
            "commerce-guardian-agent-infrastructure": {"commerce-guardian-agent-core"},
            "commerce-guardian-agent-app": {
                "commerce-guardian-agent-core",
                "commerce-guardian-agent-infrastructure",
            },
        }
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        for module, allowed in allowed_dependencies.items():
            pom = self.root / module / "pom.xml"
            if not pom.exists():
                self._add("MAVEN_MODULE", pom, "缺少模块 POM")
                continue
            try:
                project = ElementTree.parse(pom).getroot()
            except ElementTree.ParseError as exception:
                self._add("MAVEN_POM", pom, f"POM 无法解析：{exception}")
                continue
            for dependency in project.findall("m:dependencies/m:dependency", namespace):
                group_id = dependency.findtext("m:groupId", default="", namespaces=namespace)
                artifact_id = dependency.findtext(
                    "m:artifactId",
                    default="",
                    namespaces=namespace,
                )
                if group_id == "cn.ethan" and artifact_id not in allowed:
                    self._add(
                        "MAVEN_DEPENDENCY_DIRECTION",
                        pom,
                        f"{module} 不允许依赖 {artifact_id}",
                    )

    def _check_build_safety(self) -> None:
        pom = self.root / "commerce-guardian-agent-app/pom.xml"
        if not pom.exists():
            return
        text = pom.read_text(encoding="utf-8")
        if re.search(r"<filtering>\s*true\s*</filtering>", text, re.IGNORECASE):
            self._add(
                "MAVEN_RESOURCE_FILTERING",
                pom,
                "application.yml 禁止开启 Maven 资源过滤，避免密钥被写入制品",
            )

    def _check_direct_dependencies(self) -> None:
        """校验源码直接使用且跨版本敏感的基础库显式由模块声明。"""

        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        for module, expected in DIRECT_DEPENDENCIES.items():
            pom = self.root / module / "pom.xml"
            if not pom.exists():
                continue
            project = ElementTree.parse(pom).getroot()
            declared = {
                (
                    dependency.findtext("m:groupId", default="", namespaces=namespace),
                    dependency.findtext("m:artifactId", default="", namespaces=namespace),
                )
                for dependency in project.findall("m:dependencies/m:dependency", namespace)
            }
            for group_id, artifact_id in sorted(expected):
                if (group_id, artifact_id) not in declared:
                    self._add(
                        "MAVEN_DIRECT_DEPENDENCY",
                        pom,
                        f"源码使用的 {group_id}:{artifact_id} 必须直接声明",
                    )
            for group_id, artifact_id in sorted(FORBIDDEN_DIRECT_DEPENDENCIES.get(module, set())):
                if (group_id, artifact_id) in declared:
                    self._add(
                        "MAVEN_FORBIDDEN_DEPENDENCY",
                        pom,
                        f"禁止直接声明 {group_id}:{artifact_id}，避免错误 API 或聚合依赖掩盖真实依赖",
                    )

    def _check_time_boundaries(self) -> None:
        """禁止业务实现和测试依赖不可控墙钟或等待时间。"""

        for source_root_text in JAVA_SOURCE_ROOTS:
            source_root = self.root / source_root_text
            if not source_root.exists():
                continue
            is_test_root = "/src/test/" in source_root_text
            for path in sorted(source_root.rglob("*.java")):
                text = path.read_text(encoding="utf-8")
                if not is_test_root:
                    instant_now = DIRECT_INSTANT_NOW_PATTERN.search(text)
                    if instant_now is not None:
                        self._add(
                            "JAVA_CLOCK_BOUNDARY",
                            path,
                            "业务时间必须通过注入的 Clock 获取，禁止直接调用 Instant.now()",
                            self._line_at(text, instant_now.start()),
                        )
                if is_test_root:
                    thread_sleep = THREAD_SLEEP_PATTERN.search(text)
                    if thread_sleep is not None:
                        self._add(
                            "JAVA_TEST_SLEEP",
                            path,
                            "测试禁止使用 Thread.sleep；应使用 latch、可控时钟或确定性同步",
                            self._line_at(text, thread_sleep.start()),
                        )

    def _iter_scanned_files(self) -> Iterable[Path]:
        for path in sorted(self.root.rglob("*")):
            if not path.is_file() or path.suffix.lower() not in SCAN_SUFFIXES:
                continue
            relative_parts = path.relative_to(self.root).parts
            if IGNORED_DIRECTORIES.intersection(relative_parts):
                continue
            yield path

    def _add(self, code: str, path: Path, message: str, line: int | None = None) -> None:
        self.issues.append(CheckIssue(code, self._relative(path), message, line))

    def _relative(self, path: Path) -> str:
        try:
            return path.resolve().relative_to(self.root).as_posix()
        except ValueError:
            return path.as_posix()

    @staticmethod
    def _line_of(text: str, value: str) -> int:
        return ConventionChecker._line_at(text, text.index(value))

    @staticmethod
    def _line_at(text: str, offset: int) -> int:
        return text.count("\n", 0, offset) + 1


def check_repository(root: Path) -> tuple[CheckIssue, ...]:
    """检查指定仓库，便于 CLI、Hook 和单元测试复用同一实现。"""

    return ConventionChecker(root).run()


def install_hook(root: Path) -> int:
    """将仓库内版本化 Hook 目录配置为当前仓库的 Git hooksPath。"""

    hook = root / ".githooks/pre-commit"
    if not hook.exists():
        print("未找到 .githooks/pre-commit，无法安装 Hook。", file=sys.stderr)
        return 2
    hook.chmod(
        hook.stat().st_mode
        | stat.S_IXUSR
        | stat.S_IXGRP
        | stat.S_IXOTH
    )
    try:
        subprocess.run(
            ["git", "config", "core.hooksPath", ".githooks"],
            cwd=root,
            check=True,
        )
        subprocess.run(
            ["git", "config", "hooks.conventionPython", sys.executable],
            cwd=root,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError) as exception:
        print(f"安装 Git Hook 失败：{exception}", file=sys.stderr)
        return 2
    print(f"Git pre-commit Hook 已启用，Python：{sys.executable}")
    return 0


def _repository_root(candidate: Path) -> Path:
    current = candidate.resolve()
    for path in (current, *current.parents):
        if (path / "pom.xml").exists() and (path / "AGENTS.md").exists():
            return path
    raise FileNotFoundError("未找到同时包含 pom.xml 和 AGENTS.md 的仓库根目录")


def main(arguments: Sequence[str] | None = None) -> int:
    """执行命令行检查。"""

    parser = argparse.ArgumentParser(description="检查 Commerce Guardian Agent 工程规范")
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="仓库根目录")
    parser.add_argument("--install-hook", action="store_true", help="启用版本化 pre-commit Hook")
    options = parser.parse_args(arguments)

    try:
        root = _repository_root(options.root)
    except FileNotFoundError as exception:
        print(str(exception), file=sys.stderr)
        return 2

    if options.install_hook:
        install_result = install_hook(root)
        if install_result != 0:
            return install_result

    issues = check_repository(root)
    if issues:
        print(f"规范检查失败，共 {len(issues)} 项：", file=sys.stderr)
        for issue in issues:
            print(issue.format(), file=sys.stderr)
        return 1

    print("规范检查通过。")
    return 0
