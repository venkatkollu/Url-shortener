const API = '';

// --- DOM Elements ---
const shortenForm  = document.getElementById('shorten-form');
const shortenBtn   = document.getElementById('shorten-btn');
const urlInput     = document.getElementById('url-input');
const aliasInput   = document.getElementById('alias-input');
const expiryInput  = document.getElementById('expiry-input');
const resultDiv    = document.getElementById('result');
const resultLink   = document.getElementById('result-link');
const resultOrig   = document.getElementById('result-original');
const resultExpiry = document.getElementById('result-expiry');
const copyBtn      = document.getElementById('copy-btn');
const errorMsg     = document.getElementById('error-msg');

const statsForm    = document.getElementById('stats-form');
const statsInput   = document.getElementById('stats-input');
const statsResult  = document.getElementById('stats-result');
const statsError   = document.getElementById('stats-error');

// --- Shorten URL ---
shortenForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  hideAll();

  const body = { url: urlInput.value.trim() };
  if (aliasInput.value.trim()) body.customAlias = aliasInput.value.trim();
  if (expiryInput.value) body.expiresInMinutes = parseInt(expiryInput.value);

  shortenBtn.disabled = true;
  shortenBtn.textContent = 'Shortening...';

  try {
    const res = await fetch(`${API}/api/v1/shorten`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      const err = await res.json();
      throw new Error(err.message || Object.values(err.errors || {}).join(', ') || 'Something went wrong');
    }

    const data = await res.json();
    showResult(data);
  } catch (err) {
    showError(errorMsg, err.message);
  } finally {
    shortenBtn.disabled = false;
    shortenBtn.textContent = 'Shorten URL';
  }
});

function showResult(data) {
  resultLink.href = data.shortUrl;
  resultLink.textContent = data.shortUrl;
  resultOrig.textContent = 'Original: ' + truncate(data.originalUrl, 60);

  if (data.expiresAt) {
    resultExpiry.textContent = 'Expires: ' + formatDate(data.expiresAt);
    resultExpiry.classList.remove('hidden');
  } else {
    resultExpiry.textContent = '';
  }

  resultDiv.classList.remove('hidden');
}

// --- Copy to Clipboard ---
copyBtn.addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(resultLink.href);
    copyBtn.textContent = 'Copied!';
    copyBtn.classList.add('copied');
    setTimeout(() => {
      copyBtn.textContent = 'Copy';
      copyBtn.classList.remove('copied');
    }, 2000);
  } catch {
    copyBtn.textContent = 'Failed';
    setTimeout(() => { copyBtn.textContent = 'Copy'; }, 2000);
  }
});

// --- Stats Lookup ---
statsForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  statsResult.classList.add('hidden');
  statsError.classList.add('hidden');

  const code = extractShortCode(statsInput.value.trim());
  if (!code) return;

  try {
    const res = await fetch(`${API}/api/v1/urls/${encodeURIComponent(code)}/stats`);

    const contentType = res.headers.get('content-type') || '';
    if (!contentType.includes('application/json')) {
      throw new Error('Short code not found. Enter just the code (e.g. "google"), not a full URL.');
    }

    if (!res.ok) {
      const err = await res.json();
      throw new Error(err.message || 'Short code not found');
    }

    const data = await res.json();
    showStats(data);
  } catch (err) {
    showError(statsError, err.message);
  }
});

function showStats(data) {
  document.getElementById('stat-clicks').textContent = data.clickCount.toLocaleString();
  document.getElementById('stat-created').textContent = formatDate(data.createdAt);

  const expiresEl = document.getElementById('stat-expires');
  expiresEl.textContent = data.expiresAt ? formatDate(data.expiresAt) : 'Never';

  const shortUrlEl = document.getElementById('stat-short-url');
  shortUrlEl.href = data.shortUrl;
  shortUrlEl.textContent = data.shortUrl;

  document.getElementById('stat-original-url').textContent = data.originalUrl;

  statsResult.classList.remove('hidden');
}

// --- Helpers ---

/**
 * Extracts the short code from user input.
 * Handles: "google", "http://localhost:8021/google", "localhost:8021/google"
 */
function extractShortCode(input) {
  if (!input) return '';

  try {
    const url = new URL(input);
    const path = url.pathname.replace(/^\//, '');
    if (path && !path.includes('/')) {
      return path;
    }
  } catch {
    // not a URL, check if it looks like a domain/path
    const slashMatch = input.match(/^[^/]+\/([a-zA-Z0-9_-]+)$/);
    if (slashMatch) return slashMatch[1];
  }

  if (/^[a-zA-Z0-9_-]+$/.test(input)) {
    return input;
  }

  return input;
}

function hideAll() {
  resultDiv.classList.add('hidden');
  errorMsg.classList.add('hidden');
}

function showError(el, message) {
  el.textContent = message;
  el.classList.remove('hidden');
}

function truncate(str, max) {
  return str.length > max ? str.substring(0, max) + '...' : str;
}

function formatDate(dateStr) {
  if (!dateStr) return '-';
  const d = new Date(dateStr);
  return d.toLocaleDateString('en-US', {
    month: 'short', day: 'numeric', year: 'numeric',
    hour: '2-digit', minute: '2-digit'
  });
}
