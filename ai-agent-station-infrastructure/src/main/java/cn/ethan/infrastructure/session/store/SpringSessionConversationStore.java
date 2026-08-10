package cn.ethan.infrastructure.session.store;

import cn.ethan.core.agent.enums.ConversationRoleEnum;
import cn.ethan.core.agent.model.ConversationMessageModel;
import cn.ethan.core.agent.port.ConversationStore;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Session 会话存储：仅复用已脱敏的最终对话内容，和 Workflow 运行状态完全隔离。
 *
 * @author ethan
 * @date 2026-08-07
 */
@Component
public final class SpringSessionConversationStore implements ConversationStore {

    private final SessionService sessions;

    public SpringSessionConversationStore(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public List<ConversationMessageModel> recent(
            String userId,
            String sessionId,
            int maxMessages,
            int maxCharacters
    ) {
        Session session = sessions.findById(sessionId);
        if (session == null || !userId.equals(session.userId()) || maxMessages < 1 || maxCharacters < 1) {
            return List.of();
        }
        List<ConversationMessageModel> messages = new ArrayList<>();
        int characters = 0;
        List<Message> sessionMessages = sessions.getMessages(sessionId);
        for (int index = sessionMessages.size() - 1; index >= 0; index--) {
            Message message = sessionMessages.get(index);
            ConversationMessageModel converted = toModel(message);
            if (converted == null || characters + converted.content().length() > maxCharacters) {
                continue;
            }
            messages.add(converted);
            characters += converted.content().length();
            if (messages.size() >= maxMessages) {
                break;
            }
        }
        List<ConversationMessageModel> ordered = new ArrayList<>(messages.size());
        for (int index = messages.size() - 1; index >= 0; index--) {
            ordered.add(messages.get(index));
        }
        return List.copyOf(ordered);
    }

    @Override
    public void append(String userId, String sessionId, ConversationMessageModel message) {
        Session session = sessions.findById(sessionId);
        if (session == null) {
            sessions.create(CreateSessionRequest.builder().id(sessionId).userId(userId).build());
        } else if (!userId.equals(session.userId())) {
            throw new IllegalArgumentException("session is owned by another user");
        }
        if (message.role() == ConversationRoleEnum.USER) {
            sessions.appendMessage(sessionId, new UserMessage(message.content()));
        } else {
            sessions.appendMessage(sessionId, new AssistantMessage(message.content()));
        }
    }

    private ConversationMessageModel toModel(Message message) {
        if (message.getText() == null || message.getText().isBlank()) {
            return null;
        }
        if (message.getMessageType() == MessageType.USER) {
            return new ConversationMessageModel(ConversationRoleEnum.USER, message.getText());
        }
        if (message.getMessageType() == MessageType.ASSISTANT) {
            return new ConversationMessageModel(ConversationRoleEnum.ASSISTANT, message.getText());
        }
        return null;
    }
}
