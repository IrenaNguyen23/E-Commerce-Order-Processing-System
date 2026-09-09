/** DTOs for Auth Service. Mirrors `com.commerceflow.authservice.dto`. */

export type Role = 'CUSTOMER' | 'ADMIN';

export interface User {
  id: string;
  email: string;
  fullName: string;
  phone: string | null;
  roles: Role[];
  enabled: boolean;
  createdAt: string;
}

export interface AuthTokens {
  accessToken: string;
  /** Single use: presenting it rotates the pair, and reuse revokes every session. */
  refreshToken: string;
  tokenType: string;
  /** Seconds. */
  expiresIn: number;
  user: User;
}

export interface RegisterPayload {
  email: string;
  password: string;
  fullName: string;
  phone?: string;
}

export interface LoginPayload {
  email: string;
  password: string;
}

export interface RefreshPayload {
  refreshToken: string;
}

export const isAdmin = (user: User | null): boolean => Boolean(user?.roles.includes('ADMIN'));
