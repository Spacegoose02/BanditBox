// Thin client for the server API (docs/api.md). Same origin, cookie session.

import type { KnownApp } from './layout';

export interface Admin {
	id: number;
	username: string;
}

export interface AuthStatus {
	setupNeeded: boolean;
	admin: Admin | null;
}

export interface ProfileSummary {
	id: string;
	name: string;
	version: number;
	updated: string;
}

export interface Device {
	id: string;
	name: string;
	model: string;
	profiles: string[];
	created: string;
	lastSeen: string;
	appVersion: string;
}

export interface PendingPairing {
	code: string;
	name: string;
	model: string;
	created: string;
	expires: string;
}

/** A layout document (schema/layout.schema.json). Kept loose: the editor works on plain JSON. */
export type Layout = Record<string, unknown> & { profileId: string; version: number };

export class ApiError extends Error {
	constructor(
		public status: number,
		message: string,
		public body: Record<string, unknown> = {}
	) {
		super(message);
	}
}

/** Called when a request finds the session gone, so the app can show the sign-in page. */
let onSignedOut: () => void = () => {};
export function handleSignedOut(fn: () => void) {
	onSignedOut = fn;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
	const res = await fetch('/api/v1' + path, {
		method,
		credentials: 'same-origin',
		headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
		body: body === undefined ? undefined : JSON.stringify(body)
	});
	if (res.status === 204) return undefined as T;
	let data: Record<string, unknown> = {};
	try {
		data = await res.json();
	} catch {
		// Not JSON: fall through with an empty body.
	}
	if (!res.ok) {
		if (res.status === 401 && !path.startsWith('/auth/')) onSignedOut();
		throw new ApiError(res.status, String(data.error ?? res.statusText), data);
	}
	return data as T;
}

export const api = {
	status: () => request<AuthStatus>('GET', '/auth/status'),
	setup: (code: string, username: string, password: string) =>
		request<AuthStatus>('POST', '/auth/setup', { code, username, password }),
	login: (username: string, password: string) =>
		request<AuthStatus>('POST', '/auth/login', { username, password }),
	logout: () => request<void>('POST', '/auth/logout'),
	changePassword: (current: string, next: string) =>
		request<void>('POST', '/auth/password', { current, new: next }),

	profiles: () => request<ProfileSummary[]>('GET', '/profiles'),
	createProfile: (id: string, name: string, copyFrom?: string) =>
		request<Layout>('POST', '/profiles', { id, name, ...(copyFrom ? { copyFrom } : {}) }),
	layout: (id: string) => request<Layout>('GET', `/profiles/${encodeURIComponent(id)}/layout`),
	saveLayout: (doc: Layout) =>
		request<Layout>('PUT', `/profiles/${encodeURIComponent(doc.profileId)}/layout`, doc),
	deleteProfile: (id: string) => request<void>('DELETE', `/profiles/${encodeURIComponent(id)}`),

	pending: () => request<PendingPairing[]>('GET', '/pair/pending'),
	approve: (code: string, name: string, profiles: string[]) =>
		request<Device>('POST', '/pair/approve', { code, name, profiles }),
	deny: (code: string) => request<void>('POST', '/pair/deny', { code }),
	devices: () => request<Device[]>('GET', '/devices'),
	updateDevice: (id: string, patch: { name?: string; profiles?: string[] }) =>
		request<Device>('PATCH', `/devices/${encodeURIComponent(id)}`, patch),
	revokeDevice: (id: string) => request<void>('DELETE', `/devices/${encodeURIComponent(id)}`),
	apps: () => request<KnownApp[]>('GET', '/apps')
};

/** "3 minutes ago" style text for a timestamp. */
export function ago(iso: string, now = Date.now()): string {
	const t = Date.parse(iso);
	if (!t || t < 86400000) return 'never';
	const s = Math.round((now - t) / 1000);
	if (s < 60) return 'just now';
	const m = Math.round(s / 60);
	if (m < 60) return `${m} min ago`;
	const h = Math.round(m / 60);
	if (h < 24) return `${h} h ago`;
	return `${Math.round(h / 24)} d ago`;
}

/** Turns a display name into a profile id like p_living_room. */
export function profileIdFrom(name: string): string {
	const slug = name
		.toLowerCase()
		.normalize('NFKD')
		.replace(/[^a-z0-9]+/g, '_')
		.replace(/^_+|_+$/g, '')
		.slice(0, 40);
	return 'p_' + (slug || Date.now().toString(36));
}
