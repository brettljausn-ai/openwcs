// Microsoft Graph mail sender for the openWCS public site's "Contact us" form.
// Standalone CommonJS — no database, no app settings. Uses the OAuth2
// client-credentials flow to send mail as a fixed mailbox (MS_GRAPH_MAIL_ADDRESS)
// via Microsoft Graph. Requires Node 18+ (global fetch).
//
// Env:
//   MS_GRAPH_TENANT_ID      Azure AD tenant id (required)
//   MS_GRAPH_CLIENT_ID      App registration client id (required)
//   MS_GRAPH_CLIENT_SECRET  App registration client secret (required)
//   MS_GRAPH_MAIL_ADDRESS   Sender mailbox the app may send as (required for isConfigured)
//   MS_GRAPH_FROM_NAME      Display name for the sender (optional, default 'openWCS')
//   CONTACT_TO              Recipient for contact messages (optional, default contact@brettljausn.ai)

let cachedToken = null;
let tokenExpiry = 0;

async function getGraphToken() {
  if (cachedToken && Date.now() < tokenExpiry) return cachedToken;

  const tenantId = process.env.MS_GRAPH_TENANT_ID;
  const clientId = process.env.MS_GRAPH_CLIENT_ID;
  const clientSecret = process.env.MS_GRAPH_CLIENT_SECRET;
  if (!tenantId || !clientId || !clientSecret) {
    throw new Error('Microsoft Graph not configured. Set MS_GRAPH_TENANT_ID, MS_GRAPH_CLIENT_ID, and MS_GRAPH_CLIENT_SECRET.');
  }

  const res = await fetch(`https://login.microsoftonline.com/${tenantId}/oauth2/v2.0/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      client_id: clientId,
      client_secret: clientSecret,
      scope: 'https://graph.microsoft.com/.default',
      grant_type: 'client_credentials',
    }),
  });

  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error_description || err.error || 'Failed to obtain Graph token');
  }

  const data = await res.json();
  cachedToken = data.access_token;
  // Refresh ~5 min before the real expiry to avoid edge-of-life 401s.
  tokenExpiry = Date.now() + (data.expires_in - 300) * 1000;
  return cachedToken;
}

// Some Graph endpoints (e.g. /sendMail) return 202 Accepted with an empty body,
// others 204 No Content. Reading those as JSON throws "Unexpected end of JSON
// input". Read as text first and only JSON-parse when there's something to parse.
async function readBody(res) {
  const text = await res.text();
  if (!text) return null;
  try { return JSON.parse(text); } catch { return text; }
}

async function graphRequest(method, path, body = null) {
  const token = await getGraphToken();
  const opts = {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  };
  if (body) opts.body = JSON.stringify(body);

  const res = await fetch(`https://graph.microsoft.com/v1.0${path}`, opts);

  if (res.status === 401) {
    // Token may have been revoked/expired server-side; clear cache and retry once.
    cachedToken = null;
    tokenExpiry = 0;
    const retryToken = await getGraphToken();
    opts.headers.Authorization = `Bearer ${retryToken}`;
    const retry = await fetch(`https://graph.microsoft.com/v1.0${path}`, opts);
    if (!retry.ok) {
      const err = await readBody(retry);
      throw new Error(err?.error?.message || `Graph API error: ${retry.status}`);
    }
    return await readBody(retry);
  }

  if (!res.ok) {
    const err = await readBody(res);
    throw new Error(err?.error?.message || `Graph API error: ${res.status}`);
  }

  return await readBody(res);
}

function isConfigured() {
  return Boolean(
    process.env.MS_GRAPH_TENANT_ID &&
    process.env.MS_GRAPH_CLIENT_ID &&
    process.env.MS_GRAPH_CLIENT_SECRET &&
    process.env.MS_GRAPH_MAIL_ADDRESS
  );
}

// Escape user-supplied text before embedding it in the HTML mail body to
// prevent HTML/markup injection.
function escapeHtml(value) {
  return String(value == null ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

async function sendContactEmail({ fromEmail, name, message }) {
  const mailAddress = process.env.MS_GRAPH_MAIL_ADDRESS;
  const fromName = process.env.MS_GRAPH_FROM_NAME || 'openWCS';
  const to = process.env.CONTACT_TO || 'contact@brettljausn.ai';

  const safeEmail = escapeHtml(fromEmail);
  const safeName = name ? escapeHtml(name) : '';
  // Escape first, then turn newlines into <br> so the breaks survive escaping.
  const safeMessage = escapeHtml(message).replace(/\r\n|\r|\n/g, '<br>');

  const rows = [];
  if (safeName) {
    rows.push(`<tr><td style="padding:4px 12px 4px 0;color:#6b7280;">Name</td><td style="padding:4px 0;">${safeName}</td></tr>`);
  }
  rows.push(`<tr><td style="padding:4px 12px 4px 0;color:#6b7280;">Email</td><td style="padding:4px 0;"><a href="mailto:${safeEmail}">${safeEmail}</a></td></tr>`);

  const htmlBody = `<div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;font-size:14px;color:#111827;line-height:1.5;">
  <p style="margin:0 0 12px;font-weight:600;">New message from the openWCS website contact form</p>
  <table style="border-collapse:collapse;margin:0 0 16px;">
    ${rows.join('\n    ')}
  </table>
  <div style="padding:12px 16px;background:#f3f4f6;border-left:3px solid #2563eb;border-radius:4px;white-space:normal;">${safeMessage}</div>
</div>`;

  const graphMessage = {
    subject: `openWCS website contact — ${fromEmail}`,
    body: { contentType: 'HTML', content: htmlBody },
    toRecipients: [{ emailAddress: { address: to } }],
    from: { emailAddress: { address: mailAddress, name: fromName } },
    // replyTo so the recipient can hit "Reply" and reach the submitter, not
    // the platform mailbox.
    replyTo: [{ emailAddress: { address: fromEmail, name: name || fromEmail } }],
  };

  await graphRequest('POST', `/users/${mailAddress}/sendMail`, {
    message: graphMessage,
    saveToSentItems: true,
  });
}

module.exports = { getGraphToken, graphRequest, isConfigured, sendContactEmail };
