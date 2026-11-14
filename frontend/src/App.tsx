import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { api, ApiError, type Me } from './api'

type Screen =
  | { kind: 'starting'; waking: boolean }
  | { kind: 'unreachable' }
  | { kind: 'setup' }
  | { kind: 'login' }
  | { kind: 'home'; me: Me }

const WAKING_HINT_AFTER_MS = 2500
const GIVE_UP_AFTER_MS = 120_000

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** Friendly text for the API's stable error codes. */
function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Something went wrong. Please try again.'
  switch (error.code) {
    case 'INVALID_CREDENTIALS':
      return 'Email or password is incorrect.'
    case 'TOO_MANY_ATTEMPTS': {
      const minutes = Math.max(1, Math.ceil((error.retryAfterSeconds ?? 900) / 60))
      return `Too many failed attempts. Try again in about ${minutes} minute${minutes === 1 ? '' : 's'}.`
    }
    case 'SETUP_TOKEN_INVALID':
      return 'The setup token is wrong. Copy it again from your password manager.'
    case 'SETUP_DISABLED':
      return 'Setup is switched off on the server: SETUP_TOKEN is not set on Render.'
    case 'SETUP_ALREADY_COMPLETED':
      return 'Setup was already completed. Please log in.'
    case 'PASSWORD_TOO_LONG':
      return 'That password is too long.'
    case 'VALIDATION_FAILED':
      return error.fieldErrors.map((f) => `${labelFor(f.field)} ${f.message}`).join('. ') || error.message
    case 'NETWORK':
      return 'Cannot reach the server. Check your internet connection.'
    default:
      return error.retryable ? 'The server is starting up. Please try again in a moment.' : error.message
  }
}

function labelFor(field: string): string {
  const labels: Record<string, string> = {
    setupToken: 'Setup token',
    householdName: 'Household name',
    displayName: 'Your name',
    email: 'Email',
    password: 'Password',
  }
  return labels[field] ?? field
}

export default function App() {
  const [screen, setScreen] = useState<Screen>({ kind: 'starting', waking: false })

  /**
   * Free hosting sleeps when idle; the first request can take up to a minute while it wakes.
   * Keep retrying "server is starting" answers and show a friendly message instead of an error.
   */
  const start = useCallback(async () => {
    setScreen({ kind: 'starting', waking: false })
    const startedAt = Date.now()
    const hint = setTimeout(() => setScreen({ kind: 'starting', waking: true }), WAKING_HINT_AFTER_MS)
    try {
      for (;;) {
        try {
          const { setupRequired } = await api.setupStatus()
          if (setupRequired) {
            setScreen({ kind: 'setup' })
            return
          }
          try {
            setScreen({ kind: 'home', me: await api.me() })
          } catch (e) {
            if (e instanceof ApiError && e.status === 401) setScreen({ kind: 'login' })
            else throw e
          }
          return
        } catch (e) {
          const retry = e instanceof ApiError && e.retryable && Date.now() - startedAt < GIVE_UP_AFTER_MS
          if (!retry) {
            setScreen({ kind: 'unreachable' })
            return
          }
          await sleep(3000)
        }
      }
    } finally {
      clearTimeout(hint)
    }
  }, [])

  useEffect(() => {
    void start()
  }, [start])

  switch (screen.kind) {
    case 'starting':
      return <StartingScreen waking={screen.waking} />
    case 'unreachable':
      return (
        <Page title="Can't reach the server">
          <p>The server didn't answer. Check your internet connection and try again.</p>
          <button className="primary" onClick={() => void start()}>
            Try again
          </button>
        </Page>
      )
    case 'setup':
      return <SetupScreen onDone={(me) => setScreen({ kind: 'home', me })} />
    case 'login':
      return <LoginScreen onDone={(me) => setScreen({ kind: 'home', me })} />
    case 'home':
      return <HomeScreen me={screen.me} onLoggedOut={() => setScreen({ kind: 'login' })} />
  }
}

function Page({ title, children }: { title: string; children: ReactNode }) {
  return (
    <main className="page">
      <header className="brand">
        <img src="/icons/icon-192.png" alt="" width={40} height={40} />
        <span>Medicines</span>
      </header>
      <h1>{title}</h1>
      {children}
    </main>
  )
}

function StartingScreen({ waking }: { waking: boolean }) {
  return (
    <Page title={waking ? 'Waking up the server…' : 'Loading…'}>
      <div className="spinner" role="status" aria-label="Loading" />
      {waking && (
        <p className="muted">
          The free hosting sleeps when nobody has used the app for a while. Starting it again can take up to a
          minute. You don't need to do anything.
        </p>
      )}
    </Page>
  )
}

function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null
  return (
    <p className="error" role="alert">
      {messageFor(error)}
    </p>
  )
}

function SetupScreen({ onDone }: { onDone: (me: Me) => void }) {
  const [form, setForm] = useState({
    setupToken: '',
    householdName: '',
    displayName: '',
    email: '',
    password: '',
    confirm: '',
  })
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const [mismatch, setMismatch] = useState(false)

  const update = (field: keyof typeof form) => (e: { target: { value: string } }) =>
    setForm({ ...form, [field]: e.target.value })

  async function submit(e: FormEvent) {
    e.preventDefault()
    setMismatch(form.password !== form.confirm)
    if (form.password !== form.confirm) return
    setBusy(true)
    setError(null)
    try {
      const { confirm: _confirm, ...body } = form
      void _confirm
      onDone(await api.setup({ ...body, timezone: 'Asia/Kolkata' }))
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Page title="First-time setup">
      <p className="muted">Create the owner account. This works only once, with the setup token from Render.</p>
      <form onSubmit={submit} className="form">
        <label>
          Setup token
          <input
            value={form.setupToken}
            onChange={update('setupToken')}
            autoComplete="one-time-code"
            spellCheck={false}
            required
          />
        </label>
        <label>
          Household name
          <input value={form.householdName} onChange={update('householdName')} placeholder="e.g. Bagal family" required maxLength={100} />
        </label>
        <label>
          Your name
          <input value={form.displayName} onChange={update('displayName')} autoComplete="name" required maxLength={100} />
        </label>
        <label>
          Email
          <input type="email" value={form.email} onChange={update('email')} autoComplete="username" required maxLength={254} />
        </label>
        <label>
          Password <span className="muted">(12 to 64 characters)</span>
          <input
            type="password"
            value={form.password}
            onChange={update('password')}
            autoComplete="new-password"
            minLength={12}
            maxLength={64}
            required
          />
        </label>
        <label>
          Repeat password
          <input type="password" value={form.confirm} onChange={update('confirm')} autoComplete="new-password" required />
        </label>
        {mismatch && (
          <p className="error" role="alert">
            The two passwords don't match.
          </p>
        )}
        <ErrorBox error={error} />
        <button className="primary" type="submit" disabled={busy}>
          {busy ? 'Creating…' : 'Create account'}
        </button>
      </form>
    </Page>
  )
}

function LoginScreen({ onDone }: { onDone: (me: Me) => void }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      onDone(await api.login(email, password))
    } catch (err) {
      setError(err)
      setPassword('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Page title="Log in">
      <form onSubmit={submit} className="form">
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" required />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        <ErrorBox error={error} />
        <button className="primary" type="submit" disabled={busy}>
          {busy ? 'Logging in…' : 'Log in'}
        </button>
      </form>
    </Page>
  )
}

function HomeScreen({ me, onLoggedOut }: { me: Me; onLoggedOut: () => void }) {
  const [error, setError] = useState<unknown>(null)

  async function logout() {
    setError(null)
    try {
      await api.logout()
      onLoggedOut()
    } catch (err) {
      setError(err)
    }
  }

  return (
    <Page title={`Hello, ${me.user.displayName}`}>
      <p className="muted">{me.household.name}</p>
      <section className="card">
        <h2>Medicines</h2>
        <p>Your medicine list arrives in the next milestone.</p>
      </section>
      <ErrorBox error={error} />
      <button className="secondary" onClick={() => void logout()}>
        Log out
      </button>
    </Page>
  )
}
