/*! Mobilecode workbench patch v0.7.2
 * 注入层前端补丁（不修改上游 bundle，升级可平移）：
 *  1. fetch 劫持：解新装用户"目录下拉死锁"（thread/list 为空时注入引导会话，
 *     __mc_welcome__ 替身的首条消息在后台真实 thread/start + turn/start）
 *  2. markdown + 代码高亮：MutationObserver 渲染 .message-text（marked + DOMPurify + hljs）
 *  3. 工具活动浮层：独立消费 SSE item/* 事件，展示命令执行过程与状态
 *  4. i18n 清理：上游俄语 aria-label 残留与核心文案中文化
 */
(function () {
  'use strict';
  if (window.__MC_PATCH__) return;
  window.__MC_PATCH__ = true;

  var WELCOME_ID = '__mc_welcome__';
  // HOME 是惰性的：App 端在 onPageFinished 注入 window.__MC_HOME__（晚于本脚本
  // 执行），因此每次使用时取值；缓存仅用于同会话稳定。
  var homeCache = localStorage.getItem('mc.home') || null;
  function currentHome() {
    if (window.__MC_HOME__) {
      if (window.__MC_HOME__ !== homeCache) {
        homeCache = window.__MC_HOME__;
        try { localStorage.setItem('mc.home', homeCache); } catch (e) { /* 隐私模式 */ }
      }
      return homeCache;
    }
    return homeCache || (/Android/.test(navigator.userAgent)
      ? '/data/user/0/com.codex.mobile/files/home'
      : '/tmp');
  }

  /* ───────────────────────── 1) fetch 劫持：目录死锁 ───────────────────────── */

  var realThread = localStorage.getItem('mc.lastThread') || null;

  function nowIso() { return new Date().toISOString(); }
  function nowSec() { return Math.floor(Date.now() / 1000); }

  function welcomeThread() {
    return {
      id: WELCOME_ID,
      cwd: currentHome(),
      title: 'Getting started',
      name: 'Getting started',
      preview: '',
      createdAt: nowSec(),
      updatedAt: nowSec(),
      createdAtIso: nowIso(),
      updatedAtIso: nowIso(),
      cliVersion: '',
      source: 'vscode',
      gitInfo: null,
      modelProvider: ''
    };
  }

  function rpcRes(obj, status) {
    return new Response(JSON.stringify(obj), {
      status: status || 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  var _fetch = window.fetch.bind(window);

  /* ────────── 图片附件（P1）：引擎只接受 {type:'localImage', path} ──────────
   * WebView 内无法直接落盘，由 Kotlin onShowFileChooser 选图后写入 cache 并把
   * 真实路径回调给 window.__MC_FILE_READY__；宿主浏览器环境无 Kotlin 时降级提示。
   */
  var pendingFiles = [];

  function drawPendingFiles() {
    var box = document.querySelector('#mc-attach-chips');
    if (!box) return;
    box.innerHTML = pendingFiles.map(function (f, i) {
      return '<span class="mc-chip">' + esc(f.name) +
        '<button data-i="' + i + '" aria-label="移除附件">×</button></span>';
    }).join('');
    Array.prototype.forEach.call(box.querySelectorAll('button'), function (b) {
      b.addEventListener('click', function () {
        pendingFiles.splice(parseInt(b.getAttribute('data-i'), 10), 1);
        drawPendingFiles();
      });
    });
    box.hidden = pendingFiles.length === 0;
  }

  function addPendingFile(name, path) {
    pendingFiles.push({ name: name, path: path });
    drawPendingFiles();
  }

  window.__MC_FILE_READY__ = function (path) {
    var name = String(path || '').split('/').pop() || 'image';
    addPendingFile(name, path);
    var hint = document.querySelector('#mc-attach-hint');
    if (hint) { hint.textContent = '已附加 ' + name; }
  };

  function buildComposerAttach() {
    if (!document.querySelector('.thread-composer-shell') ||
        document.querySelector('#mc-attach-btn')) return;
    var btn = document.createElement('button');
    btn.id = 'mc-attach-btn';
    btn.setAttribute('aria-label', '附加图片');
    btn.title = '附加图片';
    btn.innerHTML = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" ' +
      'stroke="currentColor" stroke-width="2" stroke-linecap="round">' +
      '<path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48"/>' +
      '</svg>';
    var input = document.createElement('input');
    input.id = 'mc-file-input';
    input.type = 'file';
    input.accept = 'image/*';
    input.multiple = true;
    input.hidden = true;
    var chips = document.createElement('div');
    chips.id = 'mc-attach-chips';
    chips.hidden = true;
    var hint = document.createElement('span');
    hint.id = 'mc-attach-hint';

    btn.addEventListener('click', function () {
      try {
        input.value = '';
        input.click();   // 触发 Kotlin WebChromeClient.onShowFileChooser
      } catch (e) { /* 忽略 */ }
    });
    input.addEventListener('change', function () {
      if (!window.__MC_BRIDGE__) {
        hint.textContent = '此环境不支持文件选择（需 App 端支持）';
        setTimeout(function () { hint.textContent = ''; }, 2500);
      }
      // 有 Kotlin bridge 时由 __MC_FILE_READY__ 回填真实路径
    });

    var shell = document.querySelector('.thread-composer-shell');
    var host = shell.parentNode;
    var wrap = document.createElement('div');
    wrap.id = 'mc-attach-wrap';
    wrap.appendChild(chips);
    var row = document.createElement('div');
    row.id = 'mc-attach-row';
    row.appendChild(btn);
    row.appendChild(hint);
    wrap.appendChild(row);
    host.insertBefore(wrap, shell);
    wrap.appendChild(input);
  }

  // app-server 冷启动竞态：dist-cli 先就绪、qemu 引擎初始化慢，首批 RPC 可能
  // 打出 502/503 且上游前端不重试（页面会卡在 Loading）—— patch 层自动重试。
  function fetchWithRetry(url, init, tries) {
    return _fetch(url, init).then(function (res) {
      if ((res.status === 502 || res.status === 503 || res.status === 504) &&
          tries > 0) {
        return new Promise(function (resolve) {
          setTimeout(resolve, 900);
        }).then(function () {
          return fetchWithRetry(url, init, tries - 1);
        });
      }
      return res;
    });
  }

  window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : (input && input.url) || String(input || '');
    var isRpc = url.indexOf('/codex-api/rpc') === 0;
    var method = (init && init.method) || 'GET';
    var req = null;
    if (isRpc && method === 'POST' && init && typeof init.body === 'string') {
      try { req = JSON.parse(init.body); } catch (e) { /* 非 JSON，放行 */ }
    }

    // 图片附件：引擎实测只接受 {type:'localImage', path}（所有 base64 变体
    // 一律被拒），路径由 Kotlin 侧选图后写入 —— 避免 WebView 内传 base64
    if (req && req.method === 'turn/start' && pendingFiles.length > 0) {
      var inArr = Array.isArray(req.params.input) ? req.params.input : [];
      pendingFiles.forEach(function (f) {
        if (f.path) inArr.push({ type: 'localImage', path: f.path });
      });
      req.params.input = inArr;
      init = Object.assign({}, init, { body: JSON.stringify(req) });
      if (window.__MC_DEBUG__) window.__MC_DEBUG__.lastTurnInput = inArr.slice();
      pendingFiles = [];
      drawPendingFiles();
    }

    // turn/start on 替身 → 先真实 thread/start，再以真实 id 转发
    if (req && req.method === 'turn/start' && req.params && req.params.threadId === WELCOME_ID) {
      var rpcInit = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: null };
      var startReal = realThread
        ? Promise.resolve({ id: realThread })
        : fetchWithRetry(url, Object.assign({}, rpcInit, {
            body: JSON.stringify({ jsonrpc: '2.0', id: req.id || 1, method: 'thread/start', params: { cwd: currentHome() } })
          }), 3).then(function (r) { return r.json(); }).then(function (j) {
            var t = j.result && j.result.thread;
            return t && t.id ? t : null;
          });
      return startReal.then(function (t) {
        if (!t) return rpcRes({ error: 'thread/start failed' }, 500);
        realThread = t.id;
        localStorage.setItem('mc.lastThread', t.id);
        req.params.threadId = t.id;
        return fetchWithRetry(url, Object.assign({}, rpcInit, {
          body: JSON.stringify(req)
        }), 2);
      });
    }

    // thread/resume 替身 → 点击引导会话时立即真实 thread/start（保证后续
    // turn 有真实落点），返回替身结构让前端正常路由进会话视图
    if (req && req.method === 'thread/resume' && req.params && req.params.threadId === WELCOME_ID) {
      if (!realThread) {
        return fetchWithRetry(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ jsonrpc: '2.0', id: req.id || 1, method: 'thread/start', params: { cwd: currentHome() } })
        }, 3).then(function (r) { return r.json(); }).then(function (j) {
          var t = j.result && j.result.thread;
          if (t && t.id) {
            realThread = t.id;
            localStorage.setItem('mc.lastThread', t.id);
          }
          return rpcRes({ result: { thread: welcomeThread() } });
        }).catch(function () { return rpcRes({ result: { thread: welcomeThread() } }); });
      }
      return Promise.resolve(rpcRes({ result: { thread: welcomeThread() } }));
    }

    // thread/read / turn/interrupt 的替身 id → 映射到真实 thread
    if (req && req.params && req.params.threadId === WELCOME_ID &&
        (req.method === 'thread/read' || req.method === 'turn/interrupt')) {
      if (req.method === 'thread/read' && !realThread) {
        return Promise.resolve(rpcRes({ result: { thread: welcomeThread(), turns: [] } }));
      }
      if (realThread) {
        req.params.threadId = realThread;
        init = Object.assign({}, init, { body: JSON.stringify(req) });
      }
    }

    return fetchWithRetry(url, init, 3).then(function (res) {
      if (!isRpc || !req) return res;
      var cloned;
      try { cloned = res.clone(); } catch (e) { return res; }
      return cloned.json().then(function (j) {
        // thread/list 空 → 注入引导会话（解锁目录下拉）
        if (req.method === 'thread/list' && j.result && Array.isArray(j.result.data) &&
            j.result.data.length === 0) {
          j.result.data.push(welcomeThread());
          return rpcRes(j);
        }
        return res;
      }).catch(function () { return res; });
    });
  };

  /* ───────────────────────── 2) markdown + 代码高亮 ───────────────────────── */

  var MD_HINT = /(```|^\s*#{1,6}\s|^\s*[-*]\s|^\s*\d+\.\s|\*\*|^\s*\|)/m;

  function looksLikeMarkdown(text) {
    return text && text.length > 24 && MD_HINT.test(text);
  }

  function renderMarkdown(elm) {
    // 只渲染 assistant 消息：用户消息永远原样显示（防止误改用户原意，
    // 如字面 **bold** 被替换为粗体；业界惯例同 ChatGPT/Claude）
    var roleLi = elm.closest ? elm.closest('li[data-role]') : null;
    if (roleLi && roleLi.getAttribute('data-role') !== 'assistant') return;
    var text = elm.textContent || '';
    // 幂等 + 流式重渲染：textContent 长度变化（Vue delta 追加）时重新渲染
    var prevLen = parseInt(elm.dataset.mcMdLen || '-1', 10);
    if (elm.dataset.mcMd === '1' && prevLen === text.length) return;
    if (!looksLikeMarkdown(text)) return;
    try {
      var raw = window.marked ? window.marked.parse(text) : null;
      if (!raw) return;
      var clean = window.DOMPurify ? window.DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } }) : raw;
      elm.innerHTML = clean;
      elm.classList.add('mc-md');
      elm.dataset.mcMd = '1';
      elm.dataset.mcMdLen = String(elm.textContent.length);
      // 链接加固：新窗口 + noopener（真机另有 Kotlin shouldOverrideUrlLoading 兜底）
      elm.querySelectorAll('a[href]').forEach(function (a) {
        a.setAttribute('target', '_blank');
        a.setAttribute('rel', 'noopener noreferrer nofollow');
      });
      if (window.hljs) {
        elm.querySelectorAll('pre code').forEach(function (b) {
          try { window.hljs.highlightElement(b); } catch (e) { /* 忽略高亮失败 */ }
        });
      }
    } catch (e) { /* 渲染失败保持原文 */ }
  }

  var i18nMap = [
    ['Let\'s build', '开始构建'],
    ['Type a message...', '输入消息…'],
    ['Choose folder', '选择目录'],
    ['Threads', '会话'],
    ['Send message', '发送'],
    ['Stop', '停止'],
    ['Стоп', '停止'],
    ['New thread', '新会话'],
    ['Collapse sidebar', '收起侧栏'],
    ['Start new thread', '新建会话'],
    ['Enable 4s refresh', '开启 4s 自动刷新'],
    ['Disable 4s refresh', '关闭 4s 自动刷新'],
    ['Select a thread to send a message', '先选择一个会话再发送消息'],
    ['Resize sidebar', '调整侧栏宽度']
  ];

  function polish(elms) {
    for (var i = 0; i < elms.length; i++) {
      var n = elms[i];
      if (n.nodeType !== 1) continue;
      // aria-label 清理/中文化
      var aria = n.getAttribute && n.getAttribute('aria-label');
      if (aria) {
        for (var k = 0; k < i18nMap.length; k++) {
          if (aria === i18nMap[k][0]) { n.setAttribute('aria-label', i18nMap[k][1]); break; }
        }
      }
      // placeholder 中文化
      var ph = n.getAttribute && n.getAttribute('placeholder');
      if (ph) {
        for (var k3 = 0; k3 < i18nMap.length; k3++) {
          if (ph === i18nMap[k3][0]) { n.setAttribute('placeholder', i18nMap[k3][1]); break; }
        }
      }
      // 纯文本节点中文化
      if (n.childNodes && n.childNodes.length === 1 && n.childNodes[0].nodeType === 3) {
        var t = n.textContent;
        for (var k2 = 0; k2 < i18nMap.length; k2++) {
          if (t === i18nMap[k2][0]) { n.textContent = i18nMap[k2][1]; break; }
        }
      }
    }
  }

  var pending = false;
  function scanDom() {
    if (pending) return;
    pending = true;
    requestAnimationFrame(function () {
      pending = false;
      try {
        // composer 在会话切换时会被 Vue 重建 —— 附件区随扫帧自愈重新挂载
        if (document.querySelector('.thread-composer-shell') && !document.querySelector('#mc-attach-btn')) {
          buildComposerAttach();
        }
        document.querySelectorAll('.message-text').forEach(renderMarkdown);
        polish(document.querySelectorAll('button,[aria-label],p,h1,h2,h3,span,input,textarea'));
      } catch (e) { /* 单轮失败不致命 */ }
    });
  }

  function startObserver() {
    var mo = new MutationObserver(scanDom);
    mo.observe(document.body, { childList: true, subtree: true, characterData: true });
    scanDom();
  }

  /* ───────────────────────── 3) 工具活动浮层 ───────────────────────── */

  var activity = [];
  var actPanelOpen = false;
  var actTab = 'activity';          // 'activity' | 'resource'
  var onlyThisThread = true;
  var currentThreadId = null;
  var usage = null;                 // thread/tokenUsage/updated 最新值
  var resources = { skills: [], mcp: [], loaded: false };

  function buildActivityPanel() {
    var root = document.createElement('div');
    root.id = 'mc-activity';
    root.innerHTML =
      '<button id="mc-activity-badge" aria-label="工具活动">' +
      '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">' +
      '<polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>' +
      '<span id="mc-activity-count">0</span></button>' +
      '<div id="mc-activity-list" hidden>' +
      '<div id="mc-act-bar">' +
      '<button data-tab="activity" class="mc-act-tab mc-tab-on">活动</button>' +
      '<button data-tab="resource" class="mc-act-tab">资源</button>' +
      '<label id="mc-act-filter"><input type="checkbox" checked> 仅本会话</label>' +
      '<button id="mc-act-close" aria-label="关闭">×</button>' +
      '</div>' +
      '<div id="mc-usage-line"></div>' +
      '<div id="mc-act-body"></div>' +
      '</div>';
    document.body.appendChild(root);
    root.querySelector('#mc-activity-badge').addEventListener('click', function () {
      actPanelOpen = !actPanelOpen;
      root.querySelector('#mc-activity-list').hidden = !actPanelOpen;
      if (actPanelOpen) drawActivity();
    });
    root.querySelector('#mc-act-close').addEventListener('click', function () {
      actPanelOpen = false;
      root.querySelector('#mc-activity-list').hidden = true;
    });
    Array.prototype.forEach.call(root.querySelectorAll('.mc-act-tab'), function (b) {
      b.addEventListener('click', function () {
        actTab = b.getAttribute('data-tab');
        Array.prototype.forEach.call(root.querySelectorAll('.mc-act-tab'), function (x) {
          x.classList.toggle('mc-tab-on', x.getAttribute('data-tab') === actTab);
        });
        drawActivity();
        if (actTab === 'resource') loadResources();
      });
    });
    var cb = root.querySelector('#mc-act-filter input');
    cb.addEventListener('change', function () {
      onlyThisThread = cb.checked;
      drawActivity();
    });
    return root;
  }

  /* Skills / MCP 只读面板：引擎 RPC 早已具备，前端此前零入口 */
  function loadResources() {
    if (resources.loaded) return;
    resources.loaded = true;
    _fetch('/codex-api/rpc', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method: 'skills/list', params: { cwd: currentHome() } })
    }).then(function (r) { return r.json(); }).then(function (j) {
      var groups = (j.result && j.result.data) || [];
      resources.skills = [];
      groups.forEach(function (g) {
        (g.skills || []).forEach(function (s) {
          resources.skills.push({
            name: s.name || '?',
            desc: s.shortDescription || s.description || ''
          });
        });
      });
      drawActivity();
    }).catch(function () { /* 拉取失败保持空 */ });
    _fetch('/codex-api/rpc', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id: Date.now() + 1, method: 'mcpServerStatus/list', params: {} })
    }).then(function (r) { return r.json(); }).then(function (j) {
      resources.mcp = (j.result && j.result.data) || [];
      drawActivity();
    }).catch(function () { /* 忽略 */ });
  }

  function drawUsage() {
    var line = document.querySelector('#mc-usage-line');
    if (!line) return;
    if (!usage || !usage.modelContextWindow) {
      line.textContent = '';
      line.hidden = true;
      return;
    }
    var used = usage.totalTokens || 0;
    var win = usage.modelContextWindow || 0;
    var pct = win ? Math.min(100, Math.round(used / win * 1000) / 10) : 0;
    line.hidden = false;
    line.className = pct > 80 ? 'mc-usage-hot' : '';
    line.innerHTML = '上下文 ' + fmtNum(used) + ' / ' + fmtNum(win) + '（' + pct + '%）' +
      '<span class="mc-usage-bar"><i style="width:' + pct + '%"></i></span>';
  }

  function fmtNum(n) {
    return n >= 10000 ? (Math.round(n / 100) / 10) + 'k' : String(n);
  }

  function drawResources() {
    var html = '';
    html += '<div class="mc-res-title">Skills（' + resources.skills.length + '）</div>';
    html += resources.skills.length
      ? resources.skills.map(function (s) {
          return '<div class="mc-res-item"><code>' + esc(s.name) + '</code>' +
            '<div class="mc-res-desc">' + esc(s.desc.slice(0, 90)) + '</div></div>';
        }).join('')
      : '<div class="mc-act-empty">加载中或暂无可用 skill</div>';
    html += '<div class="mc-res-title">MCP 服务器（' + resources.mcp.length + '）</div>';
    html += resources.mcp.length
      ? resources.mcp.map(function (m) {
          return '<div class="mc-res-item"><code>' + esc(m.name || '?') + '</code>' +
            '<div class="mc-act-meta">' + esc(String(m.status || '')) + '</div></div>';
        }).join('')
      : '<div class="mc-act-empty">未配置 MCP 服务器（config.toml 可添加）</div>';
    return html;
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function drawActivity() {
    var list = document.querySelector('#mc-act-body');
    if (!list) return;
    drawUsage();
    if (actTab === 'resource') {
      list.innerHTML = drawResources();
      return;
    }
    var items = activity.slice(-50).filter(function (a) {
      return !onlyThisThread || !currentThreadId || !a.threadId || a.threadId === currentThreadId;
    });
    var html = items.reverse().slice(0, 20).map(function (a) {
      var cls = 'mc-act ' + (a.status === 'completed' ? 'mc-act-ok'
        : a.status === 'failed' ? 'mc-act-bad' : 'mc-act-run');
      var head = '<div class="mc-act-cmd"><code>' + esc(a.command) + '</code></div>';
      var meta = '<div class="mc-act-meta">' + (a.status === 'completed'
        ? '✓ ' + (a.exitCode === 0 || a.exitCode == null ? '完成' : '退出码 ' + a.exitCode)
        : a.status === 'failed' ? '✗ 失败' : '⏳ 执行中…') + '</div>';
      var out = a.output ? '<pre class="mc-act-out">' + esc(String(a.output).slice(0, 800)) + '</pre>' : '';
      return '<div class="' + cls + '">' + head + meta + out + '</div>';
    }).join('');
    list.innerHTML = html || '<div class="mc-act-empty">暂无工具活动</div>';
  }

  function pushActivity(a) {
    var found = null;
    for (var i = activity.length - 1; i >= 0; i--) {
      if (activity[i].id === a.id) { found = activity[i]; break; }
    }
    if (found) Object.assign(found, a);
    else { activity.push(a); if (activity.length > 50) activity.shift(); }
    var badge = document.querySelector('#mc-activity-count');
    if (badge) {
      var running = activity.filter(function (x) { return x.status === 'inProgress'; }).length;
      badge.textContent = String(running || activity.length);
    }
    if (actPanelOpen) drawActivity();
  }

  function startSse() {
    var es = new EventSource('/codex-api/events');
    var turnActive = false;   // turn/started → turn/completed 之间
    var turnGotReply = false; // 本轮是否收到过 agentMessage
    es.onmessage = function (ev) {
      var d;
      try { d = JSON.parse(ev.data); } catch (e) { return; }
      var m = d.method || '';
      var p = d.params || {};
      if (p.threadId) currentThreadId = p.threadId;
      var it = p.item || null;
      if (m === 'turn/started') {
        turnActive = true;
        turnGotReply = false;
      } else if ((m === 'item/started' || m === 'item/completed') && it &&
          it.type === 'commandExecution') {
        pushActivity({
          id: it.id,
          threadId: p.threadId || null,
          command: it.command,
          status: it.status,
          exitCode: it.exitCode,
          output: it.aggregatedOutput || ''
        });
      } else if (m === 'item/agentMessage/delta') {
        turnGotReply = true;
        pushActivity({ id: '__stream__', threadId: p.threadId || null, command: '（模型回复生成中…）', status: 'inProgress' });
        setTimeout(function () { pushActivity({ id: '__stream__', status: 'done', command: '（回复完成）' }); }, 2500);
      } else if ((m === 'item/completed') && it && it.type === 'agentMessage' && it.text) {
        turnGotReply = true;
      } else if (m === 'thread/tokenUsage/updated' && p.tokenUsage) {
        // 上下文占用：engine 每个 turn 推送（含 modelContextWindow），
        // 不依赖 ChatGPT 账号登录（account/rateLimits/read 需登录才可用）
        var tu = p.tokenUsage;
        usage = {
          totalTokens: (tu.total && tu.total.totalTokens) || 0,
          inputTokens: (tu.total && tu.total.inputTokens) || 0,
          outputTokens: (tu.total && tu.total.outputTokens) || 0,
          modelContextWindow: tu.modelContextWindow || 0
        };
        drawUsage();
      } else if (m === 'turn/completed') {
        pushActivity({ id: '__stream__', status: 'done', command: '（回合完成）' });
        // 上游前端不消费任何失败事件（bundle 逆向 0 处 turn/failed 处理）：
        // 引擎 StreamErrorEvent/TurnError 后 UI 完全静默，这里做兜底提示
        var failHint = p.error || p.failure || p.errorMessage ||
          (p.turn && (p.turn.error || p.turn.failure || p.turn.status === 'failed'));
        if (failHint) {
          showBanner('回合执行失败：' + String(failHint).slice(0, 120), 'bad');
        } else if (turnActive && !turnGotReply) {
          showBanner('回合已结束，但未收到任何回复——请检查网络连接与 API Key 是否有效', 'bad');
        }
        turnActive = false;
      }
    };
    es.onerror = function () {
      if (es.readyState === EventSource.CLOSED) {
        showBanner('与本地服务连接中断，正在重连…（若长时间无响应请重启 App）', 'warn');
      }
    };
    es.onopen = function () {
      hideBanner('conn');
    };
  }

  /* ───────────────────────── 全局提示条（错误/断连反馈） ───────────────────────── */

  function buildBanner() {
    var b = document.createElement('div');
    b.id = 'mc-banner';
    b.hidden = true;
    b.innerHTML = '<span id="mc-banner-text"></span>' +
      '<button id="mc-banner-close" aria-label="关闭">×</button>';
    document.body.appendChild(b);
    b.querySelector('#mc-banner-close').addEventListener('click', function () {
      b.hidden = true;
    });
  }

  function showBanner(text, kind, key) {
    var b = document.querySelector('#mc-banner');
    if (!b) buildBanner();
    b = document.querySelector('#mc-banner');
    b.className = kind === 'bad' ? 'mc-banner-bad' : 'mc-banner-warn';
    b.dataset.key = key || '';
    b.querySelector('#mc-banner-text').textContent = text;
    b.hidden = false;
  }

  function hideBanner(key) {
    var b = document.querySelector('#mc-banner');
    if (b && b.hidden !== true && (!key || b.dataset.key === key)) b.hidden = true;
  }

  /* ───────────────────────── 移动端导航（汉堡按钮） ───────────────────────── */

  function buildNav() {
    var btn = document.createElement('button');
    btn.id = 'mc-nav-btn';
    btn.setAttribute('aria-label', '切换侧栏');
    btn.innerHTML =
      '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
      'stroke-width="2" stroke-linecap="round"><line x1="3" y1="6" x2="21" y2="6"/>' +
      '<line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>';
    var backdrop = document.createElement('div');
    backdrop.id = 'mc-nav-backdrop';
    function toggle(force) {
      var open = typeof force === 'boolean' ? force : !document.body.classList.contains('mc-nav-open');
      document.body.classList.toggle('mc-nav-open', open);
    }
    btn.addEventListener('click', function () { toggle(); });
    backdrop.addEventListener('click', function () { toggle(false); });
    document.body.appendChild(btn);
    document.body.appendChild(backdrop);
  }

  /* ───────────────────────── 启动 ───────────────────────── */

  function boot() {
    // viewport 适配刘海屏
    var vp = document.querySelector('meta[name="viewport"]');
    if (vp && vp.getAttribute('content').indexOf('viewport-fit') === -1) {
      vp.setAttribute('content', vp.getAttribute('content') + ', viewport-fit=cover');
    }
    buildNav();
    buildActivityPanel();
    buildBanner();
    buildComposerAttach();
    startObserver();
    startSse();
    // 跨断点清抽屉状态：移动端开的抽屉在切回桌面（转屏/分屏）时残留
    // 会变成全屏遮罩且汉堡已隐藏，无入口关闭
    var mqMobile = window.matchMedia('(max-width: 767px)');
    function onBreakpoint(e) {
      if (!e.matches) document.body.classList.remove('mc-nav-open');
    }
    if (mqMobile.addEventListener) mqMobile.addEventListener('change', onBreakpoint);
    else if (mqMobile.addListener) mqMobile.addListener(onBreakpoint);
  }

  // 调试/诊断句柄（App 端诊断中心或远程排查用）
  window.__MC_DEBUG__ = {
    showBanner: showBanner,
    hideBanner: hideBanner,
    pushActivity: pushActivity,
    attach: function (name, path) { addPendingFile(name, path); },
    resources: function () { loadResources(); }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
