function generateSessionId() {
    return 'session_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
}

const DEFAULT_MAX_STEP = '3';
const EXECUTE_API_URL = (window.AI_AGENT_API_BASE_URL || 'http://localhost:8090') + '/api/v1/agent/execute';

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
        chatList.innerHTML = '<li class="text-gray-600 text-sm text-center py-4 font-mono">暂无历史记录</li>';
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

    selectedAgentId = chat.agentId || '1';
    renderAgentCards();
    updateDropdownCases(selectedAgentId);

    if (chat.maxStep) {
        selectedMaxStep = String(chat.maxStep);
        syncOptionButtons('.step-button', 'data-step', selectedMaxStep);
    }

    updateAgentOverview();
    scrollToBottom();
}

let selectedAgentId = '1';
let selectedMaxStep = DEFAULT_MAX_STEP;

const AGENT_CONFIGS = {
    '1': {
        id: '1',
        name: 'Flow Plan 编排体',
        description: '计划校验、工具调用、RAG 召回、质量监督',
        capability: 'Flow Plan',
        tags: ['Plan DSL', 'MCP 工具', 'RAG 多路召回', '流式追踪'],
        metrics: [
            { label: '执行内核', value: '单 Flow' },
            { label: '检索链路', value: 'Query Rewrite + 去重' },
            { label: '接口', value: 'NDJSON' }
        ],
        highlight: '将大模型生成的计划收敛为后端可校验、可执行、可监督的任务链路，并在知识问答场景中支持 Query Rewrite、多路召回和证据去重',
        color: '#58d0b7',
        iconPath: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
        cases: [
            {
                title: '检索 Spring AI 资料并形成落地建议',
                content: `请围绕“Spring AI MCP Client 和 RAG Advisor 的使用方式”完成资料调研：

1. 使用可用 MCP 工具检索 Spring AI、MCP、RAG 相关资料。
2. 提取关键实现步骤、注意事项和适用场景。
3. 按“结论、证据、落地建议”结构输出。
4. 如果可以发送通知，请在完成后发送任务完成提醒。

请按照以上步骤依次执行，并返回清晰的执行结果。`
            },
            {
                title: '梳理 RAG 多路召回升级方案并整理项目亮点',
                content: `请分析 AI Agent Station 的 RAG 升级思路，重点说明 Query Rewrite、多路召回、去重合并和证据上下文注入如何提升回答质量，并整理成可以写进简历的 3 条项目亮点。`
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

function syncOptionButtons(selector, dataName, selectedValue) {
    document.querySelectorAll(selector).forEach(button => {
        button.classList.toggle('selected', button.getAttribute(dataName) === String(selectedValue));
    });
}

function focusPromptWhenRoomy() {
    if (window.matchMedia('(min-width: 861px)').matches) {
        document.getElementById('messageInput').focus({ preventScroll: true });
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
                    <p class="agent-description">${escapeHtml(agent.description)}</p>
                </div>
            </div>
            <div class="agent-tags mt-3">
                ${(agent.tags || [agent.capability]).map(tag => `<span class="agent-tag">${escapeHtml(tag)}</span>`).join('')}
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
            <div class="agent-overview-title">请选择智能编排体后再发起任务</div>
            <div class="agent-overview-description">前端会按后端 Flow Plan 事件流展示工具摘要、结构化计划、执行过程、监督结果和最终总结。</div>
        `;
        return;
    }

    overview.innerHTML = `
        <div class="agent-overview-label">当前智能体</div>
        <div class="agent-overview-title">${escapeHtml(agent.name)} · ${escapeHtml(agent.capability)}</div>
        <div class="agent-overview-description">${escapeHtml(agent.highlight)}。当前内置 ${agent.cases.length} 个示例，计划步数上限 maxStep=${escapeHtml(selectedMaxStep)}。</div>
        <div class="agent-metrics">
            ${(agent.metrics || []).map(metric => `
                <div class="agent-metric">
                    <span>${escapeHtml(metric.label)}</span>
                    <strong>${escapeHtml(metric.value)}</strong>
                </div>
            `).join('')}
        </div>
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

function renderPlanContent(content) {
    try {
        const plan = JSON.parse(content);
        const steps = Array.isArray(plan.steps) ? plan.steps : [];
        return `
            <div class="plan-view">
                <div class="plan-goal">
                    <span class="plan-label">目标</span>
                    <span>${escapeHtml(plan.goal || '未提供目标')}</span>
                </div>
                <div class="plan-step-list">
                    ${steps.map((item, index) => `
                        <div class="plan-step-row">
                            <div class="plan-step-index">${index + 1}</div>
                            <div class="plan-step-body">
                                <div class="plan-step-title">${escapeHtml(item.name || item.stepId || '未命名步骤')}</div>
                                <div class="plan-step-meta">
                                    <span>${escapeHtml(item.type || 'LLM')}</span>
                                    ${item.toolName ? `<span>工具：${escapeHtml(item.toolName)}</span>` : ''}
                                    ${item.dependsOn && item.dependsOn.length ? `<span>依赖：${escapeHtml(item.dependsOn.join(', '))}</span>` : ''}
                                </div>
                                ${item.successCriteria ? `<div class="plan-step-criteria">${escapeHtml(item.successCriteria)}</div>` : ''}
                            </div>
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    } catch (e) {
        return renderMarkdown(content);
    }
}

function renderToolSummaryContent(content) {
    const prefix = '可用工具白名单：';
    if (!content || !content.startsWith(prefix)) {
        return renderMarkdown(content);
    }

    const tools = content.substring(prefix.length)
        .split(',')
        .map(item => item.trim())
        .filter(Boolean);
    return `
        <div class="tool-summary">
            <div class="tool-summary-title">可用工具白名单</div>
            <div class="tool-chip-list">
                ${tools.map(tool => `<span class="tool-chip">${escapeHtml(tool)}</span>`).join('')}
            </div>
        </div>
    `;
}

function renderStreamContent(type, subType, content) {
    if (subType === 'analysis_plan') {
        return renderPlanContent(content);
    }
    if (subType === 'analysis_tools') {
        return renderToolSummaryContent(content);
    }
    return renderMarkdown(content);
}

function buildAiMessageHTML(type, subType, content, step, runId) {
    const stageInfo = stageTypeMap[type] || { name: type, icon: 'MSG', class: 'stage-analysis' };
    const subTypeName = subType ? subTypeMap[subType] || subType : '';
    const shortRunId = runId ? String(runId).substring(0, 8) : '';

    let indicators = `<span class="stage-indicator ${stageInfo.class}">${stageInfo.icon} ${escapeHtml(stageInfo.name)}</span>`;
    if (subTypeName) {
        indicators += `<span class="sub-type-indicator">${escapeHtml(subTypeName)}</span>`;
    }
    if (step) {
        indicators += `<span class="sub-type-indicator">第 ${escapeHtml(step)} 步</span>`;
    }
    if (shortRunId) {
        indicators += `<span class="sub-type-indicator">运行 ${escapeHtml(shortRunId)}</span>`;
    }

    const roleTag = (type === 'summary' || type === 'complete' || type === 'completed') ? 'OUT' : 'SYS';
    const colorClass = (type === 'summary' || type === 'complete' || type === 'completed') ? 'text-primary-600 border-primary-200 bg-primary-50' : 'text-accent-600 border-accent-200 bg-accent-50';
    const renderedContent = renderStreamContent(type, subType, content);

    return `
        <div class="w-8 h-8 rounded-full border ${colorClass} flex items-center justify-center flex-shrink-0 font-mono text-xs font-bold shadow-sm">
            ${roleTag}
        </div>
        <div class="flex-1 bg-white border border-gray-100 p-4 rounded-xl shadow-sm text-sm text-gray-700">
            <div class="mb-3 pb-3 border-b border-gray-100 flex flex-wrap gap-1">
                ${indicators}
            </div>
            <div class="markdown-content font-sans">${renderedContent}</div>
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
        messageDiv.innerHTML = buildAiMessageHTML(message.stage, message.subType, message.content, message.step, message.runId);
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
    analysis: { name: '分析阶段', icon: 'PLAN', class: 'stage-analysis' },
    execution: { name: '执行阶段', icon: 'RUN', class: 'stage-execution' },
    supervision: { name: '监督阶段', icon: 'QA', class: 'stage-supervision' },
    summary: { name: '总结阶段', icon: 'OUT', class: 'stage-summary' },
    error: { name: '错误信息', icon: 'ERR', class: 'stage-error' },
    complete: { name: '完成', icon: 'OK', class: 'stage-summary' },
    completed: { name: '完成', icon: 'OK', class: 'stage-summary' }
};

const subTypeMap = {
    analysis_status: '任务状态',
    analysis_history: '历史评估',
    analysis_tools: '工具能力摘要',
    analysis_plan: '结构化计划',
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

    document.getElementById('loading').style.display = 'flex';
    hasReceivedComplete = false;
    activeStreamRoundKey = `${sessionId}-${Date.now()}`;

    const requestData = {
        aiAgentId: selectedAgentId,
        message: message,
        sessionId: sessionId,
        maxStep: parseInt(selectedMaxStep)
    };

    fetch(EXECUTE_API_URL, {
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
    const { type, subType, step, content, completed, runId } = jsonData;

    if (type === 'complete' || type === 'completed' || completed === true) {
        hasReceivedComplete = true;
    }

    if (!content || content.trim() === '') {
        return;
    }

    addStageMessage(type, subType, content, step, runId);
}

function addStageMessage(type, subType, content, step, runId) {
    const streamKey = buildStreamMessageKey(type, subType, step);
    let targetContainer;
    if (type === 'summary' || type === 'complete' || type === 'completed') {
        targetContainer = document.getElementById('resultMessages');
    } else {
        targetContainer = document.getElementById('thinkingMessages');
    }

    const messageHTML = buildAiMessageHTML(type, subType, content, step, runId);
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
        runId: runId,
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
    document.getElementById('sessionId').textContent = '等待中';

    currentChatHistory = {
        sessionId: '',
        title: '',
        timestamp: 0,
        messages: [],
        agentId: selectedAgentId,
        maxStep: selectedMaxStep
    };

    document.getElementById('messageInput').value = '';
    focusPromptWhenRoomy();
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
        defaultMessage.textContent = agentId ? '当前智能体暂无案例' : '请先选择智能编排体';
        defaultMessage.style.display = 'block';
        return;
    }

    defaultMessage.style.display = 'none';
    defaultMessage.textContent = '请先选择智能编排体';

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
        selectedMaxStep = this.getAttribute('data-step');
        syncOptionButtons('.step-button', 'data-step', selectedMaxStep);
        updateAgentOverview();
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
            focusPromptWhenRoomy();
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
    focusPromptWhenRoomy();

    syncOptionButtons('.step-button', 'data-step', selectedMaxStep);

    if (selectedAgentId) {
        updateDropdownCases(selectedAgentId);
    }
});
