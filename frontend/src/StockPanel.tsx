import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ApiError } from './api'
import { messageFor } from './messages'
import { UNITS, type Medicine } from './medicines'
import {
  amount,
  endOfMonth,
  formatExpiry,
  formatQty,
  newActionKey,
  stockApi,
  type Movement,
  type MovementType,
  type Stock,
} from './stock'

type Action = 'dose' | 'buy' | 'recount' | null

const LABELS: Record<MovementType, string> = {
  PURCHASE: 'Bought',
  CONSUMPTION: 'Dose taken',
  CORRECTION: 'Recount',
  DISPOSAL: 'Thrown away',
  REVERSAL: 'Undo',
}

/** Stock of one medicine: big numbers, the three everyday actions, batches, recent activity with undo. */
export function StockPanel({ medicine }: { medicine: Medicine }) {
  const [stock, setStock] = useState<Stock | null>(null)
  const [movements, setMovements] = useState<Movement[]>([])
  const [action, setAction] = useState<Action>(null)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // Kept while an action is in progress, so a retry after a network error reuses the same id.
  const [actionKey, setActionKey] = useState(newActionKey)

  const unit = UNITS[medicine.unit]

  const refresh = useCallback(async () => {
    try {
      const [s, m] = await Promise.all([stockApi.get(medicine.id), stockApi.movements(medicine.id)])
      setStock(s)
      setMovements(m)
    } catch (e) {
      setError(e)
    }
  }, [medicine.id])

  useEffect(() => {
    void refresh()
  }, [refresh])

  function open(next: Action) {
    setAction(next === action ? null : next)
    setError(null)
    setNotice(null)
  }

  /** Runs one stock change; on success shows the new numbers and starts a fresh action id. */
  async function run(fn: (key: string) => Promise<{ stock: Stock }>, success: string) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const res = await fn(actionKey)
      setStock(res.stock)
      setAction(null)
      setNotice(success)
      setActionKey(newActionKey())
      setMovements(await stockApi.movements(medicine.id))
    } catch (e) {
      setError(e)
      // Keep the same key only for "server didn't answer" errors; a definite "no" needs a new attempt.
      if (!(e instanceof ApiError && e.retryable)) {
        setActionKey(newActionKey())
      }
      void refresh()
    } finally {
      setBusy(false)
    }
  }

  if (!stock) return error ? <p className="error" role="alert">{messageFor(error)}</p> : <div className="spinner" />

  const daysLeft = stock.usableQuantity > 0 ? stock.daysLeft : null
  const estimating = stock.estimatedQuantity < stock.usableQuantity
  const latestUndoable = movements.find((m) => m.type !== 'REVERSAL' && !m.undone)

  return (
    <section className="stock card">
      <p className="big-number">
        {formatQty(stock.usableQuantity)} <span>{unit}</span>
      </p>
      <p className="muted">
        usable
        {daysLeft !== null && ` · about ${daysLeft} day${daysLeft === 1 ? '' : 's'} at ${formatQty(medicine.dailyDose!)} a day`}
        {stock.nextExpiry && ` · next expiry ${formatExpiry(stock.nextExpiry).replace('exp ', '')}`}
      </p>
      {estimating && (
        <p className="muted small">
          Estimated {formatQty(stock.estimatedQuantity)} {unit} now, counting {formatQty(medicine.dailyDose ?? 0)} a
          day since the last entry. Log doses or recount to correct it.
        </p>
      )}
      {stock.alertStatus !== 'OK' && stock.usableQuantity > 0 && (
        <p className="warning" role="alert">
          {stock.alertStatus === 'OUT'
            ? 'Probably run out by now: buy more, or recount.'
            : `Low: about ${daysLeft ?? 0} day${daysLeft === 1 ? '' : 's'} left. Buy more soon.`}
        </p>
      )}
      {stock.expiredQuantity > 0 && (
        <p className="warning" role="alert">
          ⚠ {formatQty(stock.expiredQuantity)} {unit} expired: not counted. Throw them away below.
        </p>
      )}

      <div className="actions">
        <button className={action === 'dose' ? 'primary' : 'secondary'} onClick={() => open('dose')} disabled={busy}>
          Took a dose
        </button>
        <button className={action === 'buy' ? 'primary' : 'secondary'} onClick={() => open('buy')} disabled={busy}>
          Bought more
        </button>
        <button
          className={action === 'recount' ? 'primary' : 'secondary'}
          onClick={() => open('recount')}
          disabled={busy}
        >
          Recount
        </button>
      </div>

      {action === 'dose' && (
        <DoseForm
          unit={unit}
          defaultAmount={1}
          busy={busy}
          onSubmit={(qty) => run((key) => stockApi.consume(medicine.id, key, qty), `Recorded: ${amount(qty, unit)} taken.`)}
        />
      )}
      {action === 'buy' && (
        <BuyForm
          unit={unit}
          busy={busy}
          onSubmit={(qty, expiry) =>
            run((key) => stockApi.purchase(medicine.id, key, qty, expiry), `Added ${amount(qty, unit)}.`)
          }
        />
      )}
      {action === 'recount' && (
        <RecountForm
          unit={unit}
          expected={stock.usableQuantity}
          busy={busy}
          onSubmit={(counted) =>
            run(
              (key) => stockApi.recount(medicine.id, key, counted, stock.usableQuantity),
              `Count saved: ${amount(counted, unit)}.`,
            )
          }
        />
      )}

      {error ? <p className="error" role="alert">{messageFor(error)}</p> : null}
      {notice && <p className="success" role="status">{notice}</p>}

      {stock.lots.length > 0 && (
        <details className="history">
          <summary>Batches ({stock.lots.length})</summary>
          <ul className="plain">
            {stock.lots.map((lot) => (
              <li key={lot.id} className={lot.expired ? 'expired' : ''}>
                {formatQty(lot.quantity)} {unit} · {formatExpiry(lot.expiryDate)}
                {lot.expired && (
                  <>
                    {' '}
                    <strong>EXPIRED</strong>{' '}
                    <button
                      className="secondary compact"
                      disabled={busy}
                      onClick={() => {
                        if (window.confirm(`Throw away ${formatQty(lot.quantity)} expired ${unit}?`)) {
                          void run((key) => stockApi.dispose(medicine.id, key, lot.id), 'Expired batch removed.')
                        }
                      }}
                    >
                      Throw away
                    </button>
                  </>
                )}
              </li>
            ))}
          </ul>
        </details>
      )}

      {movements.length > 0 && (
        <details className="history" open>
          <summary>Recent activity</summary>
          <ul className="plain">
            {movements.slice(0, 10).map((m) => (
              <li key={m.id} className={m.undone ? 'undone' : ''}>
                <strong>{LABELS[m.type]}</strong> {m.change > 0 ? '+' : ''}
                {formatQty(m.change)} → {formatQty(m.usableAfter)} · {m.performedBy},{' '}
                {new Date(m.recordedAt).toLocaleString()}
                {m.undone && <span className="muted"> (undone)</span>}
                {m.id === latestUndoable?.id && (
                  <>
                    {' '}
                    <button
                      className="secondary compact"
                      disabled={busy}
                      onClick={() => {
                        if (window.confirm(`Undo “${LABELS[m.type]} ${formatQty(m.change)}”?`)) {
                          void run((key) => stockApi.undo(m.id, key), 'Undone.')
                        }
                      }}
                    >
                      Undo
                    </button>
                  </>
                )}
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}

function DoseForm({
  unit,
  defaultAmount,
  busy,
  onSubmit,
}: {
  unit: string
  defaultAmount: number
  busy: boolean
  onSubmit: (qty: number) => void
}) {
  const [qty, setQty] = useState(String(defaultAmount))
  return (
    <form
      className="form inline-form"
      onSubmit={(e: FormEvent) => {
        e.preventDefault()
        onSubmit(Number(qty))
      }}
    >
      <label>
        How many {unit}?
        <input type="number" inputMode="decimal" min="0.01" step="any" value={qty} onChange={(e) => setQty(e.target.value)} required />
      </label>
      <button className="primary" type="submit" disabled={busy}>
        {busy ? 'Saving…' : 'Save dose'}
      </button>
    </form>
  )
}

function BuyForm({
  unit,
  busy,
  onSubmit,
}: {
  unit: string
  busy: boolean
  onSubmit: (qty: number, expiry: string | null) => void
}) {
  const [qty, setQty] = useState('')
  const [month, setMonth] = useState('')
  return (
    <form
      className="form inline-form"
      onSubmit={(e: FormEvent) => {
        e.preventDefault()
        onSubmit(Number(qty), month ? endOfMonth(month) : null)
      }}
    >
      <label>
        How many {unit}?
        <input type="number" inputMode="decimal" min="0.01" step="any" value={qty} onChange={(e) => setQty(e.target.value)} required />
      </label>
      <label>
        Expiry month <span className="muted">(from the pack, optional)</span>
        <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} />
      </label>
      <button className="primary" type="submit" disabled={busy}>
        {busy ? 'Saving…' : 'Add to stock'}
      </button>
    </form>
  )
}

function RecountForm({
  unit,
  expected,
  busy,
  onSubmit,
}: {
  unit: string
  expected: number
  busy: boolean
  onSubmit: (counted: number) => void
}) {
  const [counted, setCounted] = useState('')
  return (
    <form
      className="form inline-form"
      onSubmit={(e: FormEvent) => {
        e.preventDefault()
        onSubmit(Number(counted))
      }}
    >
      <p className="muted">
        The app says {formatQty(expected)} {unit}. Count the usable (not expired) {unit} you actually have.
      </p>
      <label>
        Counted {unit}
        <input type="number" inputMode="decimal" min="0" step="any" value={counted} onChange={(e) => setCounted(e.target.value)} required />
      </label>
      <button className="primary" type="submit" disabled={busy}>
        {busy ? 'Saving…' : 'Save count'}
      </button>
    </form>
  )
}
