"""以可重复的静态证据审计最终执行计划中的关键交付物。"""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Sequence


@dataclass(frozen=True)
class AuditResult:
    """单项计划审计结果。"""

    requirement_id: str
    description: str
    passed: bool
    evidence: str


class PlanAuditor:
    """最终计划静态审计器。"""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()

    def run(self) -> tuple[AuditResult, ...]:
        """执行当前可以机械证明的计划检查。"""

        checks: tuple[tuple[str, str, Callable[[], tuple[bool, str]]], ...] = (
            ("P01", "Maven 模块与依赖方向", self._check_modules),
            ("P02", "禁用重型能力", self._check_forbidden_capabilities),
            ("P03", "单一 application.yml", self._check_single_application_config),
            ("P04", "独立 AgentRouterService 及测试", self._check_router),
            ("P05", "Workflow 执行限制", self._check_workflow_limits),
            ("P06", "订单流程分支测试", self._check_order_workflow_tests),
            ("P07", "持久化 Workflow QuestionCard", self._check_pending_input_tests),
            ("P08", "统一队列、生命周期与取消", self._check_lifecycle_tests),
            ("P09", "Qwen 3.7 Plus 与 Max 对照配置", self._check_qwen_models),
            ("P10", "AgentScope ReAct 与事件边界", self._check_react_tools),
            ("P11", "同步、SSE 与取消 API", self._check_api),
            ("P12", "OutputManager 可观测与脱敏", self._check_output_manager),
            ("P13", "大写数据库对象", self._check_database_objects),
            ("P14", "分层自动测试套件", self._check_test_suites),
            ("P15", "运行手册", self._check_runbook),
            ("P16", "SSE 入队准入与取消归属", self._check_stream_admission),
            ("P17", "队列与 AgentScope 类型化配置", self._check_runtime_properties),
            ("P18", "HTTP 订单适配器防护", self._check_http_order_gateway),
            ("P19", "会话隔离记忆存储", self._check_session_guard),
            ("P20", "构建与密钥守卫", self._check_build_guard),
            ("P21", "输入输出安全归一化", self._check_input_output_guard),
            ("P22", "文档索引、交接与提示词边界", self._check_documentation),
            ("P23", "QuestionCard 协议与会话记忆 V2", self._check_questioncard_memory_v2),
        )
        return tuple(
            AuditResult(requirement_id, description, *check())
            for requirement_id, description, check in checks
        )

    def _check_modules(self) -> tuple[bool, str]:
        pom = self._read("pom.xml")
        modules = re.findall(r"<module>([^<]+)</module>", pom)
        expected = [
            "ai-agent-station-core",
            "ai-agent-station-infrastructure",
            "ai-agent-station-app",
        ]
        return modules == expected, f"modules={modules}"

    def _check_forbidden_capabilities(self) -> tuple[bool, str]:
        forbidden = (
            "aliyunrespon" + "sesgateway",
            "code_" + "interpreter",
            "spring-statemachine",
            "spring-ai-agent-utils",
            "deepseek",
            "qwen3.7-" + "max",
            "web_" + "extractor",
        )
        matches: list[str] = []
        for path in self._source_files():
            text = path.read_text(encoding="utf-8")
            for value in forbidden:
                if value in text.lower():
                    matches.append(f"{path.relative_to(self.root).as_posix()}:{value}")
        return not matches, "无禁用能力" if not matches else ", ".join(matches)

    def _check_single_application_config(self) -> tuple[bool, str]:
        resources = self.root / "ai-agent-station-app/src/main/resources"
        configs = sorted(
            path.name
            for path in resources.glob("application*")
            if path.suffix.lower() in {".yml", ".yaml", ".properties"}
        )
        return configs == ["application.yml"], f"configs={configs}"

    def _check_router(self) -> tuple[bool, str]:
        source = self.root / (
            "ai-agent-station-core/src/main/java/cn/ethan/core/agent/service/"
            "AgentRouterService.java"
        )
        test = self.root / (
            "ai-agent-station-core/src/test/java/cn/ethan/core/agent/service/"
            "AgentRouterServiceTest.java"
        )
        passed = source.exists() and test.exists()
        return passed, f"source={source.exists()}, test={test.exists()}"

    def _check_workflow_limits(self) -> tuple[bool, str]:
        definition = self._read(
            "ai-agent-station-core/src/main/java/cn/ethan/core/workflow/model/"
            "WorkflowDefinitionModel.java"
        )
        passed = "Math.min(maxTransitions, 8)" in definition \
            and "Math.min(maxRetriesPerNode, 2)" in definition
        return passed, "maxTransitions<=8, maxRetriesPerNode<=2" if passed else "限制缺失"

    def _check_order_workflow_tests(self) -> tuple[bool, str]:
        test = self._read(
            "ai-agent-station-core/src/test/java/cn/ethan/core/agent/service/"
            "AgentRuntimeServiceTest.java"
        )
        required = (
            "workflowQuestionIsPersistedAndAnsweredExplicitly",
            "diagnosisBranchUsesSameOrderInquiryWorkflow",
            "OrderInquiryWorkflow",
            "structuredResult",
        )
        missing = [name for name in required if name not in test]
        return not missing, "订单分支齐全" if not missing else f"缺少 {missing}"

    def _check_pending_input_tests(self) -> tuple[bool, str]:
        test = self._read(
            "ai-agent-station-core/src/test/java/cn/ethan/core/agent/service/"
            "AgentRuntimeServiceTest.java"
        )
        required = (
            "workflowQuestionIsPersistedAndAnsweredExplicitly",
            "WorkflowAnswerRequestModel",
            "WAITING_USER_INPUT",
        )
        missing = [name for name in required if name not in test]
        workflow = self.root / (
            "ai-agent-station-core/src/main/java/cn/ethan/core/workflow/"
            "after_sales/AfterSalesRefundWorkflow.java"
        )
        passed = not missing and workflow.exists()
        evidence = f"missingCoreTests={missing}, resumableWorkflow={workflow.exists()}"
        return passed, evidence

    def _check_lifecycle_tests(self) -> tuple[bool, str]:
        test = self._read(
            "ai-agent-station-core/src/test/java/cn/ethan/core/agent/service/"
            "RequestLifecycleManagerTest.java"
        )
        required = (
            "followsPreparedQueuedActiveAndCompletedStates",
            "rejectsDuplicateRequestIdDuringTerminalRetention",
            "cancellationOnlyTargetsOwnedExecution",
            "terminalEntriesExpireAfterTtl",
        )
        missing = [name for name in required if name not in test]
        workflow_test = self._read(
            "ai-agent-station-core/src/test/java/cn/ethan/core/agent/service/"
            "AgentRuntimeServiceTest.java"
        )
        boundary_test = "workflowQuestionIsPersistedAndAnsweredExplicitly" in workflow_test
        queue_test = self._read(
            "ai-agent-station-core/src/test/java/cn/ethan/core/agent/service/"
            "SessionExecutionQueueManagerTest.java"
        )
        required_queue_tests = (
            "sameSessionExecutesStrictlyInFifoOrder",
            "differentSessionsCanExecuteInParallel",
            "rejectsSessionAndGlobalCapacityOverflowBeforeExecution",
            "waitingRequestTimesOutWithoutExecuting",
            "queuedCancellationSkipsExecutionAndKeepsFollowingRequest",
            "initialWorkerRejectionIsReturnedBeforeAdmissionCompletes",
        )
        missing_queue_tests = [
            name for name in required_queue_tests if name not in queue_test
        ]
        passed = not missing and boundary_test and not missing_queue_tests
        evidence = (
            f"missingLifecycleTests={missing}, "
            f"missingQueueTests={missing_queue_tests}, workflowBoundaryTest={boundary_test}"
        )
        return passed, evidence

    def _check_qwen_models(self) -> tuple[bool, str]:
        application = self._read("ai-agent-station-app/src/main/resources/application.yml")
        models = ("qwen3.7-plus", "qwen3.8-max")
        missing = [model for model in models if model not in application]
        required_configuration = (
            "compatible-mode/v1",
            "AI_AGENT_ROUTER_TIMEOUT",
            "AI_AGENT_ROUTER_MAX_OUTPUT_TOKENS",
            "AI_AGENT_ROUTER_MAX_RETRIES",
            "AI_AGENT_ROUTER_THINKING_ENABLED",
            "AI_AGENT_ROUTER_THINKING_BUDGET",
        )
        missing_configuration = [
            value for value in required_configuration if value not in application
        ]
        validator_test = self.root / (
            "ai-agent-station-infrastructure/src/test/java/cn/ethan/infrastructure/"
            "qwen/validator/ModelNameValidatorTest.java"
        )
        router_http_test = self.root / (
            "ai-agent-station-infrastructure/src/test/java/cn/ethan/infrastructure/"
            "qwen/provider/QwenRouteDecisionProviderTest.java"
        )
        validator = self._read(
            "ai-agent-station-infrastructure/src/main/java/cn/ethan/infrastructure/"
            "qwen/validator/ModelNameValidator.java"
        )
        provider = self._read(
            "ai-agent-station-infrastructure/src/main/java/cn/ethan/infrastructure/"
            "qwen/provider/QwenRouteDecisionProvider.java"
        )
        effective_router_validation = "spring.ai.openai.chat.model" in validator
        structured_router = all(
            marker in provider
            for marker in (
                "validateSchema",
                '"enable_thinking"',
                '"thinking_budget"',
            )
        )
        passed = not missing \
            and not missing_configuration \
            and ("qwen3.7-" + "max") not in application \
            and validator_test.exists() \
            and router_http_test.exists() \
            and effective_router_validation \
            and structured_router
        evidence = (
            f"missingModels={missing}, missingConfig={missing_configuration}, "
            f"validatorTest={validator_test.exists()}, "
            f"routerHttpTest={router_http_test.exists()}, "
            f"effectiveRouterValidation={effective_router_validation}, "
            f"structuredRouter={structured_router}"
        )
        return passed, evidence

    def _check_react_tools(self) -> tuple[bool, str]:
        executor = self._read(
            "ai-agent-station-infrastructure/src/main/java/cn/ethan/infrastructure/"
            "agentscope/executor/AgentScopeReActExecutor.java"
        )
        assembler = self._read(
            "ai-agent-station-infrastructure/src/main/java/cn/ethan/infrastructure/"
            "agentscope/assembler/AgentScopeEventAssembler.java"
        )
        assembler_test = self.root / (
            "ai-agent-station-infrastructure/src/test/java/cn/ethan/infrastructure/"
            "agentscope/assembler/AgentScopeEventAssemblerTest.java"
        )
        infrastructure_pom = self._read("ai-agent-station-infrastructure/pom.xml")
        required_executor = (
            "ReActAgent",
            "DashScopeChatModel",
            "InMemoryAgentStateStore",
            ".enableThinking(thinkingEnabled)",
            "百炼原生工具已禁用",
            ".maxIters(maxIterations)",
            ".maxRetries(maxRetries)",
            "PermissionMode.DEFAULT",
            "RequireUserConfirmEvent",
            "METADATA_CONFIRM_RESULTS",
            "currentAgent.interrupt(context)",
        )
        missing_executor = [
            marker for marker in required_executor if marker not in executor
        ]
        thinking_isolated = all(
            marker in assembler
            for marker in (
                "ThinkingBlockStartEvent",
                "ThinkingBlockDeltaEvent",
                "ThinkingBlockEndEvent",
                "return Optional.empty()",
            )
        )
        native_tools_disabled = ".enableSearch(" not in executor and "MCP 工具" in executor
        passed = not missing_executor \
            and thinking_isolated \
            and native_tools_disabled \
            and assembler_test.exists() \
            and "agentscope-core" in infrastructure_pom \
            and "agentscope-extensions-model-dashscope" in infrastructure_pom
        evidence = (
            f"missingExecutor={missing_executor}, "
            f"thinkingIsolated={thinking_isolated}, "
            f"nativeToolsDisabled={native_tools_disabled}, test={assembler_test.exists()}"
        )
        return passed, evidence

    def _check_api(self) -> tuple[bool, str]:
        controller = self._read(
            "ai-agent-station-app/src/main/java/cn/ethan/controller/AgentController.java"
        )
        endpoints = (
            '@PostMapping("/chat")', '"/chat/stream"',
            '"/workflow-runs/{runId}/answers"',
            '"/requests/{requestId}/interventions/{replyId}"',
            '@DeleteMapping("/requests/{requestId}")'
        )
        test = self.root / (
            "ai-agent-station-app/src/test/java/cn/ethan/controller/"
            "AgentControllerTest.java"
        )
        missing = [endpoint for endpoint in endpoints if endpoint not in controller]
        passed = not missing and test.exists()
        evidence = f"missingEndpoints={missing}, controllerTest={test.exists()}"
        return passed, evidence

    def _check_output_manager(self) -> tuple[bool, str]:
        source = self._read(
            "ai-agent-station-core/src/main/java/cn/ethan/core/agent/service/"
            "OutputManager.java"
        )
        required = ("redact", "duration", "token")
        missing = [value for value in required if value not in source.lower()]
        test = self.root / (
            "ai-agent-station-core/src/test/java/cn/ethan/core/agent/service/"
            "OutputManagerTest.java"
        )
        provider = self.root / (
            "ai-agent-station-infrastructure/src/main/java/cn/ethan/infrastructure/"
            "observability/provider/MicrometerOutputObservationProvider.java"
        )
        passed = not missing and test.exists() and provider.exists()
        evidence = (
            f"missing={missing}, test={test.exists()}, "
            f"micrometerProvider={provider.exists()}"
        )
        return passed, evidence

    def _check_database_objects(self) -> tuple[bool, str]:
        ddl = self._read("docs/dev-ops/mysql/sql/ai-agent-station.sql")
        objects = (
            "AI_AGENT_STATION", "DEMO_ORDER", "AI_SESSION", "AI_SESSION_EVENT",
            "WORKFLOW_RUN", "AGENT_MEMORY_SOURCE", "AGENT_MEMORY_ENTRY", "AGENT_MEMORY_EVIDENCE"
        )
        missing = [name for name in objects if name not in ddl]
        cleanup = self.root / (
            "docs/dev-ops/mysql/sql/manual-cleanup-old-after-sales.sql"
        )
        passed = not missing and cleanup.exists()
        evidence = f"missingObjects={missing}, cleanupScript={cleanup.exists()}"
        return passed, evidence

    def _check_test_suites(self) -> tuple[bool, str]:
        required_fragments = (
            "AgentRouterServiceTest.java",
            "AfterSalesRefundWorkflowTest.java",
            "SessionExecutionQueueManagerTest.java",
            "AgentScopeEventAssemblerTest.java",
            "QwenRouteDecisionProviderTest.java",
            "AgentControllerTest.java",
            "ApplicationTest.java",
        )
        names = {path.name for path in self.root.rglob("*Test.java")}
        missing = [name for name in required_fragments if name not in names]
        return not missing, "分层测试齐全" if not missing else f"缺少 {missing}"

    def _check_runbook(self) -> tuple[bool, str]:
        runbook = self.root / "docs/runbook.md"
        if not runbook.exists():
            return False, "缺少 docs/runbook.md"
        text = runbook.read_text(encoding="utf-8")
        required = (
            "DASHSCOPE_API_KEY",
            "/api/v1/agent/chat",
            "SESSION_QUEUE_FULL",
            "qwen3.7-plus",
            "scripts.live_acceptance",
            "mvn",
        )
        missing = [value for value in required if value not in text]
        env_example = self.root / "ai-agent-station-app/.env.example"
        passed = not missing and env_example.exists()
        evidence = f"missingSections={missing}, envExample={env_example.exists()}"
        return passed, evidence

    def _check_stream_admission(self) -> tuple[bool, str]:
        controller = self._read(
            "ai-agent-station-app/src/main/java/cn/ethan/controller/AgentController.java"
        )
        test = self._read(
            "ai-agent-station-app/src/test/java/cn/ethan/controller/"
            "AgentControllerTest.java"
        )
        required_source = (
            "runtimeService.submit(",
            "runtimeService.cancel(requestId, userId)",
        )
        required_tests = (
            "sseQueueFullReturnsHttp429BeforeStreamStarts",
            "cancelRequiresMatchingUser",
            "missingUserHeaderReturnsStableError",
        )
        missing = [value for value in required_source if value not in controller]
        missing_tests = [value for value in required_tests if value not in test]
        return not missing and not missing_tests, (
            f"missingSource={missing}, missingTests={missing_tests}"
        )

    def _check_runtime_properties(self) -> tuple[bool, str]:
        properties = self.root / (
            "ai-agent-station-app/src/main/java/cn/ethan/config/"
            "AgentRuntimeProperties.java"
        )
        application = self._read("ai-agent-station-app/src/main/resources/application.yml")
        required = (
            "AI_AGENT_REQUEST_TERMINAL_TTL",
            "AI_AGENT_STREAM_TIMEOUT",
            "AI_AGENT_EXECUTOR_MAX_POOL_SIZE",
            "AI_AGENT_QUEUE_MAX_PENDING_PER_SESSION",
            "AI_AGENT_QUEUE_MAX_PENDING_GLOBAL",
            "AI_AGENT_QUEUE_WAIT_TIMEOUT",
            "AI_AGENT_REACT_THINKING_ENABLED",
            "AI_AGENT_ROUTER_HISTORY_TURNS",
        )
        missing = [value for value in required if value not in application]
        agentscope_properties = self.root / (
            "ai-agent-station-app/src/main/java/cn/ethan/config/"
            "AgentScopeReActProperties.java"
        )
        return properties.exists() and agentscope_properties.exists() and not missing, (
            f"runtimeProperties={properties.exists()}, "
            f"agentScopeProperties={agentscope_properties.exists()}, "
            f"missingConfig={missing}"
        )

    def _check_http_order_gateway(self) -> tuple[bool, str]:
        gateway = self._read(
            "ai-agent-station-infrastructure/src/main/java/cn/ethan/infrastructure/"
            "order/gateway/HttpOrderGateway.java"
        )
        test = self._read(
            "ai-agent-station-infrastructure/src/test/java/cn/ethan/infrastructure/"
            "order/gateway/HttpOrderGatewayTest.java"
        )
        required_source = ("setReadTimeout", "response.userId()", "http-timeout")
        required_tests = (
            "rejectsResponseOwnedByAnotherUser",
            "timeoutBecomesTemporaryFailure",
        )
        missing = [value for value in required_source if value not in gateway]
        missing_tests = [value for value in required_tests if value not in test]
        return not missing and not missing_tests, (
            f"missingSource={missing}, missingTests={missing_tests}"
        )

    def _check_session_guard(self) -> tuple[bool, str]:
        store = self._read(
            "ai-agent-station-infrastructure/src/main/java/cn/ethan/infrastructure/"
            "memory/store/MybatisAgentMemoryStore.java"
        )
        service = self._read(
            "ai-agent-station-core/src/main/java/cn/ethan/core/agent/service/AgentMemoryService.java"
        )
        passed = all(marker in store for marker in (
            "getUserId", "getSessionId", "getUpdatedAt", "getCategory",
            "getMemoryKey", "getMemoryValue", "getDeleted",
        )) and all(marker in service for marker in (
            "generationEnabled", "usageEnabled", "findOwnedByKey", "containsSensitiveContent",
            "workflowSuggestion",
        ))
        return passed, "会话隔离、受控键、软删除与默认关闭记忆" if passed else "记忆会话边界缺失"

    def _check_build_guard(self) -> tuple[bool, str]:
        app_pom = self._read("ai-agent-station-app/pom.xml")
        checker = self._read("scripts/convention_check/checker.py")
        passed = "<filtering>true</filtering>" not in app_pom \
            and "SOURCE_SECRET_PATTERN" in checker \
            and "MAVEN_RESOURCE_FILTERING" in checker
        return passed, "资源不过滤且密钥静态扫描启用" if passed else "构建守卫缺失"

    def _check_input_output_guard(self) -> tuple[bool, str]:
        workflow_test = self._read(
            "ai-agent-station-core/src/test/java/cn/ethan/core/agent/service/"
            "AgentRuntimeServiceTest.java"
        )
        output_test = self._read(
            "ai-agent-station-core/src/test/java/cn/ethan/core/agent/service/"
            "OutputManagerTest.java"
        )
        result_test = self._read(
            "ai-agent-station-core/src/test/java/cn/ethan/core/agent/model/"
            "ReActResultModelTest.java"
        )
        agentscope_test = self._read(
            "ai-agent-station-infrastructure/src/test/java/cn/ethan/infrastructure/"
            "agentscope/assembler/AgentScopeEventAssemblerTest.java"
        )
        required = (
            "workflowQuestionIsPersistedAndAnsweredExplicitly" in workflow_test,
            "redactsQuotedAndUrlEncodedSecrets" in output_test,
            "dropsUnsafeAndExcessiveSources" in result_test,
            "exposesThinkingLifecycleButDiscardsThinkingContent" in agentscope_test,
        )
        live_runner = self.root / "scripts/live_acceptance/runner.py"
        passed = all(required) and live_runner.exists()
        return passed, f"guards={required}, liveRunner={live_runner.exists()}"

    def _check_documentation(self) -> tuple[bool, str]:
        """确认入口文档、交接记录和提示词边界能够互相定位。"""

        required_documents = (
            "docs/README.md",
            "docs/architecture.md",
            "docs/execution-plan.md",
            "docs/runbook.md",
            "docs/task-handoff.md",
        )
        missing_documents = [
            path for path in required_documents if not (self.root / path).exists()
        ]
        document_index = self._read("docs/README.md")
        architecture = self._read("docs/architecture.md")
        handoff = self._read("docs/task-handoff.md")
        index_complete = all(
            marker in document_index
            for marker in (
                "task-handoff.md",
                "architecture.md",
                "execution-plan.md",
                "runbook.md",
            )
        )
        handoff_complete = all(
            marker in handoff
            for marker in (
                "## 状态",
                "## 当前基线",
                "## 下一步唯一动作",
                "## 优先文件",
            )
        )
        prompt_boundary_documented = all(
            marker in architecture
            for marker in (
                "提示词与框架校验",
                "validateSchema",
                "Thinking",
            )
        )
        passed = not missing_documents and index_complete \
            and handoff_complete and prompt_boundary_documented
        evidence = (
            f"missingDocuments={missing_documents}, indexComplete={index_complete}, "
            f"handoffComplete={handoff_complete}, "
            f"promptBoundaryDocumented={prompt_boundary_documented}"
        )
        return passed, evidence

    def _check_questioncard_memory_v2(self) -> tuple[bool, str]:
        """阻止旧恢复协议回流，并检查 V2 记忆的关键边界。"""

        active_paths = (
            "README.md",
            "docs/README.md",
            "docs/architecture.md",
            "docs/execution-plan.md",
            "docs/runbook.md",
            "docs/task-handoff.md",
            "scripts/live_acceptance/runner.py",
            "ai-agent-station-app/src/main/java/cn/ethan/controller/AgentController.java",
            "ai-agent-station-app/src/main/java/cn/ethan/dto/AgentChatRequestDto.java",
            "ai-agent-station-app/src/main/java/cn/ethan/dto/AgentWorkflowAnswerRequestDto.java",
        )
        obsolete = ("pendingInputId", "NEEDS_INPUT", "/resume")
        stale = [
            path for path in active_paths
            if any(value in self._read(path) for value in obsolete)
        ]
        memory_service = self._read(
            "ai-agent-station-core/src/main/java/cn/ethan/core/agent/service/"
            "AgentMemoryService.java"
        )
        coordinator = self._read(
            "ai-agent-station-core/src/main/java/cn/ethan/core/agent/support/"
            "AgentMemoryExtractionCoordinator.java"
        )
        react = self._read(
            "ai-agent-station-infrastructure/src/main/java/cn/ethan/infrastructure/"
            "agentscope/executor/AgentScopeReActExecutor.java"
        )
        ddl = self._read("docs/dev-ops/mysql/sql/ai-agent-station.sql")
        migration = self._read(
            "docs/dev-ops/mysql/sql/manual-upgrade-session-memory-v2.sql"
        )
        required = (
            "shouldGenerate", "shouldUse", "PREFERENCE_KEYS", "TASK_CONTEXT_KEYS",
            "MAX_BATCH_TURNS", "不可信历史数据", "MEMORY_VALUE", "UQ_AGENT_MEMORY_ENTRY_OWNER_KEY",
        )
        combined = "\n".join((memory_service, coordinator, react, ddl, migration))
        missing = [marker for marker in required if marker not in combined]
        passed = not stale and not missing
        return passed, f"staleProtocol={stale}, missingV2={missing}"

    def _source_files(self):
        suffixes = {".java", ".xml", ".yml", ".yaml"}
        for module in (
            "ai-agent-station-core",
            "ai-agent-station-infrastructure",
            "ai-agent-station-app",
        ):
            for path in (self.root / module).rglob("*"):
                if path.is_file() and path.suffix.lower() in suffixes \
                        and "target" not in path.parts:
                    yield path

    def _read(self, relative_path: str) -> str:
        path = self.root / relative_path
        return path.read_text(encoding="utf-8") if path.exists() else ""


def audit_repository(root: Path) -> tuple[AuditResult, ...]:
    """审计指定仓库。"""

    return PlanAuditor(root).run()


def _repository_root(candidate: Path) -> Path:
    current = candidate.resolve()
    for path in (current, *current.parents):
        if (path / "pom.xml").exists() and (path / "AGENTS.md").exists():
            return path
    raise FileNotFoundError("未找到仓库根目录")


def main(arguments: Sequence[str] | None = None) -> int:
    """执行计划审计命令。"""

    parser = argparse.ArgumentParser(description="审计 AI Agent Station 最终执行计划")
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="仓库根目录")
    parser.add_argument("--strict", action="store_true", help="存在缺失项时返回非零状态")
    options = parser.parse_args(arguments)

    try:
        root = _repository_root(options.root)
    except FileNotFoundError as exception:
        print(str(exception))
        return 2

    results = audit_repository(root)
    for result in results:
        status = "PASS" if result.passed else "MISSING"
        print(f"[{status}] {result.requirement_id} {result.description}：{result.evidence}")

    failed = [result for result in results if not result.passed]
    print(f"计划审计：{len(results) - len(failed)}/{len(results)} 项通过。")
    return 1 if options.strict and failed else 0
