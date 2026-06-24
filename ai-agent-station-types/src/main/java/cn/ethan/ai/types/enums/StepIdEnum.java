package cn.ethan.ai.types.enums;

/**
 * Flow Plan step identifiers — shared across armory root node, step nodes, and lifecycle VO.
 */
public enum StepIdEnum {

    FLOW_ROOT("flow_root"),
    FLOW_TOOL_ROUTING("flow_tool_routing"),
    FLOW_PLAN_GENERATE("flow_plan_generate"),
    FLOW_PLAN_VALIDATE("flow_plan_validate"),
    FLOW_PLAN_EXECUTE("flow_plan_execute"),
    FLOW_SUPERVISION("flow_supervision"),
    FLOW_SUMMARY("flow_summary");

    private final String value;

    StepIdEnum(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

}
