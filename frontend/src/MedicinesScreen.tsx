import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ApiError } from './api'
import { FIELD_LABELS, messageFor } from './messages'
import {
  DOSAGE_FORMS,
  UNITS,
  describe,
  medicinesApi,
  type DosageForm,
  type HistoryEntry,
  type Medicine,
  type MedicineInput,
  type Unit,
} from './medicines'

type View = { kind: 'list' } | { kind: 'new' } | { kind: 'edit'; id: string }

/** The medicine list and its add/edit form. Stock numbers arrive in M3. */
export function MedicinesScreen() {
  const [view, setView] = useState<View>({ kind: 'list' })

  switch (view.kind) {
    case 'list':
      return <MedicineList onAdd={() => setView({ kind: 'new' })} onOpen={(id) => setView({ kind: 'edit', id })} />
    case 'new':
      return (
        <MedicineForm
          key="new"
          onDone={() => setView({ kind: 'list' })}
          onOpenExisting={(id) => setView({ kind: 'edit', id })}
        />
      )
    case 'edit':
      return (
        <MedicineForm
          key={view.id}
          id={view.id}
          onDone={() => setView({ kind: 'list' })}
          onOpenExisting={(id) => setView({ kind: 'edit', id })}
        />
      )
  }
}

function MedicineList({ onAdd, onOpen }: { onAdd: () => void; onOpen: (id: string) => void }) {
  const [query, setQuery] = useState('')
  const [archived, setArchived] = useState(false)
  const [items, setItems] = useState<Medicine[] | null>(null)
  const [error, setError] = useState<unknown>(null)

  useEffect(() => {
    let cancelled = false
    const timer = setTimeout(async () => {
      try {
        const result = await medicinesApi.list(query.trim(), archived)
        if (!cancelled) {
          setItems(result)
          setError(null)
        }
      } catch (e) {
        if (!cancelled) setError(e)
      }
    }, 250) // wait until typing pauses
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [query, archived])

  return (
    <section className="medicines">
      <div className="row">
        <h2>{archived ? 'Archived medicines' : 'Medicines'}</h2>
        {!archived && (
          <button className="primary compact" onClick={onAdd}>
            + Add
          </button>
        )}
      </div>
      <input
        type="search"
        placeholder="Search name or brand"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        aria-label="Search medicines"
      />
      {error ? <p className="error" role="alert">{messageFor(error)}</p> : null}
      {items === null && !error && <div className="spinner" role="status" aria-label="Loading" />}
      {items?.length === 0 && (
        <p className="muted empty">
          {query
            ? 'No medicine matches that search.'
            : archived
              ? 'Nothing archived.'
              : 'No medicines yet. Start with the regular ones: tap “+ Add”.'}
        </p>
      )}
      <ul className="list">
        {items?.map((m) => (
          <li key={m.id}>
            <button className="item" onClick={() => onOpen(m.id)}>
              <span className="item-title">{describe(m)}</span>
              <span className="muted">
                {[m.brandName, m.storageLocation].filter(Boolean).join(' · ') || ' '}
              </span>
              <span className="muted small">
                {m.dailyDose
                  ? `${formatNumber(m.dailyDose)} ${UNITS[m.unit]} a day · warn at ${m.warnDays} days left`
                  : `Occasional · warn below ${formatNumber(m.minThreshold)} ${UNITS[m.unit]}`}
              </span>
            </button>
          </li>
        ))}
      </ul>
      <label className="check">
        <input type="checkbox" checked={archived} onChange={(e) => setArchived(e.target.checked)} />
        Show archived medicines
      </label>
    </section>
  )
}

interface FormState {
  name: string
  brandName: string
  strength: string
  dosageForm: DosageForm
  unit: Unit
  regular: boolean
  dailyDose: string
  warnDays: string
  minThreshold: string
  storageLocation: string
  notes: string
}

const EMPTY: FormState = {
  name: '',
  brandName: '',
  strength: '',
  dosageForm: 'TABLET',
  unit: 'TABLET',
  regular: true,
  dailyDose: '',
  warnDays: '5',
  minThreshold: '0',
  storageLocation: '',
  notes: '',
}

function toForm(m: Medicine): FormState {
  return {
    name: m.name,
    brandName: m.brandName ?? '',
    strength: m.strength ?? '',
    dosageForm: m.dosageForm,
    unit: m.unit,
    regular: m.dailyDose !== null,
    dailyDose: m.dailyDose === null ? '' : String(m.dailyDose),
    warnDays: String(m.warnDays),
    minThreshold: String(m.minThreshold),
    storageLocation: m.storageLocation ?? '',
    notes: m.notes ?? '',
  }
}

function toInput(f: FormState): MedicineInput {
  const text = (s: string) => (s.trim() === '' ? null : s.trim())
  return {
    name: f.name.trim(),
    brandName: text(f.brandName),
    strength: text(f.strength),
    dosageForm: f.dosageForm,
    unit: f.unit,
    dailyDose: f.regular && f.dailyDose !== '' ? Number(f.dailyDose) : null,
    warnDays: f.warnDays === '' ? 5 : Number(f.warnDays),
    minThreshold: f.minThreshold === '' ? 0 : Number(f.minThreshold),
    storageLocation: text(f.storageLocation),
    notes: text(f.notes),
  }
}

/** Sensible "counted in" unit when the form changes (still editable). */
const UNIT_FOR_FORM: Record<DosageForm, Unit> = {
  TABLET: 'TABLET',
  CAPSULE: 'CAPSULE',
  SYRUP: 'ML',
  DROPS: 'ML',
  INHALER: 'PIECE',
  CREAM: 'TUBE',
  INJECTION: 'PIECE',
  OTHER: 'PIECE',
}

function MedicineForm({
  id,
  onDone,
  onOpenExisting,
}: {
  id?: string
  onDone: () => void
  onOpenExisting: (id: string) => void
}) {
  const [loaded, setLoaded] = useState<Medicine | null>(null)
  const [form, setForm] = useState<FormState>(EMPTY)
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(false)

  const load = useCallback(async () => {
    if (!id) return
    try {
      const m = await medicinesApi.get(id)
      setLoaded(m)
      setForm(toForm(m))
      setError(null)
    } catch (e) {
      setError(e)
    }
  }, [id])

  useEffect(() => {
    void load()
  }, [load])

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setSaved(false)
    setForm((f) => ({ ...f, [key]: value }))
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (loaded) {
        const m = await medicinesApi.update(loaded.id, loaded.version, toInput(form))
        setLoaded(m)
        setForm(toForm(m))
        setSaved(true)
      } else {
        await medicinesApi.create(toInput(form))
        onDone()
      }
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  async function toggleArchived() {
    if (!loaded) return
    if (!loaded.archived && !window.confirm(`Archive ${loaded.name}? It disappears from the list but keeps its history.`)) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      const m = loaded.archived ? await medicinesApi.restore(loaded.id) : await medicinesApi.archive(loaded.id)
      if (m.archived) {
        onDone()
      } else {
        setLoaded(m)
        setForm(toForm(m))
      }
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  if (id && !loaded && !error) return <div className="spinner" role="status" aria-label="Loading" />

  const unitLabel = UNITS[form.unit]
  const existingId =
    error instanceof ApiError && error.code === 'DUPLICATE_MEDICINE' ? (error.extra.existingId as string | undefined) : undefined
  const stale = error instanceof ApiError && error.code === 'STALE_VERSION'
  const readOnly = loaded?.archived ?? false

  return (
    <section>
      <button className="link" onClick={onDone}>
        ← Back to list
      </button>
      <h2>{loaded ? (readOnly ? `${loaded.name} (archived)` : `Edit ${loaded.name}`) : 'Add a medicine'}</h2>
      <form onSubmit={submit} className="form">
        <fieldset disabled={readOnly || busy}>
          <label>
            Medicine name
            <input value={form.name} onChange={(e) => set('name', e.target.value)} required maxLength={100} placeholder="e.g. Metformin" />
          </label>
          <label>
            Brand <span className="muted">(optional)</span>
            <input value={form.brandName} onChange={(e) => set('brandName', e.target.value)} maxLength={100} placeholder="e.g. Glycomet" />
          </label>
          <div className="two">
            <label>
              Strength
              <input value={form.strength} onChange={(e) => set('strength', e.target.value)} maxLength={50} placeholder="500 mg" />
            </label>
            <label>
              Form
              <select
                value={form.dosageForm}
                onChange={(e) => {
                  const df = e.target.value as DosageForm
                  setForm((f) => ({ ...f, dosageForm: df, unit: UNIT_FOR_FORM[df] }))
                }}
              >
                {Object.entries(DOSAGE_FORMS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <label>
            Counted in
            <select value={form.unit} onChange={(e) => set('unit', e.target.value as Unit)}>
              {Object.entries(UNITS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>

          <div className="choice" role="radiogroup" aria-label="How is it taken?">
            <label className="check">
              <input type="radio" checked={form.regular} onChange={() => set('regular', true)} />
              Taken every day
            </label>
            <label className="check">
              <input type="radio" checked={!form.regular} onChange={() => set('regular', false)} />
              Only sometimes
            </label>
          </div>

          {form.regular ? (
            <div className="two">
              <label>
                Daily dose <span className="muted">({unitLabel})</span>
                <input
                  type="number"
                  inputMode="decimal"
                  min="0.01"
                  step="any"
                  value={form.dailyDose}
                  onChange={(e) => set('dailyDose', e.target.value)}
                  required
                  placeholder="2"
                />
              </label>
              <label>
                Warn at <span className="muted">(days left)</span>
                <input
                  type="number"
                  inputMode="numeric"
                  min="0"
                  max="60"
                  value={form.warnDays}
                  onChange={(e) => set('warnDays', e.target.value)}
                />
              </label>
            </div>
          ) : (
            <label>
              Warn below <span className="muted">({unitLabel})</span>
              <input
                type="number"
                inputMode="decimal"
                min="0"
                step="any"
                value={form.minThreshold}
                onChange={(e) => set('minThreshold', e.target.value)}
              />
            </label>
          )}

          <label>
            Stored at <span className="muted">(optional)</span>
            <input value={form.storageLocation} onChange={(e) => set('storageLocation', e.target.value)} maxLength={100} placeholder="e.g. Kitchen shelf" />
          </label>
          <label>
            Notes <span className="muted">(optional)</span>
            <textarea value={form.notes} onChange={(e) => set('notes', e.target.value)} maxLength={1000} rows={3} />
          </label>
        </fieldset>

        {error ? (
          <div className="error" role="alert">
            <p>{messageFor(error)}</p>
            {existingId && (
              <button type="button" className="secondary compact" onClick={() => onOpenExisting(existingId)}>
                Open the existing one
              </button>
            )}
            {stale && (
              <button type="button" className="secondary compact" onClick={() => void load()}>
                Reload latest
              </button>
            )}
          </div>
        ) : null}
        {saved && <p className="success" role="status">Saved.</p>}

        {!readOnly && (
          <button className="primary" type="submit" disabled={busy}>
            {busy ? 'Saving…' : loaded ? 'Save changes' : 'Add medicine'}
          </button>
        )}
        {loaded && (
          <button type="button" className="secondary" onClick={() => void toggleArchived()} disabled={busy}>
            {loaded.archived ? 'Restore medicine' : 'Archive medicine'}
          </button>
        )}
      </form>
      {loaded && <History id={loaded.id} version={loaded.version} />}
    </section>
  )
}

const ACTIONS: Record<HistoryEntry['action'], string> = {
  CREATED: 'Added',
  UPDATED: 'Changed',
  ARCHIVED: 'Archived',
  RESTORED: 'Restored',
}

function History({ id, version }: { id: string; version: number }) {
  const [entries, setEntries] = useState<HistoryEntry[] | null>(null)

  useEffect(() => {
    medicinesApi.history(id).then(setEntries, () => setEntries([]))
  }, [id, version]) // reload after each save

  if (!entries?.length) return null
  return (
    <details className="history">
      <summary>History ({entries.length})</summary>
      <ul>
        {entries.map((e, i) => (
          <li key={i}>
            <strong>{ACTIONS[e.action]}</strong> by {e.actorName}, {new Date(e.occurredAt).toLocaleString()}
            {e.action === 'UPDATED' && (
              <ul>
                {Object.entries(e.changes).map(([field, change]) => {
                  const c = change as { from: unknown; to: unknown }
                  return (
                    <li key={field} className="muted">
                      {FIELD_LABELS[field] ?? field}: {show(c.from)} → {show(c.to)}
                    </li>
                  )
                })}
              </ul>
            )}
          </li>
        ))}
      </ul>
    </details>
  )
}

function show(v: unknown): string {
  return v === null || v === undefined || v === '' ? '(empty)' : String(v)
}

function formatNumber(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')
}
