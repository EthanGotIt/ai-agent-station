package cn.ethan.app.agent.api;

import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 类型职责：验证开放 QuestionCard 快照在 HTTP 边界保留恢复所需的版本和步骤信息。
 *
 * @author ethan
 * @date 2026-08-23
 */
class AgentWorkflowQuestionSnapshotDtoTest {

    @Test
    void snapshotKeepsQuestionVersionAndStep() {
        AgentWorkflowQuestionModel question = new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "turn-1", "user-1", "question-1", "checkpoint-1",
                2, 3L, "补充原因", "请填写退款原因", "{\"fields\":[]}",
                AgentWorkflowQuestionStatusEnum.OPEN, Instant.parse("2026-08-23T00:00:00Z"),
                null, null, AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE, List.of());

        AgentWorkflowQuestionSnapshotDto snapshot = AgentWorkflowQuestionSnapshotDto.from(question);

        assertEquals(2, snapshot.stepNo());
        assertEquals(3L, snapshot.version());
        assertEquals("checkpoint-1", snapshot.checkpointId());
        assertEquals("{\"fields\":[]}", snapshot.fieldsJson());
    }
}
