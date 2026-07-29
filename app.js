const API_BASE = resolveApiBase();

const state = {
    mode: "text",
    voiceOutput: false,
    selectedImages: [],
    selectedFiles: [],
    backendOnline: false,
    capabilities: null,
    latestLocation: null,
    activeTool: ""
};

const messages = document.getElementById("messages");
const composer = document.getElementById("composer");
const messageInput = document.getElementById("messageInput");
const sendButton = document.getElementById("sendButton");
const backendMode = document.getElementById("backendMode");
const backendStatus = document.getElementById("backendStatus");
const modeBadge = document.getElementById("modeBadge");
const voiceBadge = document.getElementById("voiceBadge");
const modeSummary = document.getElementById("modeSummary");
const inputSummary = document.getElementById("inputSummary");
const taskSummary = document.getElementById("taskSummary");
const taskSteps = document.getElementById("taskSteps");
const voiceToggle = document.getElementById("voiceToggle");
const voiceToggleWrap = document.getElementById("voiceToggleWrap");
const composerHint = document.getElementById("composerHint");
const attachmentStrip = document.getElementById("attachmentStrip");
const imageInput = document.getElementById("imageInput");
const fileInput = document.getElementById("fileInput");
const addImageButton = document.getElementById("addImageButton");
const addFileButton = document.getElementById("addFileButton");
const toolWorkbench = document.getElementById("toolWorkbench");
const toolWorkbenchLabel = document.getElementById("toolWorkbenchLabel");
const toolWorkbenchTitle = document.getElementById("toolWorkbenchTitle");
const toolWorkbenchDesc = document.getElementById("toolWorkbenchDesc");
const toolWorkbenchBody = document.getElementById("toolWorkbenchBody");
const toolWorkbenchClose = document.getElementById("toolWorkbenchClose");
const localDate = document.getElementById("localDate");
const localTime = document.getElementById("localTime");
const dailyRemaining = document.getElementById("dailyRemaining");

backendMode.textContent = API_BASE;

bindEvents();
renderMode();
renderAttachmentStrip();
renderInputSummary();
initializeAppearance();
checkBackendStatus();

function bindEvents() {
    document.querySelectorAll("[data-mode-button]").forEach((button) => {
        button.addEventListener("click", () => {
            state.mode = button.dataset.modeButton;
            renderMode();
        });
    });

    document.querySelectorAll("[data-tool]").forEach((button) => {
        button.addEventListener("click", () => {
            handleToolAction(button.dataset.tool);
        });
    });

    document.querySelectorAll("[data-theme]").forEach((button) => {
        button.addEventListener("click", () => applyTheme(button.dataset.theme));
    });

    toolWorkbenchClose.addEventListener("click", closeToolWorkbench);

    voiceToggle.addEventListener("change", () => {
        if (state.mode !== "text") {
            state.voiceOutput = false;
            voiceToggle.checked = false;
            return;
        }
        state.voiceOutput = voiceToggle.checked;
        renderMode();
    });

    addImageButton.addEventListener("click", () => imageInput.click());
    addFileButton.addEventListener("click", () => fileInput.click());

    imageInput.addEventListener("change", () => {
        state.selectedImages.push(...Array.from(imageInput.files || []));
        imageInput.value = "";
        renderAttachmentStrip();
        renderInputSummary();
    });

    fileInput.addEventListener("change", () => {
        state.selectedFiles.push(...Array.from(fileInput.files || []));
        fileInput.value = "";
        renderAttachmentStrip();
        renderInputSummary();
    });

    messageInput.addEventListener("input", resizeTextarea);

    composer.addEventListener("submit", async (event) => {
        event.preventDefault();
        if (!ensureBackend()) {
            return;
        }

        const prompt = messageInput.value.trim();
        const hasImages = state.selectedImages.length > 0;
        const hasFiles = state.selectedFiles.length > 0;
        if (!prompt && !hasImages && !hasFiles) {
            return;
        }

        const submittingMode = state.mode;
        const submittingVoice = state.voiceOutput;
        const images = [...state.selectedImages];
        const files = [...state.selectedFiles];

        appendUserMessage({
            label: submittingMode === "text" ? "你 · 文本大模型" : "你 · 图片工厂",
            text: prompt,
            images,
            files
        });

        updateTaskRail({
            title: "处理中",
            description: submittingMode === "text"
                ? "正在整理文字、图片和文件内容。"
                : "正在准备图片工厂请求。",
            steps: ["接收输入", "整理内容", "调用能力", "返回结果"],
            activeStep: 1
        });

        messageInput.value = "";
        state.selectedImages = [];
        state.selectedFiles = [];
        renderAttachmentStrip();
        renderInputSummary();
        resizeTextarea();
        setSubmitting(true);

        try {
            const response = await submitAssistantRequest(submittingMode, submittingVoice, prompt, images, files);
            appendAssistantResponse(response);
            updateTaskRail({
                title: response.title || (submittingMode === "text" ? "文本完成" : "图片完成"),
                description: response.summary || "本次请求已处理完成。",
                steps: ["接收输入", "整理内容", "调用能力", "返回结果"],
                activeStep: 3
            });
        } catch (error) {
            appendAssistantText(`这次处理失败了：${error.message || "后端暂时不可用"}`);
            updateTaskRail({
                title: "处理失败",
                description: "请求已经发出，但这次没有拿到可用结果。",
                steps: ["接收输入", "整理内容", "调用能力", "等待重试"],
                activeStep: 3
            });
        } finally {
            setSubmitting(false);
        }
    });
}

function initializeAppearance() {
    let savedTheme = "minimal";
    try {
        savedTheme = window.localStorage.getItem("assistantTheme") || "minimal";
    } catch (error) {
        savedTheme = "minimal";
    }

    applyTheme(savedTheme, false);
    updateLocalClock();
    window.setInterval(updateLocalClock, 1000);
}

function applyTheme(theme, persist = true) {
    const normalizedTheme = theme === "girl" ? "girl" : "minimal";
    document.body.classList.toggle("theme-girl", normalizedTheme === "girl");
    document.querySelectorAll("[data-theme]").forEach((button) => {
        button.classList.toggle("active", button.dataset.theme === normalizedTheme);
    });

    if (persist) {
        try {
            window.localStorage.setItem("assistantTheme", normalizedTheme);
        } catch (error) {
            // Theme still works for the current page when browser storage is unavailable.
        }
    }
}

function updateLocalClock() {
    const now = new Date();
    localDate.textContent = new Intl.DateTimeFormat("zh-CN", {
        year: "numeric",
        month: "long",
        day: "numeric",
        weekday: "long"
    }).format(now);
    localTime.textContent = new Intl.DateTimeFormat("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false
    }).format(now);

    const remainingSeconds = (23 - now.getHours()) * 3600
        + (59 - now.getMinutes()) * 60
        + (59 - now.getSeconds());
    const hours = Math.floor(remainingSeconds / 3600);
    const minutes = Math.floor((remainingSeconds % 3600) / 60);
    dailyRemaining.textContent = `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
}

function resolveApiBase() {
    const configured = getConfiguredApiBase();
    if (configured) {
        return configured;
    }

    const { protocol, hostname, origin } = window.location;
    if (protocol === "file:") {
        return "http://localhost:8080/api/web-assistant";
    }

    const isLocalPreview = hostname === "localhost" || hostname === "127.0.0.1" || hostname === "::1";
    if (isLocalPreview && !origin.endsWith(":8080")) {
        return "http://localhost:8080/api/web-assistant";
    }

    return `${origin}/api/web-assistant`;
}

function getConfiguredApiBase() {
    const queryValue = new URLSearchParams(window.location.search).get("api");
    if (queryValue) {
        return queryValue.replace(/\/$/, "");
    }

    try {
        const storedValue = window.localStorage.getItem("assistantApiBase");
        return storedValue ? storedValue.replace(/\/$/, "") : "";
    } catch (error) {
        return "";
    }
}

async function checkBackendStatus() {
    try {
        const response = await fetchJson(`${API_BASE}/status`);
        state.backendOnline = true;
        state.capabilities = response;
        backendStatus.textContent = response.summary || "后端已在线";
        modeBadge.classList.remove("offline");
        modeBadge.classList.add("online");
        modeBadge.textContent = "后端已连接";
    } catch (error) {
        state.backendOnline = false;
        state.capabilities = null;
        backendStatus.textContent = "后端未连接，请先启动本地 Spring Boot 服务";
        modeBadge.classList.remove("online");
        modeBadge.classList.add("offline");
        modeBadge.textContent = "后端未连接";
    }

    setSubmitting(false);
    renderMode();
}

function ensureBackend() {
    if (state.backendOnline) {
        return true;
    }
    appendAssistantText("后端还没有连通，请先启动本地服务，再继续操作。");
    return false;
}

function renderMode() {
    document.querySelectorAll("[data-mode-button]").forEach((button) => {
        button.classList.toggle("active", button.dataset.modeButton === state.mode);
    });

    const isTextMode = state.mode === "text";
    if (!isTextMode) {
        state.voiceOutput = false;
    }

    voiceToggle.checked = state.voiceOutput;
    voiceToggle.disabled = !isTextMode;
    voiceToggleWrap.classList.toggle("disabled", !isTextMode);
    voiceBadge.textContent = isTextMode
        ? (state.voiceOutput ? "语音输出开启" : "语音输出关闭")
        : "图片工厂不支持语音输出";

    sendButton.textContent = isTextMode ? "发送" : "开始生成";
    modeSummary.textContent = isTextMode ? "文本大模型" : "图片工厂";

    if (state.mode === "image" && state.capabilities && !state.capabilities.imageGenerationAvailable) {
        composerHint.textContent = "当前图片工厂会先整理提示词，待图片接口启用后可直接出图";
        messageInput.placeholder = "描述你想生成或修改的图片";
    } else if (isTextMode) {
        composerHint.textContent = "当前模式：文本大模型";
        messageInput.placeholder = "输入内容，或只上传图片 / 文件后直接发送";
    } else if (state.selectedImages.length > 0 || hasImageFilesInSelection()) {
        composerHint.textContent = "当前模式：图片工厂，将按图片修改处理";
        messageInput.placeholder = "描述你希望怎么改这张图";
    } else {
        composerHint.textContent = "当前模式：图片工厂，将按文生图处理";
        messageInput.placeholder = "描述你想生成的图片";
    }

    renderInputSummary();
}

function renderAttachmentStrip() {
    attachmentStrip.replaceChildren();
    const items = [
        ...state.selectedImages.map((file, index) => ({ kind: "image", label: file.name, index })),
        ...state.selectedFiles.map((file, index) => ({ kind: "file", label: file.name, index }))
    ];

    attachmentStrip.hidden = items.length === 0;
    items.forEach((item) => {
        const chip = document.createElement("div");
        chip.className = "attachment-chip";

        const text = document.createElement("span");
        text.textContent = `${item.kind === "image" ? "图片" : "文件"} · ${item.label}`;

        const removeButton = document.createElement("button");
        removeButton.type = "button";
        removeButton.textContent = "×";
        removeButton.addEventListener("click", () => {
            if (item.kind === "image") {
                state.selectedImages.splice(item.index, 1);
            } else {
                state.selectedFiles.splice(item.index, 1);
            }
            renderAttachmentStrip();
            renderInputSummary();
            renderMode();
        });

        chip.append(text, removeButton);
        attachmentStrip.append(chip);
    });

    renderMode();
}

function renderInputSummary() {
    inputSummary.replaceChildren();
    const items = [
        summaryItem(`当前模式：${state.mode === "text" ? "文本大模型" : "图片工厂"}`),
        summaryItem(`图片 ${state.selectedImages.length} 张`),
        summaryItem(`文件 ${state.selectedFiles.length} 个`)
    ];

    if (state.mode === "text") {
        items.push(summaryItem(`语音输出${state.voiceOutput ? "已开启" : "未开启"}`));
    }
    if (state.latestLocation && state.latestLocation.address) {
        items.push(summaryItem(`当前位置：${state.latestLocation.address}`));
    }

    items.forEach((item) => inputSummary.append(item));
}

function summaryItem(text) {
    const li = document.createElement("li");
    const dot = document.createElement("span");
    dot.className = "dot quiet";
    li.append(dot, document.createTextNode(text));
    return li;
}

function resizeTextarea() {
    messageInput.style.height = "54px";
    messageInput.style.height = `${Math.min(messageInput.scrollHeight, 180)}px`;
}

function setSubmitting(isSubmitting) {
    sendButton.disabled = isSubmitting || !state.backendOnline;
    addImageButton.disabled = isSubmitting;
    addFileButton.disabled = isSubmitting;
    document.querySelectorAll("[data-tool]").forEach((button) => {
        button.disabled = isSubmitting;
    });
    sendButton.textContent = isSubmitting
        ? (state.mode === "text" ? "发送中" : "生成中")
        : (state.mode === "text" ? "发送" : "开始生成");
}

async function submitAssistantRequest(mode, voiceOutput, prompt, images, files) {
    const formData = new FormData();
    if (prompt) {
        formData.append("prompt", prompt);
    }
    if (mode === "text") {
        formData.append("voiceOutput", String(voiceOutput));
    }

    images.forEach((file) => formData.append("images", file, file.name));
    files.forEach((file) => formData.append("files", file, file.name));

    const endpoint = mode === "text" ? "/text" : "/image/factory";
    return fetchJson(`${API_BASE}${endpoint}`, {
        method: "POST",
        body: formData
    });
}

function handleToolAction(tool) {
    if (!ensureBackend()) {
        return;
    }

    switch (tool) {
        case "ip":
            executeIpTool();
            return;
        case "location":
            executeLocationTool();
            return;
        case "weather":
            openWeatherWorkbench();
            return;
        case "taxi":
            openTaxiWorkbench();
            return;
        case "around":
            openAroundWorkbench();
            return;
        case "route":
            openRouteWorkbench();
            return;
        case "reminder":
            openReminderWorkbench();
            return;
        case "schedule":
            openScheduleWorkbench();
            return;
        default:
            return;
    }
}

function openWorkbench({ label, title, description, bodyBuilder, tool }) {
    state.activeTool = tool;
    toolWorkbench.hidden = false;
    toolWorkbenchLabel.textContent = label;
    toolWorkbenchTitle.textContent = title;
    toolWorkbenchDesc.textContent = description;
    toolWorkbenchBody.replaceChildren();
    bodyBuilder(toolWorkbenchBody);
}

function closeToolWorkbench() {
    state.activeTool = "";
    toolWorkbench.hidden = true;
    toolWorkbenchBody.replaceChildren();
}

function closeWorkbenchAfterAction() {
    if (!toolWorkbench.hidden) {
        closeToolWorkbench();
    }
}

function openWeatherWorkbench() {
    openWorkbench({
        tool: "weather",
        label: "天气工具",
        title: "查询天气",
        description: "输入城市、区县或地点名，直接查询实时天气或天气预报。",
        bodyBuilder(container) {
            const row = createFormRow();
            const input = createInput("text", "例如：杭州、上海浦东、余杭区");
            const currentButton = createButton("查实时", () => runWeatherQuery(input.value.trim(), false));
            const forecastButton = createButton("查预报", () => runWeatherQuery(input.value.trim(), true));
            row.append(input, currentButton, forecastButton);
            container.append(row);
        }
    });
}

function openTaxiWorkbench() {
    openWorkbench({
        tool: "taxi",
        label: "打车工具",
        title: "生成滴滴官方行程链接",
        description: "先确认你的出发地，再输入目的地，系统会生成可直接打开的滴滴链接。",
        bodyBuilder(container) {
            const meta = document.createElement("div");
            meta.className = "tool-inline-meta";
            meta.textContent = state.latestLocation && state.latestLocation.address
                ? `当前起点：${state.latestLocation.address}`
                : "当前起点：还没有定位，请先获取当前位置。";

            const row = createFormRow();
            const locateButton = createButton(
                state.latestLocation ? "刷新起点" : "获取起点",
                async () => {
                    await executeLocationTool(false);
                    openTaxiWorkbench();
                }
            );
            const input = createInput("text", "输入目的地，例如：阿里巴巴西溪园区");
            const submitButton = createButton("生成链接", () => runTaxiTool(input.value.trim()));
            row.append(locateButton, input, submitButton);

            container.append(meta, row);
        }
    });
}

function openAroundWorkbench() {
    openWorkbench({
        tool: "around",
        label: "周边搜索",
        title: "搜索附近地点",
        description: "根据当前位置搜索周边餐饮、咖啡店、商场、地铁站等。",
        bodyBuilder(container) {
            const meta = document.createElement("div");
            meta.className = "tool-inline-meta";
            meta.textContent = state.latestLocation && state.latestLocation.address
                ? `搜索中心：${state.latestLocation.address}`
                : "搜索中心：还没有定位，请先获取当前位置。";

            const row1 = createFormRow();
            const locateButton = createButton(
                state.latestLocation ? "刷新位置" : "获取位置",
                async () => {
                    await executeLocationTool(false);
                    openAroundWorkbench();
                }
            );
            const keywordInput = createInput("text", "输入关键词，例如：咖啡店、地铁站、医院");
            row1.append(locateButton, keywordInput);

            const row2 = createFormRow();
            const radiusInput = createInput("number", "");
            radiusInput.min = "100";
            radiusInput.max = "50000";
            radiusInput.value = "1500";
            radiusInput.placeholder = "半径（米）";

            const limitInput = createInput("number", "");
            limitInput.min = "1";
            limitInput.max = "20";
            limitInput.value = "10";
            limitInput.placeholder = "数量";

            const submitButton = createButton("开始搜索", () => runAroundSearch(
                keywordInput.value.trim(),
                Number(radiusInput.value || 1500),
                Number(limitInput.value || 10)
            ));

            row2.append(radiusInput, limitInput, submitButton);
            container.append(meta, row1, row2);
        }
    });
}

function openRouteWorkbench() {
    openWorkbench({
        tool: "route",
        label: "路线规划",
        title: "查询路线方案",
        description: "从当前位置出发，查询驾车、步行、公交或骑行路线。",
        bodyBuilder(container) {
            const meta = document.createElement("div");
            meta.className = "tool-inline-meta";
            meta.textContent = state.latestLocation && state.latestLocation.address
                ? `当前起点：${state.latestLocation.address}`
                : "当前起点：还没有定位，请先获取当前位置。";

            const row1 = createFormRow();
            const locateButton = createButton(
                state.latestLocation ? "刷新起点" : "获取起点",
                async () => {
                    await executeLocationTool(false);
                    openRouteWorkbench();
                }
            );
            const destinationInput = createInput("text", "输入目的地，例如：西湖风景区");
            row1.append(locateButton, destinationInput);

            const row2 = createFormRow();
            const modeSelect = document.createElement("select");
            modeSelect.className = "tool-select";
            [
                ["driving", "驾车"],
                ["walking", "步行"],
                ["transit", "公交"],
                ["bicycling", "骑行"]
            ].forEach(([value, label]) => {
                const option = document.createElement("option");
                option.value = value;
                option.textContent = label;
                modeSelect.append(option);
            });

            const submitButton = createButton("查询路线", () => runRouteSearch(
                destinationInput.value.trim(),
                modeSelect.value
            ));

            row2.append(modeSelect, submitButton);
            container.append(meta, row1, row2);
        }
    });
}

function openReminderWorkbench() {
    openWorkbench({
        tool: "reminder",
        label: "定时提醒",
        title: "创建和查看提醒",
        description: "提醒会走现有自动化能力，若未绑定接收人，后端会直接提示。",
        bodyBuilder(container) {
            const titleInput = createInput("text", "提醒标题，例如：开会前准备周报");
            const remindAtInput = createInput("datetime-local", "");
            remindAtInput.value = formatDateTimeLocalValue(addMinutes(new Date(), 60));

            const messageInput = document.createElement("textarea");
            messageInput.className = "tool-textarea";
            messageInput.rows = 3;
            messageInput.placeholder = "提醒内容（可选，不填默认跟标题一致）";

            const row = createFormRow();
            const createButtonEl = createButton("创建提醒", () => runReminderCreate(
                titleInput.value.trim(),
                remindAtInput.value,
                messageInput.value.trim()
            ));
            const listButton = createButton("查看提醒", () => runReminderList());
            row.append(createButtonEl, listButton);

            container.append(titleInput, remindAtInput, messageInput, row);
        }
    });
}

function openScheduleWorkbench() {
    openWorkbench({
        tool: "schedule",
        label: "日程工具",
        title: "创建和查看日程",
        description: "适合记录一个时间段内的安排，并按时间范围查看。",
        bodyBuilder(container) {
            const titleInput = createInput("text", "日程标题，例如：项目对齐会");
            const startInput = createInput("datetime-local", "");
            const endInput = createInput("datetime-local", "");
            const startDate = addMinutes(new Date(), 60);
            const endDate = addMinutes(startDate, 60);
            startInput.value = formatDateTimeLocalValue(startDate);
            endInput.value = formatDateTimeLocalValue(endDate);

            const notesInput = document.createElement("textarea");
            notesInput.className = "tool-textarea";
            notesInput.rows = 3;
            notesInput.placeholder = "备注（可选）";

            const createRow = createFormRow();
            const createButtonEl = createButton("创建日程", () => runScheduleCreate(
                titleInput.value.trim(),
                startInput.value,
                endInput.value,
                notesInput.value.trim()
            ));
            createRow.append(createButtonEl);

            const rangeLabel = document.createElement("div");
            rangeLabel.className = "tool-inline-meta";
            rangeLabel.textContent = "查看时间范围";

            const fromInput = createInput("datetime-local", "");
            const toInput = createInput("datetime-local", "");
            fromInput.value = formatDateTimeLocalValue(startOfToday());
            toInput.value = formatDateTimeLocalValue(addDays(startOfToday(), 7));

            const listRow = createFormRow();
            const listButton = createButton("查看日程", () => runScheduleList(fromInput.value, toInput.value));
            listRow.append(fromInput, toInput, listButton);

            container.append(titleInput, startInput, endInput, notesInput, createRow, rangeLabel, listRow);
        }
    });
}

async function executeIpTool() {
    appendToolAction("IP 工具", "查询当前公网 IP");
    updateTaskRail({
        title: "IP 查询中",
        description: "正在获取当前公网 IP 信息。",
        steps: ["接收请求", "调用接口", "整理结果", "返回结果"],
        activeStep: 1
    });

    try {
        const response = await fetchJson(`${API_BASE}/ip`);
        appendAssistantResponse({
            title: "IP 工具",
            text: formatToolReply(response.title, response.summary, response.content)
        });
        updateTaskRail({
            title: "IP 查询完成",
            description: "公网 IP 信息已返回。",
            steps: ["接收请求", "调用接口", "整理结果", "返回结果"],
            activeStep: 3
        });
    } catch (error) {
        appendAssistantText(`IP 查询失败：${error.message}`);
    }
}

async function executeLocationTool(announce = true) {
    if (announce) {
        appendToolAction("定位工具", "获取当前位置");
    }

    updateTaskRail({
        title: "定位中",
        description: "正在读取浏览器定位并反查地址。",
        steps: ["请求定位", "获取坐标", "反查地址", "返回结果"],
        activeStep: 1
    });

    try {
        const location = await locateCurrentPosition();
        state.latestLocation = location;
        renderInputSummary();

        if (announce) {
            appendAssistantResponse({
                title: "定位工具",
                text: [
                    "已获取当前位置。",
                    `地址：${location.address || "未识别到地址"}`,
                    `坐标：${location.longitude}, ${location.latitude}`
                ].join("\n")
            });
        }

        updateTaskRail({
            title: "定位完成",
            description: "当前位置和地址已获取。",
            steps: ["请求定位", "获取坐标", "反查地址", "返回结果"],
            activeStep: 3
        });
        return location;
    } catch (error) {
        if (announce) {
            appendAssistantText(`定位失败：${error.message}`);
        }
        throw error;
    }
}

async function runWeatherQuery(location, forecast) {
    if (!location) {
        appendAssistantText("天气工具还缺一个地点，请先输入城市或区域。");
        return;
    }

    closeWorkbenchAfterAction();

    appendToolAction("天气工具", `${forecast ? "查询预报" : "查询实时天气"} · ${location}`);
    updateTaskRail({
        title: "天气查询中",
        description: `正在查询 ${location} 的${forecast ? "天气预报" : "实时天气"}。`,
        steps: ["接收请求", "标准化地点", "调用天气接口", "返回结果"],
        activeStep: 2
    });

    try {
        const path = forecast ? "/weather/forecast" : "/weather/current";
        const response = await fetchJson(`${API_BASE}${path}?location=${encodeURIComponent(location)}`);
        appendAssistantResponse({
            title: "天气工具",
            text: formatToolReply(response.title, response.summary, response.content)
        });
        updateTaskRail({
            title: "天气查询完成",
            description: `${location} 的天气信息已返回。`,
            steps: ["接收请求", "标准化地点", "调用天气接口", "返回结果"],
            activeStep: 3
        });
    } catch (error) {
        appendAssistantText(`天气查询失败：${error.message}`);
    }
}

async function runTaxiTool(destination) {
    if (!destination) {
        appendAssistantText("打车工具还缺一个目的地。");
        return;
    }

    closeWorkbenchAfterAction();

    try {
        const origin = state.latestLocation || await executeLocationTool(false);
        appendToolAction("打车工具", `从当前位置前往 ${destination}`);
        updateTaskRail({
            title: "生成打车链接中",
            description: "正在解析目的地并生成滴滴行程链接。",
            steps: ["确认起点", "解析终点", "生成链接", "返回结果"],
            activeStep: 2
        });

        const response = await fetchJson(`${API_BASE}/taxi/link`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                originLongitude: origin.longitude,
                originLatitude: origin.latitude,
                originName: origin.address,
                destination
            })
        });

        if (!response.success) {
            throw new Error(response.message || "打车工具暂时不可用");
        }

        appendAssistantResponse({
            title: "打车工具",
            text: [
                response.message || "已生成滴滴行程链接。",
                response.originName ? `起点：${response.originName}` : "",
                response.destinationName ? `终点：${response.destinationName}` : "",
                response.rawText || ""
            ].filter(Boolean).join("\n\n"),
            links: response.appLink ? [{ label: "打开滴滴行程链接", url: response.appLink }] : []
        });

        updateTaskRail({
            title: "打车链接已生成",
            description: "可以直接打开滴滴链接继续下单。",
            steps: ["确认起点", "解析终点", "生成链接", "返回结果"],
            activeStep: 3
        });
    } catch (error) {
        appendAssistantText(`打车工具失败：${error.message}`);
    }
}

async function runAroundSearch(keywords, radius, limit) {
    if (!keywords) {
        appendAssistantText("周边搜索还缺关键词，例如咖啡店、便利店、商场。");
        return;
    }

    closeWorkbenchAfterAction();

    try {
        const origin = state.latestLocation || await executeLocationTool(false);
        appendToolAction("周边搜索", `搜索附近：${keywords}`);
        updateTaskRail({
            title: "周边搜索中",
            description: "正在基于当前位置搜索周边地点。",
            steps: ["确认中心点", "调用搜索", "整理结果", "返回结果"],
            activeStep: 1
        });

        const response = await fetchJson(`${API_BASE}/around/search`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                longitude: origin.longitude,
                latitude: origin.latitude,
                keywords,
                radius,
                limit
            })
        });

        if (!response.success) {
            throw new Error(response.summary || "周边搜索失败");
        }

        appendAssistantResponse({
            title: "周边搜索",
            text: formatToolReply(response.title, response.summary, response.content)
        });
        updateTaskRail({
            title: "周边搜索完成",
            description: "附近地点结果已返回。",
            steps: ["确认中心点", "调用搜索", "整理结果", "返回结果"],
            activeStep: 3
        });
    } catch (error) {
        appendAssistantText(`周边搜索失败：${error.message}`);
    }
}

async function runRouteSearch(destination, mode) {
    if (!destination) {
        appendAssistantText("路线规划还缺目的地。");
        return;
    }

    closeWorkbenchAfterAction();

    try {
        const origin = state.latestLocation || await executeLocationTool(false);
        appendToolAction("路线规划", `${modeLabel(mode)}到 ${destination}`);
        updateTaskRail({
            title: "路线规划中",
            description: "正在生成路线方案。",
            steps: ["确认起点", "解析终点", "调用规划", "返回结果"],
            activeStep: 1
        });

        const response = await fetchJson(`${API_BASE}/route/search`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                originLongitude: origin.longitude,
                originLatitude: origin.latitude,
                destination,
                mode
            })
        });

        if (!response.success) {
            throw new Error(response.summary || "路线规划失败");
        }

        appendAssistantResponse({
            title: "路线规划",
            text: formatToolReply(response.title, response.summary, response.content)
        });
        updateTaskRail({
            title: "路线规划完成",
            description: "路线方案已返回。",
            steps: ["确认起点", "解析终点", "调用规划", "返回结果"],
            activeStep: 3
        });
    } catch (error) {
        appendAssistantText(`路线规划失败：${error.message}`);
    }
}

async function runReminderCreate(title, remindAtLocal, message) {
    if (!title || !remindAtLocal) {
        appendAssistantText("创建提醒前，请先填好标题和提醒时间。");
        return;
    }

    closeWorkbenchAfterAction();

    appendToolAction("定时提醒", `创建提醒：${title}`);
    updateTaskRail({
        title: "创建提醒中",
        description: "正在提交提醒事项。",
        steps: ["整理参数", "提交提醒", "保存结果", "返回结果"],
        activeStep: 1
    });

    try {
        const response = await fetchJson(`${API_BASE}/automation/reminder`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                title,
                remindAt: toOffsetIso(remindAtLocal),
                message
            })
        });

        appendAutomationResponse("定时提醒", response);
        updateTaskRail({
            title: response.success ? "提醒创建完成" : "提醒创建失败",
            description: response.summary || "提醒请求已处理。",
            steps: ["整理参数", "提交提醒", "保存结果", "返回结果"],
            activeStep: 3
        });
    } catch (error) {
        appendAssistantText(`创建提醒失败：${error.message}`);
    }
}

async function runReminderList() {
    closeWorkbenchAfterAction();

    appendToolAction("定时提醒", "查看提醒列表");
    updateTaskRail({
        title: "加载提醒列表中",
        description: "正在读取当前提醒事项。",
        steps: ["发起请求", "读取提醒", "整理结果", "返回结果"],
        activeStep: 1
    });

    try {
        const response = await fetchJson(`${API_BASE}/automation/reminders`);
        appendAutomationResponse("定时提醒", response);
        updateTaskRail({
            title: "提醒列表已返回",
            description: response.summary || "提醒列表已读取。",
            steps: ["发起请求", "读取提醒", "整理结果", "返回结果"],
            activeStep: 3
        });
    } catch (error) {
        appendAssistantText(`加载提醒列表失败：${error.message}`);
    }
}

async function runScheduleCreate(title, startAtLocal, endAtLocal, notes) {
    if (!title || !startAtLocal || !endAtLocal) {
        appendAssistantText("创建日程前，请先填好标题、开始时间和结束时间。");
        return;
    }

    closeWorkbenchAfterAction();

    appendToolAction("日程工具", `创建日程：${title}`);
    updateTaskRail({
        title: "创建日程中",
        description: "正在保存新的日程安排。",
        steps: ["整理参数", "提交日程", "保存结果", "返回结果"],
        activeStep: 1
    });

    try {
        const response = await fetchJson(`${API_BASE}/automation/schedule`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                title,
                startAt: toOffsetIso(startAtLocal),
                endAt: toOffsetIso(endAtLocal),
                notes
            })
        });

        appendAutomationResponse("日程工具", response);
        updateTaskRail({
            title: response.success ? "日程创建完成" : "日程创建失败",
            description: response.summary || "日程请求已处理。",
            steps: ["整理参数", "提交日程", "保存结果", "返回结果"],
            activeStep: 3
        });
    } catch (error) {
        appendAssistantText(`创建日程失败：${error.message}`);
    }
}

async function runScheduleList(fromLocal, toLocal) {
    if (!fromLocal || !toLocal) {
        appendAssistantText("查看日程前，请先填好开始和结束范围。");
        return;
    }

    closeWorkbenchAfterAction();

    appendToolAction("日程工具", "查看日程列表");
    updateTaskRail({
        title: "加载日程中",
        description: "正在按时间范围读取日程安排。",
        steps: ["整理范围", "读取日程", "整理结果", "返回结果"],
        activeStep: 1
    });

    try {
        const query = new URLSearchParams({
            from: toOffsetIso(fromLocal),
            to: toOffsetIso(toLocal)
        });
        const response = await fetchJson(`${API_BASE}/automation/schedules?${query.toString()}`);
        appendAutomationResponse("日程工具", response);
        updateTaskRail({
            title: "日程列表已返回",
            description: response.summary || "日程范围已读取。",
            steps: ["整理范围", "读取日程", "整理结果", "返回结果"],
            activeStep: 3
        });
    } catch (error) {
        appendAssistantText(`查看日程失败：${error.message}`);
    }
}

async function locateCurrentPosition() {
    if (!navigator.geolocation) {
        throw new Error("当前浏览器不支持定位。");
    }

    const coords = await new Promise((resolve, reject) => {
        navigator.geolocation.getCurrentPosition(
            (position) => resolve(position.coords),
            (error) => {
                if (error && error.code === 1) {
                    reject(new Error("你拒绝了定位权限。"));
                    return;
                }
                if (error && error.code === 2) {
                    reject(new Error("定位暂时不可用。"));
                    return;
                }
                if (error && error.code === 3) {
                    reject(new Error("定位超时，请重试。"));
                    return;
                }
                reject(new Error("获取定位失败。"));
            },
            { enableHighAccuracy: true, timeout: 12000, maximumAge: 0 }
        );
    });

    const response = await fetchJson(
        `${API_BASE}/location/reverse?longitude=${encodeURIComponent(coords.longitude)}&latitude=${encodeURIComponent(coords.latitude)}`
    );

    return {
        address: response.address || "未识别到地址",
        longitude: response.longitude,
        latitude: response.latitude,
        city: response.city || "",
        adcode: response.adcode || ""
    };
}

function appendToolAction(title, detail) {
    appendUserMessage({
        label: "你 · 工具",
        text: `${title}\n${detail}`,
        images: [],
        files: []
    });
}

function appendUserMessage(payload) {
    const article = document.createElement("article");
    article.className = "message user";

    const avatar = document.createElement("div");
    avatar.className = "avatar";
    avatar.textContent = "你";

    const bubble = document.createElement("div");
    bubble.className = "bubble";

    const label = document.createElement("p");
    label.className = "bubble-label";
    label.textContent = payload.label || (state.mode === "text" ? "你 · 文本大模型" : "你 · 图片工厂");

    const content = document.createElement("div");
    content.className = "bubble-content";

    const lines = [];
    if (payload.text) {
        lines.push(payload.text);
    }
    if (payload.images && payload.images.length > 0) {
        lines.push(`已附图片 ${payload.images.length} 张`);
    }
    if (payload.files && payload.files.length > 0) {
        lines.push(`已附文件 ${payload.files.length} 个`);
    }
    content.textContent = lines.join("\n");

    bubble.append(label, content);
    article.append(avatar, bubble);
    messages.append(article);
    scrollMessagesToBottom();
}

function appendAssistantText(text) {
    appendAssistantResponse({
        title: "助手",
        text,
        images: [],
        files: [],
        notices: [],
        links: []
    });
}

function appendAutomationResponse(defaultTitle, response) {
    appendAssistantResponse({
        title: response.title || defaultTitle,
        text: [
            response.summary || "",
            response.content || ""
        ].filter(Boolean).join("\n\n")
    });
}

function appendAssistantResponse(response) {
    const article = document.createElement("article");
    article.className = "message assistant";

    const avatar = document.createElement("div");
    avatar.className = "avatar soft";
    avatar.textContent = "A";

    const bubble = document.createElement("div");
    bubble.className = "bubble";

    const label = document.createElement("p");
    label.className = "bubble-label";
    label.textContent = response.title || "助手";

    const content = document.createElement("div");
    content.className = "bubble-content";
    content.textContent = response.text || response.summary || "已处理完成。";

    bubble.append(label, content);

    const assets = document.createElement("div");
    assets.className = "bubble-assets";
    let hasAssets = false;

    if (Array.isArray(response.images) && response.images.length > 0) {
        hasAssets = true;
        const imageGrid = document.createElement("div");
        imageGrid.className = "image-grid";
        response.images.forEach((image) => {
            const img = document.createElement("img");
            img.className = "result-image";
            img.src = image.dataUrl;
            img.alt = image.fileName || "生成图片";
            imageGrid.append(img);
        });
        assets.append(imageGrid);
    }

    if (response.audio && response.audio.dataUrl) {
        hasAssets = true;
        assets.append(buildVoiceBubble(response.audio));
    }

    if (Array.isArray(response.links) && response.links.length > 0) {
        hasAssets = true;
        assets.append(buildLinkBlock("可用链接", response.links));
    }

    const metaBlocks = [];
    if (Array.isArray(response.files) && response.files.length > 0) {
        metaBlocks.push(buildMetaBlock("文件处理", response.files.map((file) => {
            const details = [];
            details.push(file.fileName || "未命名文件");
            if (file.extractedImageCount) {
                details.push(`提取图片 ${file.extractedImageCount} 张`);
            }
            if (file.extractedText) {
                details.push(file.extractedText);
            }
            return details.join("\n");
        })));
    }
    if (Array.isArray(response.notices) && response.notices.length > 0) {
        metaBlocks.push(buildMetaBlock("补充说明", response.notices));
    }

    if (metaBlocks.length > 0) {
        hasAssets = true;
        metaBlocks.forEach((block) => assets.append(block));
    }

    if (hasAssets) {
        bubble.append(assets);
    }

    article.append(avatar, bubble);
    messages.append(article);
    scrollMessagesToBottom();
}

function buildMetaBlock(title, lines) {
    const block = document.createElement("div");
    block.className = "meta-block";

    const label = document.createElement("p");
    label.className = "bubble-label";
    label.textContent = title;

    const list = document.createElement("ul");
    list.className = "meta-list";
    lines.forEach((line) => {
        const item = document.createElement("li");
        item.textContent = line;
        list.append(item);
    });

    block.append(label, list);
    return block;
}

function buildVoiceBubble(audioAsset) {
    const wrap = document.createElement("div");
    wrap.className = "voice-bubble";

    const playButton = document.createElement("button");
    playButton.type = "button";
    playButton.className = "voice-play";
    playButton.setAttribute("aria-label", "播放语音回复");
    playButton.textContent = "▶";

    const wave = document.createElement("div");
    wave.className = "voice-wave";
    for (let i = 0; i < 16; i += 1) {
        const bar = document.createElement("span");
        bar.style.setProperty("--i", String(i));
        wave.append(bar);
    }

    const meta = document.createElement("span");
    meta.className = "voice-meta";
    meta.textContent = audioAsset.fileName || "语音回复";

    const audio = document.createElement("audio");
    audio.className = "audio-player";
    audio.preload = "metadata";
    audio.src = audioAsset.dataUrl;

    playButton.addEventListener("click", async () => {
        if (!audio.paused) {
            audio.pause();
            return;
        }
        try {
            await audio.play();
        } catch (error) {
            audio.controls = true;
        }
    });

    audio.addEventListener("play", () => {
        wrap.classList.add("playing");
        playButton.textContent = "❚❚";
    });
    audio.addEventListener("pause", () => {
        wrap.classList.remove("playing");
        playButton.textContent = "▶";
    });
    audio.addEventListener("ended", () => {
        wrap.classList.remove("playing");
        playButton.textContent = "▶";
    });
    audio.addEventListener("loadedmetadata", () => {
        if (Number.isFinite(audio.duration) && audio.duration > 0) {
            meta.textContent = `${Math.round(audio.duration)} 秒 · 语音回复`;
        }
    });

    wrap.append(playButton, wave, meta, audio);
    return wrap;
}

function buildLinkBlock(title, links) {
    const block = document.createElement("div");
    block.className = "meta-block";

    const label = document.createElement("p");
    label.className = "bubble-label";
    label.textContent = title;

    const list = document.createElement("ul");
    list.className = "meta-list";
    links.forEach((link) => {
        const item = document.createElement("li");
        const anchor = document.createElement("a");
        anchor.href = link.url;
        anchor.target = "_blank";
        anchor.rel = "noopener noreferrer";
        anchor.textContent = link.label || link.url;
        item.append(anchor);
        list.append(item);
    });

    block.append(label, list);
    return block;
}

function updateTaskRail(task) {
    taskSummary.replaceChildren();

    const title = document.createElement("strong");
    title.textContent = task.title;

    const description = document.createElement("p");
    description.textContent = task.description;

    taskSummary.append(title, description);
    taskSteps.replaceChildren();
    task.steps.forEach((step, index) => {
        const item = document.createElement("li");
        item.textContent = step;
        if (index === task.activeStep) {
            item.classList.add("active");
        }
        taskSteps.append(item);
    });
}

function scrollMessagesToBottom() {
    messages.scrollTop = messages.scrollHeight;
}

function hasImageFilesInSelection() {
    return state.selectedFiles.some((file) => (file.type || "").startsWith("image/"));
}

function formatToolReply(title, summary, content) {
    return [
        title || "处理完成",
        summary || "",
        content || ""
    ].filter(Boolean).join("\n\n");
}

function createFormRow() {
    const row = document.createElement("div");
    row.className = "tool-inline-row";
    return row;
}

function createInput(type, placeholder) {
    const input = document.createElement("input");
    input.type = type;
    input.placeholder = placeholder;
    return input;
}

function createButton(text, onClick) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "tool-btn";
    button.textContent = text;
    button.addEventListener("click", onClick);
    return button;
}

function modeLabel(mode) {
    switch (mode) {
        case "walking":
            return "步行";
        case "transit":
            return "公交";
        case "bicycling":
            return "骑行";
        default:
            return "驾车";
    }
}

function toOffsetIso(localValue) {
    if (!localValue) {
        return "";
    }
    const date = new Date(localValue);
    if (Number.isNaN(date.getTime())) {
        return "";
    }

    const year = date.getFullYear();
    const month = pad2(date.getMonth() + 1);
    const day = pad2(date.getDate());
    const hours = pad2(date.getHours());
    const minutes = pad2(date.getMinutes());
    const seconds = pad2(date.getSeconds());

    const offsetMinutes = -date.getTimezoneOffset();
    const sign = offsetMinutes >= 0 ? "+" : "-";
    const abs = Math.abs(offsetMinutes);
    const offsetHours = pad2(Math.floor(abs / 60));
    const offsetRemainMinutes = pad2(abs % 60);

    return `${year}-${month}-${day}T${hours}:${minutes}:${seconds}${sign}${offsetHours}:${offsetRemainMinutes}`;
}

function formatDateTimeLocalValue(date) {
    return [
        date.getFullYear(),
        pad2(date.getMonth() + 1),
        pad2(date.getDate())
    ].join("-") + "T" + [
        pad2(date.getHours()),
        pad2(date.getMinutes())
    ].join(":");
}

function pad2(value) {
    return String(value).padStart(2, "0");
}

function addMinutes(date, minutes) {
    return new Date(date.getTime() + minutes * 60 * 1000);
}

function addDays(date, days) {
    return new Date(date.getTime() + days * 24 * 60 * 60 * 1000);
}

function startOfToday() {
    const now = new Date();
    now.setHours(0, 0, 0, 0);
    return now;
}

async function fetchJson(url, options = { method: "GET" }) {
    const response = await fetch(url, options);
    const contentType = response.headers.get("content-type") || "";
    if (!response.ok) {
        const body = contentType.includes("application/json")
            ? await response.json().catch(() => ({}))
            : await response.text().catch(() => "");
        const message = typeof body === "string"
            ? body
            : body.message || body.summary || `请求失败（HTTP ${response.status}）`;
        throw new Error(message || `请求失败（HTTP ${response.status}）`);
    }
    if (contentType.includes("application/json")) {
        return response.json();
    }
    throw new Error("后端返回的不是 JSON 数据");
}
