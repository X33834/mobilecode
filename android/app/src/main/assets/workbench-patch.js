/*! Mobilecode workbench patch v0.7.0
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
    if (elm.dataset.mcMd === '1') return;
    var text = elm.textContent || '';
    if (!looksLikeMarkdown(text)) return;
    try {
      var raw = window.marked ? window.marked.parse(text) : null;
      if (!raw) return;
      var clean = window.DOMPurify ? window.DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } }) : raw;
      elm.innerHTML = clean;
      elm.classList.add('mc-md');
      elm.dataset.mcMd = '1';
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

  function buildActivityPanel() {
    var root = document.createElement('div');
    root.id = 'mc-activity';
    root.innerHTML =
      '<button id="mc-activity-badge" aria-label="工具活动">' +
      '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">' +
      '<polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>' +
      '<span id="mc-activity-count">0</span></button>' +
      '<div id="mc-activity-list" hidden></div>';
    document.body.appendChild(root);
    root.querySelector('#mc-activity-badge').addEventListener('click', function () {
      actPanelOpen = !actPanelOpen;
      root.querySelector('#mc-activity-list').hidden = !actPanelOpen;
      if (actPanelOpen) drawActivity();
    });
    return root;
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function drawActivity() {
    var list = document.querySelector('#mc-activity-list');
    if (!list) return;
    var html = activity.slice(-20).reverse().map(function (a) {
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
    es.onmessage = function (ev) {
      var d;
      try { d = JSON.parse(ev.data); } catch (e) { return; }
      var m = d.method || '';
      var it = (d.params && d.params.item) || null;
      if ((m === 'item/started' || m === 'item/completed') && it &&
          it.type === 'commandExecution') {
        pushActivity({
          id: it.id,
          command: it.command,
          status: it.status,
          exitCode: it.exitCode,
          output: it.aggregatedOutput || ''
        });
      } else if (m === 'item/agentMessage/delta') {
        pushActivity({ id: '__stream__', command: '（模型回复生成中…）', status: 'inProgress' });
        var t = setTimeout(function () { pushActivity({ id: '__stream__', status: 'done', command: '（回复完成）' }); }, 2500);
      } else if (m === 'turn/completed') {
        pushActivity({ id: '__stream__', status: 'done', command: '（回合完成）' });
      }
    };
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
    startObserver();
    startSse();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
