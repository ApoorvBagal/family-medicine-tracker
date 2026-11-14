import { request } from './api'

export interface Recipient {
  id: string
  name: string
  email: string
  active: boolean
  createdAt: string
}

export interface AlertStatus {
  emailProvider: string
  lastDailyCheck: string | null
  pendingEmails: number
}

export const alertsApi = {
  recipients: () => request<Recipient[]>('GET', '/api/v1/alert-recipients'),
  add: (name: string, email: string) => request<Recipient>('POST', '/api/v1/alert-recipients', { name, email }),
  remove: (id: string) => request<void>('DELETE', `/api/v1/alert-recipients/${id}`),
  status: () => request<AlertStatus>('GET', '/api/v1/alerts/status'),
  testEmail: () => request<{ queued: number }>('POST', '/api/v1/alerts/test-email', {}),
  unsubscribe: (token: string) =>
    request<{ unsubscribed: boolean }>('POST', `/api/v1/unsubscribe?token=${encodeURIComponent(token)}`, {}),
}
