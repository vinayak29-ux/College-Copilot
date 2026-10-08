const http = require('node:http');

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '127.0.0.1';
const BACKBOARD_API_KEY = process.env.BACKBOARD_API_KEY;
const ASSISTANT_ID = process.env.BACKBOARD_ASSISTANT_ID || '8e4957ab-77c4-48dd-87eb-cc770eb17f28';
const BACKBOARD_URL = 'https://app.backboard.io/api';
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_BODY_BYTES = 64 * 1024;
const RATE_LIMIT = 30;
const RATE_WINDOW_MS = 60_000;
const requestWindows = new Map();
setInterval(() => {
  const now = Date.now();
  for (const [address, window] of requestWindows) {
    if (now - window.startedAt >= RATE_WINDOW_MS) requestWindows.delete(address);
  }
}, RATE_WINDOW_MS).unref();

function sendJson(response, status, body) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  });
  response.end(JSON.stringify(body));
}

function allowRequest(address) {
  const now = Date.now();
  const window = requestWindows.get(address);
  if (!window || now - window.startedAt >= RATE_WINDOW_MS) {
    requestWindows.set(address, { startedAt: now, count: 1 });
    return true;
  }
  if (window.count >= RATE_LIMIT) return false;
  window.count += 1;
  return true;
}

async function readJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw Object.assign(new Error('Request body is too large.'), { status: 413 });
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw Object.assign(new Error('Request body must be valid JSON.'), { status: 400 });
  }
}

async function backboard(path, options = {}) {
  if (!BACKBOARD_API_KEY) throw Object.assign(new Error('Assistant service is not configured.'), { status: 503 });
  const response = await fetch(`${BACKBOARD_URL}${path}`, {
    ...options,
    signal: AbortSignal.timeout(85_000),
    headers: {
      'X-API-Key': BACKBOARD_API_KEY,
      'Accept': 'application/json',
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...options.headers
    }
  });
  const text = await response.text();
  let body;
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    body = {};
  }
  if (!response.ok) {
    console.error(`Backboard returned HTTP ${response.status}.`);
    throw Object.assign(new Error('Assistant service request failed.'), { status: 502 });
  }
  return body;
}

function textContent(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content.map((part) => {
    if (typeof part === 'string') return part;
    if (typeof part.text === 'string') return part.text;
    if (part.text && typeof part.text.value === 'string') return part.text.value;
    return '';
  }).filter(Boolean).join('\n');
}

function threadMessages(thread) {
  return (Array.isArray(thread.messages) ? thread.messages : [])
    .filter((message) => message.role === 'user' || message.role === 'assistant')
    .map((message) => ({
      messageId: String(message.id || ''),
      role: message.role,
      content: textContent(message.content)
    }))
    .filter((message) => message.content.trim());
}

async function handle(request, response) {
  if (!allowRequest(request.socket.remoteAddress || 'unknown')) {
    return sendJson(response, 429, { error: 'Too many requests. Try again later.' });
  }
  const url = new URL(request.url, 'http://localhost');

  try {
    if (request.method === 'POST' && url.pathname === '/api/assistant/thread') {
      const body = await readJson(request);
      if (body.threadId != null) {
        if (typeof body.threadId !== 'string' || !UUID_PATTERN.test(body.threadId)) {
          return sendJson(response, 400, { error: 'Invalid threadId.' });
        }
        const thread = await backboard(`/threads/${encodeURIComponent(body.threadId)}`);
        if (thread.thread_id !== body.threadId || thread.assistant_id !== ASSISTANT_ID) {
          return sendJson(response, 404, { error: 'Assistant thread was not found.' });
        }
        return sendJson(response, 200, {
          threadId: thread.thread_id || body.threadId,
          messages: threadMessages(thread)
        });
      }

      const thread = await backboard(`/assistants/${ASSISTANT_ID}/threads`, {
        method: 'POST',
        body: JSON.stringify({})
      });
      if (typeof thread.thread_id !== 'string' || !UUID_PATTERN.test(thread.thread_id)) {
        throw Object.assign(new Error('Assistant service returned an invalid thread.'), { status: 502 });
      }
      return sendJson(response, 200, { threadId: thread.thread_id, messages: [] });
    }

    if (request.method === 'POST' && url.pathname === '/api/assistant/message') {
      const body = await readJson(request);
      if (typeof body.threadId !== 'string' || !UUID_PATTERN.test(body.threadId)) {
        return sendJson(response, 400, { error: 'Invalid threadId.' });
      }
      if (typeof body.content !== 'string' || !body.content.trim() || body.content.length > 4000) {
        return sendJson(response, 400, { error: 'content must contain 1 to 4000 characters.' });
      }

      const thread = await backboard(`/threads/${encodeURIComponent(body.threadId)}`);
      if (thread.thread_id !== body.threadId || thread.assistant_id !== ASSISTANT_ID) {
        return sendJson(response, 404, { error: 'Assistant thread was not found.' });
      }

      const result = await backboard('/threads/messages', {
        method: 'POST',
        body: JSON.stringify({
          thread_id: body.threadId,
          assistant_id: ASSISTANT_ID,
          content: body.content.trim(),
          llm_provider: 'google',
          model_name: 'gemini-2.5-flash',
          stream: false
        })
      });
      if (result.status === 'FAILED' || result.status === 'REQUIRES_ACTION' || typeof result.content !== 'string') {
        throw Object.assign(new Error('Assistant did not return a text response.'), { status: 502 });
      }
      return sendJson(response, 200, {
        threadId: result.thread_id || body.threadId,
        messageId: result.message_id || null,
        content: result.content
      });
    }

    return sendJson(response, 404, { error: 'Not found.' });
  } catch (error) {
    const status = Number.isInteger(error.status) ? error.status : 502;
    if (status >= 500) console.error(error.message);
    return sendJson(response, status, {
      error: status === 503 ? 'Assistant service is not configured.' : 'Assistant service is temporarily unavailable.'
    });
  }
}

const server = http.createServer((request, response) => {
  handle(request, response).catch((error) => {
    console.error(error.message);
    if (!response.headersSent) sendJson(response, 500, { error: 'Assistant service is temporarily unavailable.' });
  });
});

server.listen(PORT, HOST, () => {
  console.log(`Assistant proxy listening on http://${HOST}:${PORT}`);
});
