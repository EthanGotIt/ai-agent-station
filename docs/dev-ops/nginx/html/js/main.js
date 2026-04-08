function generateSessionId() {
    return 'session_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
}

const DEFAULT_MAX_STEP = '3';

let sessionId = generateSessionId();
document.getElementById('sessionId').textContent = sessionId;

let isConnected = false;
let eventSource = null;
let currentStepMessages = new Map();
let hasReceivedComplete = false;
let activeStreamRoundKey = '';

let currentChatHistory = {
    sessionId: sessionId,
    title: '',
    timestamp: Date.now(),
    messages: [],
    agentId: null,
    maxStep: DEFAULT_MAX_STEP
};

const CHAT_HISTORY_KEY = 'ai_agent_chat_history';

function getChatHistory() {
    try {
        const history = localStorage.getItem(CHAT_HISTORY_KEY);
        return history ? JSON.parse(history) : [];
    } catch (e) {
        console.error('获取历史对话失败:', e);
        return [];
    }
}

function saveChatToHistory(chatData) {
    try {
        const history = getChatHistory();
        const existingIndex = history.findIndex(chat => chat.sessionId === chatData.sessionId);
        if (existingIndex >= 0) {
            history[existingIndex] = chatData;
        } else {
            history.unshift(chatData);
        }
        if (history.length > 50) {
            history.splice(50);
        }
        localStorage.setItem(CHAT_HISTORY_KEY, JSON.stringify(history));
        updateChatHistoryUI();
    } catch (e) {
        console.error('保存历史对话失败:', e);
    }
}

function deleteChatHistory(sessionId) {
    try {
        const history = getChatHistory();
        const filteredHistory = history.filter(chat => chat.sessionId !== sessionId);
        localStorage.setItem(CHAT_HISTORY_KEY, JSON.stringify(filteredHistory));
        updateChatHistoryUI();
    } catch (e) {
        console.error('删除历史对话失败:', e);
    }
}

function updateChatHistoryUI() {
    const chatList = document.getElementById('chatList');
    const history = getChatHistory();

    chatList.innerHTML = '';

    if (history.length === 0) {
        chatList.innerHTML = '<li class="text-gray-600 text-sm text-center py-4 font-mono">No Logs Found</li>';
        return;
    }

    history.forEach(chat => {
        const listItem = document.createElement('li');
        listItem.className = 'group';

        const date = new Date(chat.timestamp);
        const timeStr = date.toLocaleString('zh-CN', {
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit'
        });

        let title = chat.title;
        if (!title && chat.messages.length > 0) {
            const firstUserMessage = chat.messages.find(msg => msg.type === 'user');
            if (firstUserMessage) {
                title = firstUserMessage.content.substring(0, 20) + (firstUserMessage.content.length > 20 ? '...' : '');
            }
        }
        if (!title) title = '新对话';

        const safeTitle = escapeHtml(title);

        listItem.innerHTML = `
            <div class="chat-history-item flex items-center justify-between" 
                 onclick="loadChatHistory('${chat.sessionId}')">
                <div class="flex-1 min-w-0">
                    <div class="text-sm font-medium text-gray-700 truncate font-mono" title="${safeTitle}">
                        ${safeTitle}
                    </div>
                    <div class="text-xs text-gray-500 font-mono mt-1">
                        ${timeStr}
                    </div>
                </div>
                <button class="opacity-0 group-hover:opacity-100 ml-2 p-1 text-red-500 hover:text-red-600 hover:bg-red-50 rounded transition-all duration-200" 
                        onclick="event.stopPropagation(); deleteChatHistory('${chat.sessionId}')" 
                        title="删除对话">
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/>
                    </svg>
                </button>
            </div>
        `;

        chatList.appendChild(listItem);
    });
}

function buildStreamMessageKey(type, subType, step) {
    return [activeStreamRoundKey || 'default', type || 'unknown', subType || 'default', step || '0'].join('::');
}

function upsertAiMessageHistory(message) {
    if (!currentChatHistory || !currentChatHistory.sessionId) {
        return;
    }

    const existingIndex = currentChatHistory.messages.findIndex(item =>
        item.type === 'ai' && item.streamKey === message.streamKey
    );

    if (existingIndex >= 0) {
        currentChatHistory.messages[existingIndex] = {
            ...currentChatHistory.messages[existingIndex],
            ...message
        };
    } else {
        currentChatHistory.messages.push(message);
    }

    saveChatToHistory(currentChatHistory);
}

function loadChatHistory(targetSessionId) {
    const history = getChatHistory();
    const chat = history.find(c => c.sessionId === targetSessionId);

    if (!chat) {
        alert('对话记录不存在');
        return;
    }

    const thinkingDiv = document.getElementById('thinkingMessages');
    const resultDiv = document.getElementById('resultMessages');

    const thinkingChildren = Array.from(thinkingDiv.children);
    thinkingChildren.slice(1).forEach(child => child.remove());

    const resultChildren = Array.from(resultDiv.children);
    resultChildren.slice(1).forEach(child => child.remove());

    chat.messages.forEach(message => {
        if (message.type === 'user') {
            addMessage(message.content, 'user');
        } else if (message.type === 'ai') {
            appendAiMessageFromHistory(message);
        }
    });

    currentStepMessages.clear();
    activeStreamRoundKey = '';
    currentChatHistory = { ...chat };
    sessionId = chat.sessionId;
    document.getElementById('sessionId').textContent = chat.sessionId;

    if (chat.agentId) {
        selectedAgentId = chat.agentId;
        renderAgentCards();
        updateDropdownCases(chat.agentId);
    } else {
        selectedAgentId = null;
        renderAgentCards();
        updateDropdownCases(null);
    }

    if (chat.maxStep) {
        selectedMaxStep = chat.maxStep;
        document.querySelectorAll('.step-button').forEach(button => {
            button.classList.remove('selected');
            if (button.getAttribute('data-step') === chat.maxStep) {
                button.classList.add('selected');
            }
        });
    }

    scrollToBottom();
}

let selectedAgentId = null;
let selectedMaxStep = DEFAULT_MAX_STEP;

const AGENT_CONFIGS = {
    '1': {
        id: '1',
        name: '流程规划执行体',
        description: '自动自主规划与工具编排',
        capability: 'Flow 模式',
        highlight: '适合流程明确、需要串联工具的执行任务',
        color: '#0ea5e9',
        iconPath: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
        cases: [
            {
                title: '搜索 Java 技术栈笔记并发送通知',
                content: `请帮我完成以下任务：

1. 使用小红书 MCP 工具 rednote 搜索关键词“Java技术栈”的笔记内容。
2. 整理搜索结果，提炼出值得学习的技术方向与高频话题。
3. 使用 notify 工具发送一条“任务完成”通知，通知中包含整理后的摘要结果。

请按照以上步骤依次执行，并返回清晰的执行结果。`
            },
            {
                title: '检索 Spring AI 资料并整理摘要',
                content: `请使用 rednote 搜索“Spring AI 实战”相关笔记，整理出 5 条最有价值的信息，并在整理完成后通过 notify 发送摘要通知。`
            }
        ]
    },
    '3': {
        id: '3',
        name: '调研分析执行体',
        description: '文本调研自动分析和执行任务',
        capability: 'Auto 模式',
        highlight: '适合开放问题分析、资料检索和方案整理',
        color: '#8b5cf6',
        iconPath: 'M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z',
        cases: [
            {
                title: '检索小傅哥项目并输出学习计划',
                content: '检索小傅哥的相关项目，列出一份学习计划。'
            },
            {
                title: '分析北京互联网公司入职建议',
                content: '根据当前北京互联网程序员加班情况、收入和公司文化，列出一份大学生推荐入职单位。'
            }
        ]
    },
    '6': {
        id: '6',
        name: '固定执行体',
        description: '固定任务执行与结果回传',
        capability: 'Fixed 模式',
        highlight: '适合固定模板任务和结果回传场景',
        color: '#10b981',
        iconPath: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z',
        cases: [
            {
                title: '搜索热门 Java 笔记并通知',
                content: '请搜索“小红书 Java 面试”相关热门笔记，整理成简要清单，并发送一条完成通知。'
            },
            {
                title: '搜索 Agent 相关文章并回传摘要',
                content: '请搜索“AI Agent 工作流”相关内容，输出摘要后发送通知告知任务完成。'
            }
        ]
    }
};

function escapeHtml(value) {
    return String(value || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function renderMarkdown(content) {
    return DOMPurify.sanitize(marked.parse(content || ''));
}

function setCaseDropdownLabel(text) {
    const label = document.querySelector('#caseDropdownButton .case-dropdown-label');
    if (label) {
        label.textContent = text;
        label.title = text;
    }
}

function renderAgentCards() {
    const container = document.getElementById('agentCardContainer');
    container.innerHTML = Object.values(AGENT_CONFIGS).map(agent => `
        <div class="agent-card flex-1 min-w-[200px] py-3 px-4 ${selectedAgentId === agent.id ? ' selected' : ''}" data-agent-id="${agent.id}">
            <div class="flex items-center gap-3 w-full">
                <div class="agent-icon w-10 h-10 flex-shrink-0" style="color: ${agent.color};">
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="${agent.iconPath}" />
                    </svg>
                </div>
                <div class="agent-copy flex-1 min-w-0">
                    <h5 class="agent-title truncate">${escapeHtml(agent.name)}</h5>
                    <p class="agent-description truncate">${escapeHtml(agent.description)}</p>
                </div>
            </div>
            <div class="agent-tags mt-3">
                <span class="agent-tag" style="border-color: ${agent.color}40; color: ${agent.color};">${escapeHtml(agent.capability)}</span>
            </div>
        </div>
    `).join('');

    updateAgentOverview();
}

function updateAgentOverview() {
    const overview = document.getElementById('agentOverview');
    if (!overview) {
        return;
    }

    const agent = AGENT_CONFIGS[selectedAgentId];
    if (!agent) {
        overview.innerHTML = `
            <div class="agent-overview-label">当前未选择智能体</div>
            <div class="agent-overview-title">请选择一个执行模式后再发起任务</div>
            <div class="agent-overview-description">建议先选择智能体，再选一个案例填充输入框，最后根据任务复杂度调整最大执行步数。</div>
        `;
        return;
    }

    overview.innerHTML = `
        <div class="agent-overview-label">当前智能体</div>
        <div class="agent-overview-title">${escapeHtml(agent.name)} · ${escapeHtml(agent.capability)}</div>
        <div class="agent-overview-description">${escapeHtml(agent.highlight)}，当前内置 ${agent.cases.length} 个示例案例，可直接填充到输入框进行体验。</div>
    `;
}

function syncCurrentChatMeta() {
    if (!currentChatHistory) {
        return;
    }

    currentChatHistory.agentId = selectedAgentId;
    currentChatHistory.maxStep = selectedMaxStep;

    if (currentChatHistory.sessionId) {
        saveChatToHistory(currentChatHistory);
    }
}

function buildAiMessageHTML(type, subType, content, step) {
    const stageInfo = stageTypeMap[type] || { name: type, icon: '📝', class: 'stage-analysis' };
    const subTypeName = subType ? subTypeMap[subType] || subType : '';

    let indicators = `<span class="stage-indicator ${stageInfo.class}">${stageInfo.icon} ${escapeHtml(stageInfo.name)}</span>`;
    if (subTypeName) {
        indicators += `<span class="sub-type-indicator">${escapeHtml(subTypeName)}</span>`;
    }
    if (step) {
        indicators += `<span class="sub-type-indicator">第 ${escapeHtml(step)} 步</span>`;
    }

    const roleTag = (type === 'summary' || type === 'complete' || type === 'completed') ? 'OUT' : 'SYS';
    const colorClass = (type === 'summary' || type === 'complete' || type === 'completed') ? 'text-primary-600 border-primary-200 bg-primary-50' : 'text-accent-600 border-accent-200 bg-accent-50';

    return `
        <div class="w-8 h-8 rounded-full border ${colorClass} flex items-center justify-center flex-shrink-0 font-mono text-xs font-bold shadow-sm">
            ${roleTag}
        </div>
        <div class="flex-1 bg-white border border-gray-100 p-4 rounded-xl shadow-sm text-sm text-gray-700">
            <div class="mb-3 pb-3 border-b border-gray-100 flex flex-wrap gap-1">
                ${indicators}
            </div>
            <div class="markdown-content font-sans">${renderMarkdown(content)}</div>
        </div>
    `;
}

function buildUserMessageHTML(content) {
    return `
        <div class="w-8 h-8 rounded-full border border-gray-200 bg-white flex items-center justify-center flex-shrink-0 text-gray-600 font-mono text-xs font-bold shadow-sm">
            USR
        </div>
        <div class="flex-1 bg-gray-50 border border-gray-200 p-4 rounded-xl shadow-sm text-sm text-gray-800">
            <div class="font-mono text-xs text-gray-500 mb-2 font-semibold">输入指令</div>
            <div class="font-sans whitespace-pre-wrap">${escapeHtml(content)}</div>
        </div>
    `;
}

function appendAiMessageFromHistory(message) {
    const targetContainer = message.stage === 'summary' || message.stage === 'complete' || message.stage === 'completed'
        ? document.getElementById('resultMessages')
        : document.getElementById('thinkingMessages');

    const messageDiv = document.createElement('div');
    messageDiv.className = 'flex items-start gap-3 message';
    if (message.content) {
        messageDiv.innerHTML = buildAiMessageHTML(message.stage, message.subType, message.content, message.step);
    } else {
        messageDiv.innerHTML = DOMPurify.sanitize(message.html || '');
    }
    targetContainer.appendChild(messageDiv);

    messageDiv.querySelectorAll('pre code').forEach((block) => {
        hljs.highlightElement(block);
    });
}

marked.setOptions({
    highlight: function(code, lang) {
        if (lang && hljs.getLanguage(lang)) {
            try {
                return hljs.highlight(code, { language: lang }).value;
            } catch (err) {}
        }
        return hljs.highlightAuto(code).value;
    },
    breaks: true,
    gfm: true
});

const stageTypeMap = {
    analysis: { name: '分析阶段', icon: '🎯', class: 'stage-analysis' },
    execution: { name: '执行阶段', icon: '⚡', class: 'stage-execution' },
    supervision: { name: '监督阶段', icon: '🔍', class: 'stage-supervision' },
    summary: { name: '总结阶段', icon: '📊', class: 'stage-summary' },
    error: { name: '错误信息', icon: '❌', class: 'stage-error' },
    complete: { name: '完成', icon: '✅', class: 'stage-summary' },
    completed: { name: '完成', icon: '✅', class: 'stage-summary' }
};

const subTypeMap = {
    analysis_status: '任务状态',
    analysis_history: '历史评估',
    analysis_strategy: '执行策略',
    analysis_progress: '完成度',
    analysis_task_status: '任务状态',
    execution_target: '执行目标',
    execution_process: '执行过程',
    execution_result: '执行结果',
    execution_quality: '质量检查',
    assessment: '质量评估',
    issues: '问题识别',
    suggestions: '改进建议',
    score: '质量评分',
    pass: '检查结果',
    completed_work: '已完成工作',
    incomplete_reasons: '未完成原因',
    evaluation: '效果评估',
    summary_overview: '总结概览'
};

function sendMessage() {
    const messageInput = document.getElementById('messageInput');
    const message = messageInput.value.trim();

    if (!message) {
        alert('请输入消息内容');
        return;
    }

    if (!selectedAgentId) {
        alert('请先选择一个智能体');
        return;
    }

    if (isConnected) {
        alert('正在处理中，请稍候...');
        return;
    }

    const chatTitle = message.substring(0, 20) + (message.length > 20 ? '...' : '');

    if (currentChatHistory.messages.length === 0) {
        sessionId = generateSessionId();
        document.getElementById('sessionId').textContent = sessionId;

        currentChatHistory = {
            sessionId: sessionId,
            title: chatTitle,
            timestamp: Date.now(),
            messages: [],
            agentId: selectedAgentId,
            maxStep: selectedMaxStep
        };

        saveChatToHistory(currentChatHistory);
    }

    addMessage(message, 'user');

    currentChatHistory.messages.push({
        type: 'user',
        content: message,
        timestamp: Date.now()
    });

    if (currentChatHistory.messages.length === 1) {
        currentChatHistory.title = chatTitle;
    }

    saveChatToHistory(currentChatHistory);

    messageInput.value = '';

    const sendBtn = document.getElementById('sendBtn');
    sendBtn.disabled = true;

    document.getElementById('loading').style.display = 'block';
    hasReceivedComplete = false;
    activeStreamRoundKey = `${sessionId}-${Date.now()}`;

    const requestData = {
        aiAgentId: selectedAgentId,
        message: message,
        sessionId: sessionId,
        maxStep: parseInt(selectedMaxStep)
    };

    fetch('http://localhost:8090/api/v1/agent/auto_agent', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/x-ndjson'
        },
        body: JSON.stringify(requestData)
    })
    .then(response => {
        if (!response.ok) {
            throw new Error('网络请求失败: ' + response.status);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();

        currentStepMessages.clear();

        isConnected = true;

        let streamBuffer = '';

        function handleStreamLine(line) {
            const normalizedLine = line.trim();
            if (!normalizedLine) {
                return;
            }

            const data = normalizedLine.startsWith('data: ')
                ? normalizedLine.substring(6).trim()
                : normalizedLine;
            if (!data || data === '[DONE]') {
                return;
            }

            try {
                const jsonData = JSON.parse(data);
                handleStreamMessage(jsonData);
            } catch (e) {
                console.warn('无法解析JSON数据:', data);
                addStageMessage('error', null, data, null);
            }
            scrollToBottom();
        }

        function readStream() {
            reader.read().then(({ done, value }) => {
                if (done) {
                    if (streamBuffer.trim()) {
                        handleStreamLine(streamBuffer.trim());
                    }
                    isConnected = false;
                    sendBtn.disabled = false;
                    document.getElementById('loading').style.display = 'none';
                    if (!hasReceivedComplete) {
                        addStageMessage('complete', null, '任务执行完成', null);
                    }
                    return;
                }

                const chunk = decoder.decode(value, { stream: true });
                streamBuffer += chunk;

                const lines = streamBuffer.split(/\r?\n/);
                streamBuffer = lines.pop() || '';
                lines.forEach(handleStreamLine);
                readStream();
            }).catch(error => {
                console.error('读取流数据错误:', error);
                isConnected = false;
                sendBtn.disabled = false;
                document.getElementById('loading').style.display = 'none';
                addStageMessage('error', null, '连接中断，请重试', null);
            });
        }
        readStream();
    })
    .catch(error => {
        console.error('请求错误:', error);
        isConnected = false;
        sendBtn.disabled = false;
        document.getElementById('loading').style.display = 'none';
        addStageMessage('error', null, '请求失败: ' + error.message, null);
    });
}

function handleStreamMessage(jsonData) {
    const { type, subType, step, content, completed } = jsonData;

    if (type === 'complete' || type === 'completed' || completed === true) {
        hasReceivedComplete = true;
    }

    if (!content || content.trim() === '') {
        return;
    }

    addStageMessage(type, subType, content, step);
}

function addStageMessage(type, subType, content, step) {
    const streamKey = buildStreamMessageKey(type, subType, step);
    let targetContainer;
    if (type === 'summary' || type === 'complete' || type === 'completed') {
        targetContainer = document.getElementById('resultMessages');
    } else {
        targetContainer = document.getElementById('thinkingMessages');
    }

    const messageHTML = buildAiMessageHTML(type, subType, content, step);
    let messageDiv = currentStepMessages.get(streamKey);

    if (!messageDiv) {
        messageDiv = document.createElement('div');
        messageDiv.className = 'flex items-start gap-3 message';
        messageDiv.dataset.streamKey = streamKey;
        targetContainer.appendChild(messageDiv);
        currentStepMessages.set(streamKey, messageDiv);
    }

    messageDiv.innerHTML = messageHTML;

    upsertAiMessageHistory({
        type: 'ai',
        content: content,
        stage: type,
        subType: subType,
        step: step,
        timestamp: Date.now(),
        html: messageHTML,
        streamKey: streamKey
    });

    messageDiv.querySelectorAll('pre code').forEach((block) => {
        hljs.highlightElement(block);
    });

    scrollToBottom(targetContainer);
    return messageDiv;
}

function addMessage(content, type) {
    if (type === 'user') {
        const thinkingDiv = document.getElementById('thinkingMessages');
        const resultDiv = document.getElementById('resultMessages');

        [thinkingDiv, resultDiv].forEach(container => {
            const messageDiv = document.createElement('div');
            messageDiv.className = 'flex items-start gap-3 message';
            messageDiv.innerHTML = buildUserMessageHTML(content);
            container.appendChild(messageDiv);
            scrollToBottom(container);
        });
    }
}

function scrollToBottom(container) {
    if (!container) {
        const thinkingDiv = document.getElementById('thinkingMessages');
        const resultDiv = document.getElementById('resultMessages');
        thinkingDiv.scrollTop = thinkingDiv.scrollHeight;
        resultDiv.scrollTop = resultDiv.scrollHeight;
    } else {
        container.scrollTop = container.scrollHeight;
    }
}

function createNewChat() {
    const thinkingDiv = document.getElementById('thinkingMessages');
    const resultDiv = document.getElementById('resultMessages');

    const thinkingChildren = Array.from(thinkingDiv.children);
    thinkingChildren.slice(1).forEach(child => child.remove());

    const resultChildren = Array.from(resultDiv.children);
    resultChildren.slice(1).forEach(child => child.remove());

    sessionId = '';
    currentStepMessages.clear();
    activeStreamRoundKey = '';
    document.getElementById('sessionId').textContent = 'WAITING';

    currentChatHistory = {
        sessionId: '',
        title: '',
        timestamp: 0,
        messages: [],
        agentId: selectedAgentId,
        maxStep: selectedMaxStep
    };

    document.getElementById('messageInput').value = '';
    document.getElementById('messageInput').focus();
    updateChatHistoryUI();
}

function clearAllChats() {
    if (confirm('确定要清空所有对话记录吗？')) {
        localStorage.removeItem(CHAT_HISTORY_KEY);
        createNewChat();
        updateChatHistoryUI();
    }
}

document.getElementById('messageInput').addEventListener('keypress', function(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
    }
});

document.getElementById('agentCardContainer').addEventListener('click', function(event) {
    const card = event.target.closest('.agent-card');
    if (!card) {
        return;
    }

    selectedAgentId = card.getAttribute('data-agent-id');
    renderAgentCards();
    updateDropdownCases(selectedAgentId);
    syncCurrentChatMeta();
});

function updateDropdownCases(agentId) {
    const dropdownContainer = document.getElementById('dropdownCaseContainer');
    const defaultMessage = document.getElementById('dropdown-default-message');

    dropdownContainer.innerHTML = '';
    dropdownContainer.appendChild(defaultMessage);
    setCaseDropdownLabel('选择案例');

    const agentConfig = AGENT_CONFIGS[agentId];
    if (!agentConfig || !agentConfig.cases.length) {
        defaultMessage.textContent = agentId ? '当前智能体暂无案例' : '请先选择一个智能体类型';
        defaultMessage.style.display = 'block';
        return;
    }

    defaultMessage.style.display = 'none';
    defaultMessage.textContent = '请先选择一个智能体类型';

    agentConfig.cases.forEach(caseInfo => {
        const caseItem = document.createElement('div');
        caseItem.className = 'dropdown-case-item font-sans text-sm text-gray-700 mb-1';
        caseItem.setAttribute('data-case', caseInfo.content);
        caseItem.setAttribute('data-title', caseInfo.title);
        caseItem.innerHTML = `
            <div class="flex items-center gap-2 truncate">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-primary-500 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                <span class="truncate">${escapeHtml(caseInfo.title)}</span>
            </div>
        `;
        dropdownContainer.appendChild(caseItem);
    });

    setupCaseCardEvents();
}

document.querySelectorAll('.step-button').forEach(button => {
    button.addEventListener('click', function() {
        document.querySelectorAll('.step-button').forEach(b => b.classList.remove('selected'));
        this.classList.add('selected');
        selectedMaxStep = this.getAttribute('data-step');
        syncCurrentChatMeta();
    });
});

function setupCaseCardEvents() {
    document.querySelectorAll('.dropdown-case-item').forEach(item => {
        item.addEventListener('click', function() {
            const caseContent = this.getAttribute('data-case');
            const caseTitle = this.getAttribute('data-title') || '提问案例';
            document.getElementById('messageInput').value = caseContent;
            setCaseDropdownLabel(caseTitle);
            document.querySelectorAll('.dropdown-case-item').forEach(c => c.classList.remove('selected'));
            this.classList.add('selected');
            document.getElementById('caseDropdown').classList.add('hidden');
            document.getElementById('messageInput').focus();
        });
    });
}

document.getElementById('caseDropdownButton').addEventListener('click', function() {
    const dropdown = document.getElementById('caseDropdown');
    dropdown.classList.toggle('hidden');
});

document.addEventListener('click', function(event) {
    const dropdown = document.getElementById('caseDropdown');
    const dropdownButton = document.getElementById('caseDropdownButton');

    if (!dropdown.contains(event.target) && !dropdownButton.contains(event.target)) {
        dropdown.classList.add('hidden');
    }
});

document.getElementById('newChatBtn').addEventListener('click', createNewChat);
document.getElementById('clearAllChatsBtn').addEventListener('click', clearAllChats);

window.addEventListener('load', function() {
    renderAgentCards();
    currentChatHistory = {
        sessionId: '',
        title: '',
        timestamp: 0,
        messages: [],
        agentId: selectedAgentId,
        maxStep: selectedMaxStep
    };

    updateChatHistoryUI();
    document.getElementById('messageInput').focus();

    if (selectedAgentId) {
        updateDropdownCases(selectedAgentId);
    }
});
