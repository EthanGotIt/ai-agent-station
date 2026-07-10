package cn.ethan.ai.domain.agent.model;

/**
 * 售后规划器可选择的只读工具能力。
 */
public enum AfterSalesToolCapability {
    QUERY_ORDER("query_order"),
    QUERY_LOGISTICS("query_logistics"),
    QUERY_REFUND_HISTORY("query_refund_history");

    private final String toolName;

    AfterSalesToolCapability(String toolName) {
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }

    public static AfterSalesToolCapability fromToolName(String toolName) {
        if (toolName == null) {
            return null;
        }
        for (AfterSalesToolCapability capability : values()) {
            if (capability.toolName.equalsIgnoreCase(toolName.trim())) {
                return capability;
            }
        }
        return null;
    }
}
