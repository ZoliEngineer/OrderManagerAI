// @ts-check
// Plain JavaScript, type-checked by TypeScript through JSDoc annotations (see advisorservice/tsconfig.json).
// The browser runs this file as written; `npm run typecheck` in advisorservice/ checks it in strict mode.
'use strict';

// ---------- server contract (mirrors the Java side) ----------

/**
 * Public sign-in settings from `GET /config.json` (`AdvisorProperties.Ui`).
 * @typedef {object} UiConfig
 * @property {string} authority
 * @property {string} clientId
 * @property {string} scope
 * @property {string | null} logoutUrl
 */

/**
 * @typedef {object} Source
 * @property {string} title
 * @property {string} url
 */

/**
 * `tool_activity` payloads, discriminated on `kind` (built in `AdvisorAgent.activity` / `emitWebSearchActivity`).
 * @typedef {{ kind: 'tool', tool: string, label: string, status: 'ok' | 'error' }
 *   | { kind: 'web_search', tool: 'web_search', label: string }
 *   | { kind: 'web_results', tool: 'web_search', label: string, sources: Source[] }} ToolActivity
 */

/**
 * One tool call waiting for the user's decision (`AdvisorAgent.approvalEvent`).
 * @typedef {object} PendingAction
 * @property {string} toolUseId
 * @property {string} tool
 * @property {string} summary
 * @property {Record<string, unknown>} input
 */

/**
 * @typedef {object} ApprovalRequired
 * @property {string} expiresAt ISO-8601 instant
 * @property {PendingAction[]} actions
 */

/**
 * One Server-Sent Event, discriminated on `type` (the SSE `event:` field, see `AgentEventSink`).
 * Narrowing on `type` gives the matching `data` shape in each `case`.
 * @typedef {{ type: 'text_delta', data: { text: string } }
 *   | { type: 'thinking', data: { text: string } }
 *   | { type: 'tool_activity', data: ToolActivity }
 *   | { type: 'approval_required', data: ApprovalRequired }
 *   | { type: 'error', data: { message: string } }
 *   | { type: 'done', data: { awaitingApproval: boolean } }} AdvisorEvent
 */

/**
 * Request bodies (`ChatRequest`, `ApprovalDecision`, `ApprovalRequest`).
 * @typedef {{ text: string, accountId: string | null }} ChatRequest
 * @typedef {{ toolUseId: string, approved: boolean, reason: string | null }} Decision
 * @typedef {{ decisions: Decision[] }} ApprovalRequest
 */

/** @typedef {import('oidc-client-ts').UserManager} UserManager */
/** @typedef {import('oidc-client-ts').User} User */

// ---------- page ----------

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ACCOUNT_KEY = 'advisor.accountId';

/**
 * Looks up an element and checks its type at runtime, so the result needs no cast or null check.
 * `T` is inferred from the constructor: `byId('input', HTMLTextAreaElement)` is an `HTMLTextAreaElement`.
 * @template {HTMLElement} T
 * @param {string} id
 * @param {new () => T} type
 * @returns {T}
 */
function byId(id, type) {
  const node = document.getElementById(id);
  if (!(node instanceof type)) throw new Error(`#${id} is missing or is not a ${type.name}`);
  return node;
}

const el = {
  transcript: byId('transcript', HTMLElement),
  welcome: byId('welcome', HTMLElement),
  form: byId('composer', HTMLFormElement),
  input: byId('input', HTMLTextAreaElement),
  send: byId('send', HTMLButtonElement),
  accountId: byId('accountId', HTMLInputElement),
  newChat: byId('newChat', HTMLButtonElement),
  authButton: byId('authButton', HTMLButtonElement),
};

/**
 * @type {{
 *   userManager: UserManager | null,
 *   logoutUrl: string | null,
 *   user: User | null,
 *   conversationId: string | null,
 *   busy: boolean,
 * }}
 */
const state = {
  userManager: null,
  logoutUrl: null,
  user: null,
  conversationId: null,
  busy: false,
};

/**
 * `catch` gives `unknown`: anything can be thrown in JavaScript. Narrow it instead of assuming `Error`.
 * @param {unknown} err
 * @returns {string}
 */
function errorMessage(err) {
  return err instanceof Error ? err.message : String(err);
}

// ---------- storage (per-viewer convenience only) ----------
/** @returns {string} */
function loadAccountId() {
  try { return localStorage.getItem(ACCOUNT_KEY) ?? ''; } catch { return ''; }
}
/** @param {string} value */
function saveAccountId(value) {
  try { localStorage.setItem(ACCOUNT_KEY, value); } catch { /* storage unavailable */ }
}

// ---------- auth (generic OIDC: Cognito, Entra ID, ...) ----------
/** @returns {UserManager} */
function userManager() {
  if (!state.userManager) throw new Error('Sign-in is not initialised yet');
  return state.userManager;
}

async function initAuth() {
  const res = await fetch('config.json');
  if (!res.ok) throw new Error(`config.json returned HTTP ${res.status}`);
  // Trust boundary: JSON is cast to the server contract, not validated.
  const config = /** @type {UiConfig} */ (await res.json());

  state.logoutUrl = config.logoutUrl || null;
  const manager = new oidc.UserManager({
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: window.location.origin + window.location.pathname,
    response_type: 'code',
    scope: config.scope,
    userStore: new oidc.WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: false,
  });
  state.userManager = manager;

  const params = new URLSearchParams(window.location.search);
  if (params.has('code') && params.has('state')) {
    try {
      state.user = await manager.signinRedirectCallback();
    } catch (e) {
      console.error('Sign-in failed', e);
    }
    window.history.replaceState({}, document.title, window.location.pathname);
  } else {
    state.user = await manager.getUser();
  }
  renderAuth();
}

/** @param {User} user */
function displayName(user) {
  const { email, sub } = user.profile;
  const username = user.profile['cognito:username'];
  return email ?? (typeof username === 'string' ? username : sub);
}

function renderAuth() {
  // An expired access token is refreshed (or re-login started) by getToken().
  const user = state.user;
  el.authButton.textContent = user ? 'Sign out' : 'Sign in';
  el.authButton.title = user ? displayName(user) : '';
  el.input.disabled = !user;
  el.input.placeholder = user
    ? 'Ask your advisor…  (Enter to send, Shift+Enter for a new line)'
    : 'Sign in to chat with your advisor';
  updateSendState();
}

/** @returns {Promise<string>} */
async function getToken() {
  const manager = userManager();
  let user = await manager.getUser();
  if (!user || user.expired) {
    try {
      user = await manager.signinSilent();
    } catch (e) {
      await manager.signinRedirect();
      throw e;
    }
  }
  // signinSilent() resolves to null when there is no session to renew.
  if (!user) {
    await manager.signinRedirect();
    throw new Error('Your session has ended. Redirecting to sign in…');
  }
  state.user = user;
  return user.access_token;
}

el.authButton.addEventListener('click', async () => {
  const manager = userManager();
  if (state.user) {
    await manager.removeUser();
    state.user = null;
    if (state.logoutUrl) {
      window.location.assign(state.logoutUrl);
    } else {
      renderAuth();
    }
  } else {
    await manager.signinRedirect();
  }
});

// ---------- API ----------
// The page lives under a context path (/advisor/), so API calls are resolved against the page's directory.
const BASE = window.location.pathname.replace(/[^/]*$/, '');

/**
 * @param {string} path
 * @param {ChatRequest | ApprovalRequest} [body]
 * @returns {Promise<Response>}
 */
async function api(path, body) {
  const token = await getToken();
  return fetch(BASE + path.replace(/^\//, ''), {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      Accept: 'text/event-stream, application/json',
    },
    body: body === undefined ? null : JSON.stringify(body),
  });
}

/** @returns {Promise<string>} */
async function ensureConversation() {
  if (state.conversationId) return state.conversationId;
  const res = await api('/api/conversations');
  if (!res.ok) throw new Error(`Could not start a conversation (HTTP ${res.status})`);
  const { conversationId } = /** @type {{ conversationId: string }} */ (await res.json());
  state.conversationId = conversationId;
  return conversationId;
}

/**
 * @param {Response} res
 * @returns {Promise<string>}
 */
async function failureMessage(res) {
  if (res.status === 409) return 'The advisor is still working on the previous message.';
  try {
    const err = /** @type {{ message?: string, detail?: string }} */ (await res.json());
    return err.message || err.detail || `Request failed (HTTP ${res.status})`;
  } catch {
    return `Request failed (HTTP ${res.status})`; // not JSON
  }
}

/**
 * Reads a Server-Sent Events response body and yields one typed event at a time.
 * @param {Response} res
 * @returns {AsyncGenerator<AdvisorEvent, void, undefined>}
 */
async function* readEvents(res) {
  if (!res.body) throw new Error('The response has no body to stream');
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
      /** @type {string[]} */
      const data = [];
      for (const line of raw.split('\n')) {
        if (line.startsWith('event:')) type = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (!data.length) continue;
      try {
        // Trust boundary: the event name and its JSON payload are cast to the server contract, not validated.
        yield /** @type {AdvisorEvent} */ ({ type, data: JSON.parse(data.join('\n')) });
      } catch (e) {
        if (!(e instanceof SyntaxError)) throw e;
        console.warn('Ignoring a non-JSON event', type);
      }
    }
  }
}

// ---------- rendering ----------
function scrollToBottom() {
  el.transcript.scrollTop = el.transcript.scrollHeight;
}

/**
 * @template {Node} T
 * @param {T} node
 * @returns {T}
 */
function append(node) {
  el.welcome.remove();
  el.transcript.appendChild(node);
  scrollToBottom();
  return node;
}

/**
 * Creates an element. The tag name picks the return type: `h('input')` is an `HTMLInputElement`.
 * @template {keyof HTMLElementTagNameMap} K
 * @param {K} tag
 * @param {string | null} [className]
 * @param {string} [text]
 * @returns {HTMLElementTagNameMap[K]}
 */
function h(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

/**
 * @param {HTMLElement} target
 * @param {string} markdown
 */
function renderMarkdown(target, markdown) {
  // marked's types return `string | Promise<string>`; it is only a Promise when `async: true` is set.
  const html = marked.parse(markdown, { breaks: false, gfm: true, async: false });
  if (typeof html !== 'string') throw new Error('marked returned a Promise despite async: false');
  target.innerHTML = DOMPurify.sanitize(html);
  for (const a of target.querySelectorAll('a')) {
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
  }
}

/**
 * @param {string} text
 * @param {string} accountId
 */
function addUserMessage(text, accountId) {
  const wrap = h('div', 'msg user');
  wrap.appendChild(h('div', 'bubble', text));
  if (accountId) wrap.appendChild(h('div', 'meta', `Account ${accountId}`));
  append(wrap);
}

/** @param {string} message */
function addNotice(message) {
  append(h('div', 'notice', message));
}

/** One assistant reply, built up from streamed events. */
class AssistantTurn {
  constructor() {
    this.root = append(h('div', 'msg assistant'));
    /** @type {{ node: HTMLDivElement, text: string } | null} */
    this.segment = null;
    /** @type {HTMLDivElement | null} */
    this.thinking = null;
    /** @type {HTMLDivElement | null} */
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

  /** @param {string} text */
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

  /** @param {string} text */
  appendThinking(text) {
    if (!this.thinking) {
      const details = h('details', 'thinking');
      details.appendChild(h('summary', null, 'Thinking'));
      this.thinking = details.appendChild(h('div'));
      this.root.insertBefore(details, this.root.firstChild);
    }
    this.thinking.textContent += text;
  }

  /**
   * Tool activity closes the current text segment so later text appears after it, in order.
   * @param {ToolActivity} data
   */
  addActivity(data) {
    this.closeSegment();
    if (data.kind === 'web_results') {
      // Narrowed: only this variant has `sources`.
      const list = h('ul', 'sources');
      for (const s of data.sources) {
        const a = h('a', null, s.title || s.url);
        a.href = s.url;
        a.target = '_blank';
        a.rel = 'noopener noreferrer';
        list.appendChild(h('li')).appendChild(a);
      }
      const details = h('details', 'thinking');
      details.appendChild(h('summary', null, `🔎 ${data.label}`));
      details.appendChild(list);
      this.root.appendChild(details);
      this.activity = null;
      return;
    }
    const status = data.kind === 'tool' ? data.status : '';
    const icon = data.kind === 'web_search' ? '🔎' : status === 'error' ? '⚠️' : '🛠️';
    const chip = h('span', `chip ${status}`, `${icon} ${data.label}`);
    chip.title = data.label;
    this.activity ??= this.root.appendChild(h('div', 'activity'));
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

  /** @param {ApprovalRequired} data */
  addApproval(data) {
    this.closeSegment();
    this.root.appendChild(renderApproval(data));
    scrollToBottom();
  }

  /** @param {string} message */
  addError(message) {
    this.closeSegment();
    this.root.appendChild(h('div', 'notice', message));
  }

  finish() {
    this.closeSegment();
    if (!this.root.childElementCount) this.root.remove();
  }

  /**
   * Routes one stream event to the matching method. `switch` on the discriminant narrows `event.data`.
   * @param {AdvisorEvent} event
   */
  handle(event) {
    switch (event.type) {
      case 'text_delta': this.appendText(event.data.text); break;
      case 'thinking': this.appendThinking(event.data.text); break;
      case 'tool_activity': this.addActivity(event.data); break;
      case 'approval_required': this.addApproval(event.data); break;
      case 'error': this.addError(event.data.message); break;
      case 'done': break;
      default: {
        // Compile-time exhaustiveness check: adding an event type without a case is a type error here.
        // At runtime a newer server may still send unknown events, which are ignored.
        /** @type {never} */
        const unknown = event;
        console.debug('Ignoring unknown event', unknown);
      }
    }
  }
}

/**
 * @param {ApprovalRequired} data
 * @returns {HTMLDivElement}
 */
function renderApproval(data) {
  const card = h('div', 'approval');
  const count = data.actions.length;
  card.appendChild(h('h3', null, count === 1 ? 'Approval needed' : `${count} actions need approval`));
  const expires = new Date(data.expiresAt);
  card.appendChild(h('p', 'hint', `Nothing runs until you decide. Expires at ${expires.toLocaleTimeString()}.`));

  /** @type {Map<string, Decision>} */
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

    /** @param {boolean} approved */
    const decide = approved => {
      const why = reason.value.trim();
      decisions.set(action.toolUseId, { toolUseId: action.toolUseId, approved, reason: approved ? null : why || null });
      controls.replaceChildren(h('span', `decision ${approved ? 'approved' : 'rejected'}`,
        approved ? '✓ Approved' : `✕ Rejected${why ? ` — ${why}` : ''}`));
      if (decisions.size === data.actions.length) submitDecisions([...decisions.values()]);
    };
    approve.addEventListener('click', () => decide(true));
    reject.addEventListener('click', () => decide(false));
  }
  return card;
}

// ---------- turns ----------
/**
 * @param {(conversationId: string) => string} path
 * @param {ChatRequest | ApprovalRequest} body
 */
async function runTurn(path, body) {
  setBusy(true);
  const turn = new AssistantTurn();
  try {
    let res = await api(path(await ensureConversation()), body);
    if (res.status === 404) {
      // The server restarted and lost the conversation: start a fresh one.
      state.conversationId = null;
      const fresh = await ensureConversation();
      addNotice('The previous conversation was no longer available on the server, so a new one was started.');
      res = await api(path(fresh), body);
    }
    if (!res.ok) throw new Error(await failureMessage(res));
    for await (const event of readEvents(res)) turn.handle(event);
  } catch (e) {
    turn.addError(errorMessage(e));
  } finally {
    turn.finish();
    setBusy(false);
  }
}

/** @param {string} text */
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
  void runTurn(id => `/api/conversations/${id}/messages`, { text, accountId: accountId || null });
}

/** @param {Decision[]} decisions */
function submitDecisions(decisions) {
  void runTurn(id => `/api/conversations/${id}/approvals`, { decisions });
}

/** Selector results are typed `Element`; an `instanceof` check narrows them without a cast. */
function suggestionButtons() {
  return [...document.querySelectorAll('.suggestion')].filter(b => b instanceof HTMLButtonElement);
}

/** @param {boolean} busy */
function setBusy(busy) {
  state.busy = busy;
  updateSendState();
  for (const b of document.querySelectorAll('.approval button')) {
    if (b instanceof HTMLButtonElement) b.disabled = busy;
  }
  for (const b of suggestionButtons()) b.disabled = busy || !state.user;
}

function updateSendState() {
  el.send.disabled = state.busy || !state.user || !el.input.value.trim();
}

function autoGrow() {
  el.input.style.height = 'auto';
  el.input.style.height = `${Math.min(el.input.scrollHeight, 200)}px`;
}

// ---------- wiring ----------
el.form.addEventListener('submit', e => {
  e.preventDefault();
  const text = el.input.value.trim();
  if (text && !state.busy && state.user) sendMessage(text);
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
for (const button of suggestionButtons()) {
  button.addEventListener('click', () => {
    if (!state.busy && state.user) sendMessage(button.textContent ?? '');
  });
}

initAuth().catch(e => {
  console.error(e);
  addNotice(`Sign-in is not available: ${errorMessage(e)}`);
});
