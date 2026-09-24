package cn.ethan.core.agent.workflow;

import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 类型职责：为 Java 催发货版本计算最小授权指纹并核验执行资格。
 *
 * @author ethan
 * @date 2026-09-24
 */
public final class ExpediteJavaWorkflowPolicy {

    private static final String FINGERPRINT_PREFIX = "expedite-java-v1:";

    public String factsFingerprint(OrderSnapshotModel order, String reason) {
        List<String> values = List.of("EXPEDITE", order.userId(), order.orderId(),
                order.status().name(), reason == null ? "" : reason.strip());
        StringBuilder canonical = new StringBuilder();
        values.forEach(value -> canonical.append(value.length()).append(':').append(value));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return FINGERPRINT_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
    }

    public boolean eligible(OrderSnapshotModel order, String expectedUserId) {
        return order != null && expectedUserId != null && expectedUserId.equals(order.userId())
                && order.status() == OrderStatusEnum.PAID;
    }
}
