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

export interface BackupEntry {
	name: string;
	size: number;
	created: string;
	profiles: number;
}

export interface Install {
	deviceId: string;
	sha256: string;
	status: 'pending' | 'waiting' | 'installed' | 'skipped' | 'failed';
	detail: string;
	updated: string;
}

export interface Apk {
	sha256: string;
	package: string;
	versionCode: number;
	versionName: string;
	fileName: string;
	size: number;
	uploaded: string;
	installs: Install[];
}

export interface RestoreResult {
	profiles: number;
	changed: number;
}

export interface ConnectorField {
	key: string;
	label: string;
	help?: string;
	placeholder?: string;
	required?: boolean;
	secret?: boolean;
}

export interface ConnectorKind {
	id: string;
	name: string;
	description: string;
	fields: ConnectorField[];
	feeds: { suffix: string; name: string; template: string }[];
}

export interface ConnectorStatus {
	lastRun?: string;
	lastOk?: string;
	lastError?: string;
	nextRun?: string;
	feeds: string[];
}

export interface Connector {
	id: string;
	kind: string;
	name: string;
	enabled: boolean;
	settings: Record<string, string>;
	created: string;
	hasKey: boolean;
	status?: ConnectorStatus;
}

export interface FeedCard {
	id: string;
	title: string;
	subtitle?: string;
	image?: string;
	badge?: string;
	weather?: { temp: number; unit: string; label?: string; high?: number; low?: number };
	score?: {
		state: string;
		home: { name: string; abbr?: string; score?: string | null };
		away: { name: string; abbr?: string; score?: string | null };
		detail?: string;
	};
	stat?: { value: string; unit?: string };
}

/** A widget feed (schema/widget-feed.schema.json). */
export interface WidgetFeed {
	id: string;
	name: string;
	template: 'cardRow' | 'stat' | 'weather' | 'nowPlaying' | 'scoreboard';
	sizes?: string[];
	kidsSafe: boolean;
	error?: string;
	updatedAt?: string;
	cards: FeedCard[];
}

export interface ConnectorInput {
	id?: string;
	kind?: string;
	name?: string;
	enabled?: boolean;
	settings?: Record<string, string>;
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

export interface HomeAssistantConfig {
	broker: string;
	username: string;
	/** Sent only when changing it; never returned. */
	password?: string;
	prefix: string;
	discovery: string;
	webhook: string;
}

export interface HomeAssistantView extends HomeAssistantConfig {
	hasPassword: boolean;
	status: {
		connected: boolean;
		error?: string;
		tvs: {
			device: Device;
			state: { app: string; appLabel: string; playing: boolean; screenOn: boolean };
			updated: string;
		}[];
	};
}

export const api = {
	homeAssistant: () => request<HomeAssistantView>('GET', '/homeassistant'),
	saveHomeAssistant: (c: HomeAssistantConfig & { clearPassword?: boolean }) =>
		request<HomeAssistantView>('PUT', '/homeassistant', c),
	testHomeAssistant: (c: HomeAssistantConfig) => request<void>('POST', '/homeassistant/test', c),

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
	apps: () => request<KnownApp[]>('GET', '/apps'),
	pushProfile: (id: string) => request<void>('POST', `/profiles/${encodeURIComponent(id)}/push`),

	backups: () => request<BackupEntry[]>('GET', '/backups'),
	createBackup: () => request<BackupEntry>('POST', '/backups'),
	restoreBackup: (name: string) =>
		request<RestoreResult>('POST', `/backups/${encodeURIComponent(name)}/restore`),
	restoreFile: (file: unknown) => request<RestoreResult>('POST', '/backups/restore', file),
	backupUrl: (name: string) => `/api/v1/backups/${encodeURIComponent(name)}`,

	connectorKinds: () => request<ConnectorKind[]>('GET', '/connectors/kinds'),
	connectors: () => request<Connector[]>('GET', '/connectors'),
	createConnector: (c: ConnectorInput) => request<Connector>('POST', '/connectors', c),
	updateConnector: (id: string, c: ConnectorInput) =>
		request<Connector>('PUT', `/connectors/${encodeURIComponent(id)}`, c),
	deleteConnector: (id: string) => request<void>('DELETE', `/connectors/${encodeURIComponent(id)}`),
	testConnector: (c: ConnectorInput) =>
		request<{ feeds: WidgetFeed[] }>('POST', '/connectors/test', c),
	refreshConnector: (id: string) =>
		request<void>('POST', `/connectors/${encodeURIComponent(id)}/refresh`),
	feeds: () => request<{ version: number; feeds: WidgetFeed[] }>('GET', '/connectors/feeds'),

	apks: () => request<Apk[]>('GET', '/apks'),
	installApk: (sha: string, devices: string[]) =>
		request<Apk>('POST', `/apks/${sha}/install`, { devices }),
	deleteApk: (sha: string) => request<void>('DELETE', `/apks/${sha}`),
	/** Uploads an APK file as the request body. */
	async uploadApk(file: File): Promise<Apk> {
		const res = await fetch('/api/v1/apks', {
			method: 'POST',
			credentials: 'same-origin',
			headers: {
				'Content-Type': 'application/vnd.android.package-archive',
				'X-File-Name': encodeURIComponent(file.name).slice(0, 200)
			},
			body: file
		});
		const data = await res.json().catch(() => ({}));
		if (!res.ok) {
			if (res.status === 401) onSignedOut();
			throw new ApiError(res.status, String(data.error ?? res.statusText), data);
		}
		return data as Apk;
	}
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
