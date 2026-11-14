import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { alertsApi, type AlertStatus, type Recipient } from './alerts'
import { messageFor } from './messages'

/** Who gets low-stock emails (besides you), a test email, and when the daily check last ran. */
export function AlertsPanel({ ownerEmail }: { ownerEmail: string }) {
  const [recipients, setRecipients] = useState<Recipient[] | null>(null)
  const [status, setStatus] = useState<AlertStatus | null>(null)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const [r, s] = await Promise.all([alertsApi.recipients(), alertsApi.status()])
      setRecipients(r)
      setStatus(s)
    } catch (e) {
      setError(e)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  async function run(fn: () => Promise<unknown>, success: string) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await fn()
      setNotice(success)
      await refresh()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  function add(e: FormEvent) {
    e.preventDefault()
    const who = name
    void run(async () => {
      await alertsApi.add(name, email)
      setName('')
      setEmail('')
    }, `${who} will get alert emails.`)
  }

  return (
    <details className="card history">
      <summary>Alert emails</summary>
      <p className="muted">
        An email goes out when a medicine gets down to its warning days (5 unless you changed it), when it runs out,
        and when it’s restocked. You ({ownerEmail}) always get them.
      </p>
      {status && (
        <p className="muted small">
          Last daily check: {status.lastDailyCheck ? new Date(status.lastDailyCheck).toLocaleString() : 'not yet'}
          {status.pendingEmails > 0 && ` · ${status.pendingEmails} email(s) waiting to send`}
          {status.emailProvider === 'log' && ' · email sending is not set up on the server yet'}
        </p>
      )}
      <ul className="plain">
        {recipients?.map((r) => (
          <li key={r.id}>
            {r.name} · {r.email}
            {!r.active && <span className="muted"> (unsubscribed)</span>}{' '}
            <button
              className="secondary compact"
              disabled={busy}
              onClick={() => {
                if (window.confirm(`Stop sending alerts to ${r.name}?`)) {
                  void run(() => alertsApi.remove(r.id), `${r.name} removed.`)
                }
              }}
            >
              Remove
            </button>
          </li>
        ))}
      </ul>
      <form className="form inline-form" onSubmit={add}>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} />
        </label>
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required maxLength={254} />
        </label>
        <button className="primary" type="submit" disabled={busy}>
          Add person
        </button>
      </form>
      <button
        className="secondary"
        disabled={busy}
        onClick={() => void run(() => alertsApi.testEmail(), 'Test email sent. Check inboxes (and spam).')}
      >
        Send a test email
      </button>
      {error ? <p className="error" role="alert">{messageFor(error)}</p> : null}
      {notice && <p className="success" role="status">{notice}</p>}
    </details>
  )
}

/** Opened from the link in an alert email; works without logging in. */
export function UnsubscribeScreen({ token }: { token: string }) {
  const [state, setState] = useState<'ask' | 'busy' | 'done'>('ask')
  const [error, setError] = useState<unknown>(null)

  async function confirm() {
    setState('busy')
    setError(null)
    try {
      await alertsApi.unsubscribe(token)
      setState('done')
    } catch (e) {
      setError(e)
      setState('ask')
    }
  }

  if (state === 'done') {
    return (
      <p className="success" role="status">
        Done. You won’t get medicine alert emails any more.
      </p>
    )
  }
  return (
    <>
      <p>Stop getting medicine alert emails?</p>
      <button className="primary" onClick={() => void confirm()} disabled={state === 'busy'}>
        {state === 'busy' ? 'Saving…' : 'Yes, unsubscribe'}
      </button>
      {error ? <p className="error" role="alert">{messageFor(error)}</p> : null}
    </>
  )
}
