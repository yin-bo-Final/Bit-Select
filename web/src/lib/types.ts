export type Product = {
  id: number;
  sku?: string;
  name: string;
  category: string;
  categoryName?: string;
  description: string;
  priceCents: number;
  originalPriceCents?: number;
  stock: number;
  imageUrl: string;
  specifications?: Record<string, string>;
  enabled: boolean;
  featured: boolean;
  manualUrl?: string;
  tags?: string[];
  sold?: number;
};
export type PageResult<T> = {
  items: T[];
  total: number;
  page?: number;
  pageSize?: number;
};
export type User = {
  id: number;
  username: string;
  nickname?: string;
  role: string;
  balanceCents: number;
};
export type CartItem = {
  id?: number;
  productId: number;
  quantity: number;
  product: Product;
};
export type OrderItem = {
  productId: number;
  name: string;
  quantity: number;
  priceCents: number;
  imageUrl?: string;
};
export type Address = { recipient: string; phone: string; detail: string };
export type SavedAddress = Address & { id: number; isDefault: boolean };
export type RefundRequest = {
  id: string;
  orderId: string;
  orderNo: string;
  userId: number;
  totalCents: number;
  reason: string;
  status: string;
  reviewReason?: string;
  createdAt: string;
};
export type Order = {
  id: string;
  orderNo: string;
  status: string;
  totalCents: number;
  createdAt: string;
  expiresAt: string;
  paidAt?: string;
  items: OrderItem[];
  address: Address;
  trackingNo?: string;
  userId?: number;
};
export type WalletEntry = {
  id: number;
  type: string;
  amountCents: number;
  balanceAfterCents: number;
  referenceId: string;
  createdAt: string;
};
export type Source = {
  productId?: number;
  title: string;
  excerpt: string;
  score?: number;
};
export type ChatMessage = {
  role: string;
  content: string;
  createdAt?: string;
  sources?: Source[];
};
export type Conversation = { id: string; title: string; updatedAt: string };
export type Memory = {
  id: string;
  content: string;
  updatedAt: string;
  source: string;
  confidence: number;
};
