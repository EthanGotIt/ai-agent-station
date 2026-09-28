package cn.ethan.core.agent.workflow;

import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.commerce.order.OrderSnapshotModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 类型职责：定义退款与删除各自的资格和授权内容，供确定性 Java Workflow 使用。
 *
 * @author ethan
 * @date 2026-09-28
 */
public interface OrderWriteJavaWorkflowPolicy {

    String intent();

    String label();

    String impact(OrderSnapshotModel order);

    ExternalActionTypeEnum actionType();

    AgentWorkflowOrchestrationVersionEnum version();

    boolean eligible(OrderSnapshotModel order, String userId);

    List<String> authorizationValues(OrderSnapshotModel order, String reason);

    Map<String, String> commandPayload(OrderSnapshotModel order, String reason);

    default boolean needsReason() {
        return false;
    }

    default String factsFingerprint(OrderSnapshotModel order, String reason) {
        StringBuilder canonical = new StringBuilder();
        for (String value : authorizationValues(order, reason)) {
            String safe = value == null ? "" : value;
            canonical.append(safe.length()).append(':').append(safe);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return intent().toLowerCase(java.util.Locale.ROOT) + "-java-v1:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
    }
}
