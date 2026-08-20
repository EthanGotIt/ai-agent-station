package cn.ethan.infrastructure.agent.action.persistence;

import cn.ethan.core.agent.action.ExternalActionResultStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 类型职责：映射外部动作结果去重表。
 *
 * @author ethan
 * @date 2026-08-20
 */
@TableName(value = "EXTERNAL_ACTION_RESULT", autoResultMap = true)
public final class ExternalActionResultEntity {

    @TableId(value = "RESULT_ID", type = IdType.INPUT)
    private String resultId;
    @TableField("COMMAND_ID")
    private String commandId;
    @TableField("IDEMPOTENCY_KEY")
    private String idempotencyKey;
    @TableField("ACTION_TYPE")
    private ExternalActionTypeEnum actionType;
    @TableField("STATUS")
    private ExternalActionResultStatusEnum status;
    @TableField("RESPONSE_JSON")
    private String responseJson;
    @TableField("CREATED_AT")
    private Instant createdAt;

    public ExternalActionResultEntity() {
    }

    public String getResultId() { return resultId; }
    public void setResultId(String resultId) { this.resultId = resultId; }
    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public ExternalActionTypeEnum getActionType() { return actionType; }
    public void setActionType(ExternalActionTypeEnum actionType) { this.actionType = actionType; }
    public ExternalActionResultStatusEnum getStatus() { return status; }
    public void setStatus(ExternalActionResultStatusEnum status) { this.status = status; }
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
