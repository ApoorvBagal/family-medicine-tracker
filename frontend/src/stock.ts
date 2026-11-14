import { request } from './api'

export interface Lot {
  id: string
  expiryDate: string | null
  quantity: number
  label: string | null
  expired: boolean
}

export interface Stock {
  usableQuantity: number
  expiredQuantity: number
  nextExpiry: string | null
  lots: Lot[]
}

export type MovementType = 'PURCHASE' | 'CONSUMPTION' | 'CORRECTION' | 'DISPOSAL' | 'REVERSAL'

export interface Movement {
  id: string
  type: MovementType
  change: number
  usableAfter: number
  note: string | null
  performedBy: string
  occurredAt: string
  recordedAt: string
  reversesMovementId: string | null
  undone: boolean
}

export interface ChangeResponse {
  movement: Movement
  stock: Stock
}

/**
 * A fresh id per user action. If the request has to be sent again (bad network), the same id is reused,
 * so the server applies it only once.
 */
export function newActionKey(): string {
  return crypto.randomUUID()
}

const med = (id: string) => `/api/v1/medicines/${id}`
const post = (path: string, key: string, body: unknown) =>
  request<ChangeResponse>('POST', path, body, { 'Idempotency-Key': key })

export const stockApi = {
  get: (medicineId: string) => request<Stock>('GET', `${med(medicineId)}/stock`),
  movements: (medicineId: string) => request<Movement[]>('GET', `${med(medicineId)}/movements`),
  purchase: (medicineId: string, key: string, quantity: number, expiryDate: string | null) =>
    post(`${med(medicineId)}/purchases`, key, { quantity, expiryDate }),
  consume: (medicineId: string, key: string, quantity: number) =>
    post(`${med(medicineId)}/consumptions`, key, { quantity }),
  recount: (medicineId: string, key: string, countedUsable: number, expectedUsable: number) =>
    post(`${med(medicineId)}/recounts`, key, { countedUsable, expectedUsable }),
  dispose: (medicineId: string, key: string, lotId: string) =>
    post(`${med(medicineId)}/disposals`, key, { lotId }),
  undo: (movementId: string, key: string) => post(`/api/v1/stock-movements/${movementId}/reversal`, key, {}),
}

/** "2027-08" (from a month picker) → "2027-08-31": packs say "EXP 08/2027", meaning usable through August. */
export function endOfMonth(month: string): string {
  const [y, m] = month.split('-').map(Number)
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate()
  return `${month}-${String(last).padStart(2, '0')}`
}

export function formatQty(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')
}

/** "1 tablet", "2 tablets", "0.5 tablets", "5 ml". */
export function amount(n: number, unit: string): string {
  const u = n === 1 && unit.endsWith('s') ? unit.slice(0, -1) : unit
  return `${formatQty(n)} ${u}`
}

export function formatExpiry(date: string | null): string {
  if (!date) return 'no expiry date'
  const [y, m] = date.split('-')
  return `exp ${m}/${y}`
}
