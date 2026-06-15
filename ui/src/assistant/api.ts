// Client for the AI assistant (floating chat widget). All calls go through the gateway (/api/**)
// and rely on the global Bearer interceptor (no separate authFetch wrapper — plain fetch carries
// the credentials the same way every other api.ts in the app does).

export interface AssistantStatus {
  enabled: boolean
  configured: boolean
  model: string
}

export type ChatRole = 'user' | 'assistant'

export interface ChatMessage {
  role: ChatRole
  content: string
}

interface ChatRequest {
  warehouseId: string
  messages: ChatMessage[]
}

interface ChatResponse {
  reply: string
}

/** Whether the assistant is switched on (and, separately, whether an API key has been configured).
 *  Best-effort: a non-OK response or network error is reported as disabled so the launcher hides. */
export async function getAssistantStatus(): Promise<AssistantStatus> {
  try {
    const res = await fetch('/api/assistant/status')
    if (!res.ok) return { enabled: false, configured: false, model: '' }
    return (await res.json()) as AssistantStatus
  } catch {
    return { enabled: false, configured: false, model: '' }
  }
}

/** Send the full conversation plus the active warehouse and get the assistant's reply. */
export async function sendChat(warehouseId: string, messages: ChatMessage[]): Promise<string> {
  const body: ChatRequest = { warehouseId, messages }
  const res = await fetch('/api/assistant/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const b = (await res.json().catch(() => ({}))) as { detail?: string; message?: string }
    throw new Error(b.detail || b.message || `${res.status} ${res.statusText}`)
  }
  const data = (await res.json()) as ChatResponse
  return data.reply ?? ''
}
