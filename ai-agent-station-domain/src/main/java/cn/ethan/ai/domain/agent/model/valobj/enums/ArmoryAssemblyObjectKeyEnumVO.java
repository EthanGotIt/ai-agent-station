package cn.ethan.ai.domain.agent.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum ArmoryAssemblyObjectKeyEnumVO {

    AI_CLIENT_API_OBJECT_MAP_KEY("ai_client_api_object_map", "AI Client API对象缓存"),
    AI_CLIENT_MODEL_OBJECT_MAP_KEY("ai_client_model_object_map", "AI Client Model对象缓存"),
    AI_CLIENT_CHAT_CLIENT_OBJECT_MAP_KEY("ai_client_chat_client_object_map", "AI Client ChatClient对象缓存"),
    ;

    private String code;
    private String info;

}
