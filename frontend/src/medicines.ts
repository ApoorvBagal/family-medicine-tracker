import { request } from './api'

export const DOSAGE_FORMS = {
  TABLET: 'Tablet',
  CAPSULE: 'Capsule',
  SYRUP: 'Syrup',
  DROPS: 'Drops',
  INHALER: 'Inhaler',
  CREAM: 'Cream / ointment',
  INJECTION: 'Injection',
  OTHER: 'Other',
} as const

export const UNITS = {
  TABLET: 'tablets',
  CAPSULE: 'capsules',
  ML: 'ml',
  BOTTLE: 'bottles',
  STRIP: 'strips',
  TUBE: 'tubes',
  PIECE: 'pieces',
  OTHER: 'other',
} as const

export type DosageForm = keyof typeof DOSAGE_FORMS
export type Unit = keyof typeof UNITS

export interface MedicineInput {
  name: string
  brandName: string | null
  strength: string | null
  dosageForm: DosageForm
  unit: Unit
  minThreshold: number
  dailyDose: number | null
  warnDays: number
  storageLocation: string | null
  notes: string | null
}

export interface Medicine extends MedicineInput {
  id: string
  usableQuantity: number
  expiredQuantity: number
  /** Runway estimate (M5): counts the daily dose since the last stock entry. */
  estimatedQuantity: number | null
  daysLeft: number | null
  alertStatus: 'OK' | 'LOW' | 'OUT' | null
  archived: boolean
  archivedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface HistoryEntry {
  action: 'CREATED' | 'UPDATED' | 'ARCHIVED' | 'RESTORED'
  actorName: string
  changes: Record<string, unknown>
  occurredAt: string
}

const base = '/api/v1/medicines'

export const medicinesApi = {
  list: (q = '', archived = false) =>
    request<Medicine[]>('GET', `${base}?archived=${archived}${q ? `&q=${encodeURIComponent(q)}` : ''}`),
  get: (id: string) => request<Medicine>('GET', `${base}/${id}`),
  create: (input: MedicineInput) => request<Medicine>('POST', base, input),
  /** Sends the version we last saw; the server refuses (STALE_VERSION) if someone else saved since. */
  update: (id: string, version: number, input: MedicineInput) =>
    request<Medicine>('PUT', `${base}/${id}`, input, { 'If-Match': `"v${version}"` }),
  archive: (id: string) => request<Medicine>('POST', `${base}/${id}/archive`, {}),
  restore: (id: string) => request<Medicine>('POST', `${base}/${id}/restore`, {}),
  history: (id: string) => request<HistoryEntry[]>('GET', `${base}/${id}/history`),
}

export function describe(m: Pick<Medicine, 'name' | 'strength' | 'dosageForm'>): string {
  return [m.name, m.strength, DOSAGE_FORMS[m.dosageForm].toLowerCase()].filter(Boolean).join(' · ')
}
