import { ApiError } from './api'

export const FIELD_LABELS: Record<string, string> = {
  setupToken: 'Setup token',
  householdName: 'Household name',
  displayName: 'Your name',
  email: 'Email',
  password: 'Password',
  name: 'Medicine name',
  brandName: 'Brand',
  strength: 'Strength',
  dosageForm: 'Form',
  unit: 'Counted in',
  minThreshold: 'Warn below',
  dailyDose: 'Daily dose',
  warnDays: 'Warn days',
  storageLocation: 'Stored at',
  notes: 'Notes',
}

/** Friendly text for the API's stable error codes. */
export function messageFor(error: unknown): string {
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
    case 'DUPLICATE_MEDICINE':
      return 'This medicine is already in the list (same name, strength and form).'
    case 'STALE_VERSION':
      return 'Someone changed this medicine while you were editing. Reload it, then make your change again.'
    case 'MEDICINE_ARCHIVED':
      return 'This medicine is archived. Restore it before editing.'
    case 'MEDICINE_NOT_FOUND':
      return 'This medicine no longer exists.'
    case 'INSUFFICIENT_STOCK': {
      const usable = Number(error.extra.usable ?? 0)
      const expired = Number(error.extra.expired ?? 0)
      return `Not enough usable stock: only ${usable} left` + (expired > 0 ? ` (${expired} more are expired).` : '.')
    }
    case 'STOCK_CONFLICT':
      return `Stock changed while you were counting (now ${error.extra.currentUsable}). Check and save your count again.`
    case 'ALREADY_EXPIRED':
      return 'That expiry month has already passed. Expired stock can’t be added.'
    case 'CANNOT_UNDO':
      return 'Can’t undo: some of that stock has already been used. Use Recount instead.'
    case 'ALREADY_UNDONE':
      return 'That was already undone.'
    case 'CANNOT_UNDO_UNDO':
      return 'An undo can’t be undone. Record the change again instead.'
    case 'LOT_NOT_FOUND':
      return 'That batch is already gone.'
    case 'IDEMPOTENCY_KEY_REUSED':
      return 'Something went wrong with that request. Please try again.'
    case 'UNAUTHENTICATED':
      return 'You were logged out. Please log in again.'
    case 'VALIDATION_FAILED':
      return error.fieldErrors.map((f) => `${FIELD_LABELS[f.field] ?? f.field} ${f.message}`).join('. ') || error.message
    case 'NETWORK':
      return 'Cannot reach the server. Check your internet connection.'
    default:
      return error.retryable ? 'The server is starting up. Please try again in a moment.' : error.message
  }
}
