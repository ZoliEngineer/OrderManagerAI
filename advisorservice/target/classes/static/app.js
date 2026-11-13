'use strict';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ACCOUNT_KEY = 'advisor.accountId';

const el = {
  transcript: document.getElementById('transcript'),
  welcome: document.getElementById('welcome'),
  form: document.getElementById('composer'),
  input: document.getElementById('input'),
  send: document.getElementById('send'),
  accountId: document.getElementById('accountId'),
  newChat: document.getElementById('newChat'),
  authButton: document.getElementById('authButton'),
};

const state = {
  msal: null,
  scope: null,
  account: null,
  conversationId: null,
  busy: false,
};

// ---------- storage (per-viewer convenience only) ----------
function loadAccountId() {
  try { return localStorage.getItem(ACCOUNT_KEY) || ''; } catch { return ''; }
}
function saveAccountId(value) {
  try { localStorage.setItem(ACCOUNT_KEY, value); } catch { /* storage unavailable */ }
}

// ---------- auth ----------
async function initAuth() {
  const config = await (await fetch('config.json')).json();
  state.scope = config.scope;
  state.msal = new msal.PublicClientApplication({
    auth: {
      clientId: config.clientId,
      authority: `https://login.microsoftonline.com/${config.tenantId}`,
      redirectUri: window.location.origin + window.location.pathname,
    },
    cache: { cacheLocation: 'sessionStorage' },
  });
  await state.msal.initialize();
  const redirect = await state.msal.handleRedirectPromise();
  state.account = redirect?.account ?? state.msal.getActiveAccount() ?? state.msal.getAllAccounts()[0] ?? null;
  if (state.account) state.msal.setActiveAccount(state.account);
  renderAuth();
}

function renderAuth() {
  const signedIn = !!state.account;
  el.authButton.textContent = signedIn ? 'Sign out' : 'Sign in';
  el.authButton.title = signedIn ? state.account.username : '';
  el.input.disabled = !signedIn;
  el.input.placeholder = signedIn ? 'Ask your advisor…  (Enter to send, Shift+Enter for a new line)' : 'Sign in to chat with your advisor';
  updateSendState();
}

async function getToken() {
  const request = { scopes: [state.scope], account: state.account };
  try {
    return (await state.msal.acquireTokenSilent(request)).accessToken;
  } catch (e) {
    if (e instanceof msal.InteractionRequiredAuthError) {
      await state.msal.acquireTokenRedirect(request);
    }
    throw e;
  }
}

el.authButton.addEventListener('click', () => {
  if (state.account) {
    state.msal.logoutRedirect({ account: state.account });
  } else {
    state.msal.loginRedirect({ scopes: [state.scope] });
  }
});

// ---------- API ----------
async function api(path, body) {
  const token = await getToken();
  return fetch(path, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      Accept: 'text/event-stream, application/json',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function ensureConversation() {
  if (state.conversationId) return state.conversationId;
  const res = await api('/api/conversations');
  if (!res.ok) throw new Error(`Could not start a conversation (HTTP ${res.status})`);
  state.conversationId = (await res.json()).conversationId;
  return state.conversationId;
}

/** Reads a Server-Sent Events response body and calls onEvent(type, data) per event. */
async function readEvents(res, onEvent) {
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
    let boundary;
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      let type = 'message';
      const data = [];
      for (const line of raw.split('\n')) {
        if (line.startsWith('event:')) type = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (data.length) {
        let parsed;
        try { parsed = JSON.parse(data.join('\n')); } catch { parsed = data.join('\n'); }
        onEvent(type, parsed);
      }
    }
  }
}

// ---------- rendering ----------
function scrollToBottom() {
  el.transcript.scrollTop = el.transcript.scrollHeight;
}

function append(node) {
  el.welcome?.remove();
  el.transcript.appendChild(node);
  scrollToBottom();
  return node;
}

function h(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function renderMarkdown(target, markdown) {
  target.innerHTML = DOMPurify.sanitize(marked.parse(markdown, { breaks: false, gfm: true }));
  target.querySelectorAll('a').forEach(a => { a.target = '_blank'; a.rel = 'noopener noreferrer'; });
}

function addUserMessage(text, accountId) {
  const wrap = h('div', 'msg user');
  wrap.appendChild(h('div', 'bubble', text));
  if (accountId) wrap.appendChild(h('div', 'meta', `Account ${accountId}`));
  append(wrap);
}

function addNotice(message) {
  append(h('div', 'notice', message));
}

/** One assistant reply, built up from streamed events. */
class AssistantTurn {
  constructor() {
    this.root = append(h('div', 'msg assistant'));
    this.segment = null;
    this.thinking = null;
    this.activity = null;
    this.pendingRender = false;
  }

  textSegment() {
    if (!this.segment) {
      const bubble = h('div', 'bubble markdown cursor');
      this.root.appendChild(bubble);
      this.segment = { node: bubble, text: '' };
      this.activity = null;
    }
    return this.segment;
  }

  appendText(text) {
    const segment = this.textSegment();
    segment.text += text;
    if (!this.pendingRender) {
      this.pendingRender = true;
      requestAnimationFrame(() => {
        this.pendingRender = false;
        renderMarkdown(segment.node, segment.text);
        scrollToBottom();
      });
    }
  }

  appendThinking(text) {
    if (!this.thinking) {
      const details = h('details', 'thinking');
      details.appendChild(h('summary', null, 'Thinking'));
      const body = h('div');
      details.appendChild(body);
      this.root.insertBefore(details, this.root.firstChild);
      this.thinking = body;
    }
    this.thinking.textContent += text;
  }

  /** Tool activity closes the current text segment so later text appears after it, in order. */
  addActivity(data) {
    this.closeSegment();
    if (!this.activity) this.activity = this.root.appendChild(h('div', 'activity'));
    if (data.kind === 'web_results' && Array.isArray(data.sources)) {
      const list = h('ul', 'sources');
      for (const s of data.sources) {
        const li = h('li');
        const a = h('a', null, s.title || s.url);
        a.href = s.url;
        a.target = '_blank';
        a.rel = 'noopener noreferrer';
        li.appendChild(a);
        list.appendChild(li);
      }
      const details = h('details', 'thinking');
      details.appendChild(h('summary', null, `🔎 ${data.label}`));
      details.appendChild(list);
      this.root.appendChild(details);
      this.activity = null;
      return;
    }
    const icon = data.kind === 'web_search' ? '🔎' : data.status === 'error' ? '⚠️' : '🛠️';
    const chip = h('span', `chip ${data.status || ''}`, `${icon} ${data.label}`);
    chip.title = data.label;
    this.activity.appendChild(chip);
    scrollToBottom();
  }

  closeSegment() {
    if (this.segment) {
      renderMarkdown(this.segment.node, this.segment.text);
      this.segment.node.classList.remove('cursor');
      this.segment = null;
    }
  }

  addApproval(data) {
    this.closeSegment();
    this.root.appendChild(renderApproval(data));
    scrollToBottom();
  }

  finish() {
    this.closeSegment();
    if (!this.root.childElementCount) this.root.remove();
  }
}

function renderApproval(data) {
  const card = h('div', 'approval');
  const count = data.actions.length;
  card.appendChild(h('h3', null, count === 1 ? 'Approval needed' : `${count} actions need approval`));
  const expires = new Date(data.expiresAt);
  card.appendChild(h('p', 'hint', `Nothing runs until you decide. Expires at ${expires.toLocaleTimeString()}.`));

  const decisions = new Map();

  for (const action of data.actions) {
    const row = h('div', 'action');
    row.appendChild(h('div', 'summary', action.summary));
    row.appendChild(h('div', 'tool', action.tool));
    const controls = h('div', 'row');
    const reason = h('input', 'reason');
    reason.placeholder = 'Reason (optional, sent with a rejection)';
    reason.maxLength = 500;
    const approve = h('button', 'approve', 'Approve');
    const reject = h('button', 'reject', 'Reject');
    approve.type = reject.type = 'button';
    controls.append(approve, reject, reason);
    row.appendChild(controls);
    card.appendChild(row);

    const decide = approved => {
      decisions.set(action.toolUseId, { toolUseId: action.toolUseId, approved, reason: approved ? null : reason.value.trim() || null });
      controls.replaceChildren(h('span', `decision ${approved ? 'approved' : 'rejected'}`,
        approved ? '✓ Approved' : `✕ Rejected${reason.value.trim() ? ` — ${reason.value.trim()}` : ''}`));
      if (decisions.size === data.actions.length) submitDecisions([...decisions.values()]);
    };
    approve.addEventListener('click', () => decide(true));
    reject.addEventListener('click', () => decide(false));
  }
  return card;
}

// ---------- turns ----------
async function runTurn(path, body) {
  setBusy(true);
  const turn = new AssistantTurn();
  try {
    await ensureConversation();
    let res = await api(path(state.conversationId), body);
    if (res.status === 404) {
      // The server restarted and lost the conversation: start a fresh one.
      state.conversationId = null;
      await ensureConversation();
      addNotice('The previous conversation was no longer available on the server, so a new one was started.');
      res = await api(path(state.conversationId), body);
    }
    if (!res.ok) {
      let message = `Request failed (HTTP ${res.status})`;
      try { const err = await res.json(); if (err.message || err.detail) message = err.message || err.detail; } catch { /* not JSON */ }
      if (res.status === 409) message = 'The advisor is still working on the previous message.';
      throw new Error(message);
    }
    await readEvents(res, (type, data) => {
      switch (type) {
        case 'text_delta': turn.appendText(data.text); break;
        case 'thinking': turn.appendThinking(data.text); break;
        case 'tool_activity': turn.addActivity(data); break;
        case 'approval_required': turn.addApproval(data); break;
        case 'error': turn.closeSegment(); turn.root.appendChild(h('div', 'notice', data.message)); break;
        default: break;
      }
    });
  } catch (e) {
    turn.root.appendChild(h('div', 'notice', e.message || String(e)));
  } finally {
    turn.finish();
    setBusy(false);
  }
}

function sendMessage(text) {
  const accountId = el.accountId.value.trim();
  if (accountId && !UUID_RE.test(accountId)) {
    el.accountId.classList.add('invalid');
    el.accountId.focus();
    return;
  }
  addUserMessage(text, accountId);
  el.input.value = '';
  autoGrow();
  runTurn(id => `/api/conversations/${id}/messages`, { text, accountId: accountId || null });
}

function submitDecisions(decisions) {
  runTurn(id => `/api/conversations/${id}/approvals`, { decisions });
}

function setBusy(busy) {
  state.busy = busy;
  updateSendState();
  document.querySelectorAll('.approval button').forEach(b => { b.disabled = busy; });
  document.querySelectorAll('.suggestion').forEach(b => { b.disabled = busy || !state.account; });
}

function updateSendState() {
  el.send.disabled = state.busy || !state.account || !el.input.value.trim();
}

function autoGrow() {
  el.input.style.height = 'auto';
  el.input.style.height = `${Math.min(el.input.scrollHeight, 200)}px`;
}

// ---------- wiring ----------
el.form.addEventListener('submit', e => {
  e.preventDefault();
  const text = el.input.value.trim();
  if (text && !state.busy && state.account) sendMessage(text);
});
el.input.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    el.form.requestSubmit();
  }
});
el.input.addEventListener('input', () => { autoGrow(); updateSendState(); });
el.accountId.value = loadAccountId();
el.accountId.addEventListener('input', () => {
  const value = el.accountId.value.trim();
  el.accountId.classList.toggle('invalid', !!value && !UUID_RE.test(value));
  saveAccountId(value);
});
el.newChat.addEventListener('click', () => {
  if (state.busy) return;
  state.conversationId = null;
  el.transcript.replaceChildren();
  append(h('div', 'meta', 'New conversation started.'));
});
document.querySelectorAll('.suggestion').forEach(button => {
  button.addEventListener('click', () => { if (!state.busy && state.account) sendMessage(button.textContent); });
});

initAuth().catch(e => {
  console.error(e);
  addNotice(`Sign-in is not available: ${e.message || e}`);
});
