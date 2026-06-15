import { useEffect, useRef, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useT } from '../i18n/useT'
import { ChatMessage, getAssistantStatus, sendChat } from './api'
import styles from './assistant.module.css'

// Global floating chat widget. Rendered in the app shell so it's available on every screen for
// authenticated users. The launcher only appears when GET /api/assistant/status reports enabled.
// Conversation history lives in component state; each turn POSTs the full messages array plus the
// active warehouseId (from the same warehouse hook the dashboards/reporting screens use).
export default function AssistantWidget() {
  const t = useT('assistant')
  const { session } = useAuth()
  const { currentWarehouseId } = useWarehouse()

  const [enabled, setEnabled] = useState(false)
  const [model, setModel] = useState('')
  const [open, setOpen] = useState(false)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const logRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const launcherRef = useRef<HTMLButtonElement>(null)

  // Fetch status once on mount (only when authenticated), and again whenever the panel is opened —
  // so a freshly configured/enabled assistant becomes usable without a reload.
  useEffect(() => {
    if (!session) {
      setEnabled(false)
      return
    }
    let cancelled = false
    getAssistantStatus().then((s) => {
      if (cancelled) return
      setEnabled(s.enabled)
      setModel(s.model)
    })
    return () => {
      cancelled = true
    }
  }, [session])

  useEffect(() => {
    if (!open) return
    getAssistantStatus().then((s) => {
      setEnabled(s.enabled)
      setModel(s.model)
    })
  }, [open])

  // Keep the log scrolled to the newest message, and focus the input when the panel opens.
  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight
  }, [messages, sending])

  useEffect(() => {
    if (open) inputRef.current?.focus()
    else launcherRef.current?.focus()
  }, [open])

  async function send() {
    const text = input.trim()
    if (!text || sending) return
    const next: ChatMessage[] = [...messages, { role: 'user', content: text }]
    setMessages(next)
    setInput('')
    setError(null)
    setSending(true)
    try {
      const reply = await sendChat(currentWarehouseId, next)
      setMessages([...next, { role: 'assistant', content: reply }])
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSending(false)
    }
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'Escape') {
      setOpen(false)
      return
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      send()
    }
  }

  if (!session || !enabled) return null

  if (!open) {
    return (
      <button
        ref={launcherRef}
        type="button"
        className={styles.launcher}
        onClick={() => setOpen(true)}
        aria-label={t('open', 'Open AI assistant')}
        aria-haspopup="dialog"
      >
        <span className={styles.launcherIcon} aria-hidden="true">✦</span>
        {t('launcher', 'Assistant')}
      </button>
    )
  }

  return (
    <div
      className={styles.panel}
      role="dialog"
      aria-modal="false"
      aria-label={t('title', 'AI assistant')}
      onKeyDown={onKeyDown}
    >
      <div className={styles.head}>
        <div className={styles.headTitle}>
          <strong>{t('title', 'AI assistant')}</strong>
          {model && <span className={styles.headMeta}>{model}</span>}
        </div>
        <button
          type="button"
          className={styles.close}
          onClick={() => setOpen(false)}
          aria-label={t('close', 'Close')}
        >
          ×
        </button>
      </div>

      <div className={styles.log} ref={logRef}>
        {messages.length === 0 && !sending && (
          <p className={styles.empty}>
            {t('emptyHint', 'Ask about your warehouse — orders, stock, throughput or how a feature works.')}
          </p>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`${styles.bubble} ${m.role === 'user' ? styles.user : styles.assistant}`}>
            {m.content}
          </div>
        ))}
        {sending && <div className={styles.thinking}>{t('thinking', 'Thinking…')}</div>}
      </div>

      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}

      <div className={styles.composer}>
        <textarea
          ref={inputRef}
          className="form-control"
          value={input}
          placeholder={t('placeholder', 'Type a message…')}
          aria-label={t('placeholder', 'Type a message…')}
          rows={1}
          disabled={sending}
          onChange={(e) => setInput(e.target.value)}
        />
        <button
          type="button"
          className="btn btn-primary"
          onClick={send}
          disabled={sending || !input.trim()}
        >
          {t('send', 'Send')}
        </button>
      </div>
    </div>
  )
}
