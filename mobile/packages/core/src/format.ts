export function money(v: unknown): string {
  const n = typeof v === 'number' ? v : Number(v ?? 0);
  if (Number.isNaN(n)) return '₹0';
  return '₹' + n.toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

export function dateTime(v: unknown): string {
  if (!v) return '—';
  const d = new Date(String(v));
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
}

export function date(v: unknown): string {
  if (!v) return '—';
  const d = new Date(String(v));
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function ago(v: unknown): string {
  if (!v) return '';
  const mins = Math.round((Date.now() - new Date(String(v)).getTime()) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const h = Math.round(mins / 60);
  if (h < 24) return `${h} h ago`;
  return `${Math.round(h / 24)} d ago`;
}

const LABELS: Record<string, string> = {
  REQUEST_CREATED: 'Submitted',
  AGENT_REVIEW: 'Agent reviewing',
  PARTNER_QUOTATION: 'Collecting quotes',
  CUSTOMER_APPROVAL: 'Quotes ready',
  PAYMENT_PENDING: 'Payment pending',
  PAYMENT_CONFIRMED: 'Paid',
  PARTNER_ASSIGNED: 'Partner assigned',
  PARTNER_CONFIRMED: 'Booking confirmed',
  RESCHEDULING_REQUESTED: 'Reschedule requested',
  PARTNER_ARRIVED: 'Partner arrived',
  SERVICE_STARTED: 'In progress',
  IN_PROGRESS: 'In progress',
  COMPLETION_PENDING: 'Awaiting your confirmation',
  COMPLETED: 'Completed',
  SETTLEMENT_PENDING: 'Completed',
  SETTLED: 'Completed',
  CANCELLED: 'Cancelled',
  REJECTED: 'Rejected',
  EXPIRED: 'Expired',
  PARTNER_DECLINED: 'Finding another partner',
  NO_SHOW: 'Partner no-show',
  DISPUTED: 'Under review',
  REFUND_PENDING: 'Refund in review',
  REFUNDED: 'Refunded',
};

export function statusLabel(s: string): string {
  return LABELS[s] ?? s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
}

export type Tone = 'success' | 'warning' | 'danger' | 'neutral' | 'info';

export function statusTone(s: string): Tone {
  if (['COMPLETED', 'SETTLEMENT_PENDING', 'SETTLED', 'PAYMENT_CONFIRMED', 'APPROVED', 'VERIFIED', 'SUCCEEDED', 'PAID', 'AVAILABLE', 'ACCEPTED', 'CONFIRMED'].includes(s)) return 'success';
  if (['CANCELLED', 'REJECTED', 'EXPIRED', 'NO_SHOW', 'FAILED', 'DISPUTED', 'DECLINED', 'SUSPENDED', 'UNAVAILABLE', 'ON_LEAVE'].includes(s)) return 'danger';
  if (['PAYMENT_PENDING', 'CUSTOMER_APPROVAL', 'COMPLETION_PENDING', 'REFUND_PENDING', 'PARTNER_DECLINED', 'PENDING', 'SUBMITTED', 'REQUESTED', 'INVITED', 'BUSY', 'RESCHEDULING_REQUESTED'].includes(s)) return 'warning';
  if (['REQUEST_CREATED', 'AGENT_REVIEW', 'PARTNER_QUOTATION'].includes(s)) return 'info';
  return 'neutral';
}
