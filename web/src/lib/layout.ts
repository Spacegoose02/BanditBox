// Types and small edits for the layout editor. The editor changes only what it knows and
// keeps every other field as it came from the server (themes, button shortcuts, kids rules).

import type { Layout } from './api';

export interface Tile {
	size?: 'small' | 'medium' | 'large';
	style?: 'banner' | 'icon';
	shape?: 'rounded' | 'square' | 'circle';
}

export interface Row {
	id: string;
	type: 'apps' | 'widget' | 'watchnext' | 'channel' | string;
	title?: string | null;
	/** apps rows */
	items?: string[];
	tile?: Tile;
	/** watchnext and channel rows */
	size?: 'small' | 'medium' | 'large';
	limit?: number;
	/** channel rows */
	package?: string;
	/** widget rows: "<plugin>.<feed>" */
	feed?: string;
	[key: string]: unknown;
}

export interface Folder {
	id: string;
	name: string;
	items?: string[];
}

export type LayoutDoc = Layout & {
	profile: { name: string; autoLogin?: boolean; kids?: unknown; [key: string]: unknown };
	rows: Row[];
	folders?: Folder[];
	hiddenApps?: string[];
	topBar?: string[];
};

export interface KnownApp {
	packageName: string;
	label: string;
	tv: boolean;
	devices: string[];
}

export const FOLDER_PREFIX = 'folder:';

/** A short random id like r_k3x9q2, unique enough within one layout. */
export function newId(prefix: string, taken: Iterable<string>): string {
	const used = new Set(taken);
	for (;;) {
		const id = prefix + '_' + Math.random().toString(36).slice(2, 8);
		if (!used.has(id)) return id;
	}
}

/** Moves list[i] by delta places, if it can. */
export function move<T>(list: T[], i: number, delta: number) {
	const j = i + delta;
	if (j < 0 || j >= list.length) return;
	const [x] = list.splice(i, 1);
	list.splice(j, 0, x);
}

export const rowKinds: Record<string, string> = {
	apps: 'Apps',
	watchnext: 'Continue watching',
	channel: 'App channel',
	widget: 'Widget'
};

/** Removes a folder and every place it appears in a row. */
export function deleteFolder(doc: LayoutDoc, id: string) {
	doc.folders = (doc.folders ?? []).filter((f) => f.id !== id);
	for (const row of doc.rows) {
		if (row.items) row.items = row.items.filter((it) => it !== FOLDER_PREFIX + id);
	}
}

/** Problems the server would refuse, said plainly, so they show before saving. */
export function problems(doc: LayoutDoc): string[] {
	const out: string[] = [];
	if (!doc.profile.name.trim()) out.push('The profile needs a name.');
	for (const r of doc.rows) {
		if (r.type === 'widget' && !r.feed) out.push('Every widget row needs a feed.');
	}
	for (const f of doc.folders ?? []) {
		if (!f.name.trim()) out.push('Every folder needs a name.');
	}
	return [...new Set(out)];
}
