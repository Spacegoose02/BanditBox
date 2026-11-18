<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { beforeNavigate } from '$app/navigation';
	import { api, ApiError, ago, type WidgetFeed } from '$lib/api';
	import {
		deleteFolder,
		move,
		newId,
		problems,
		rowKinds,
		type KnownApp,
		type LayoutDoc,
		type Row
	} from '$lib/layout';
	import AppList from '$lib/components/AppList.svelte';

	const id = page.url.searchParams.get('id') ?? '';

	let doc = $state<LayoutDoc | null>(null);
	/** The document as last loaded or saved, to tell whether there are unsaved changes. */
	let saved = $state('');
	let apps = $state<KnownApp[]>([]);
	/** Widget feeds from the server's connectors (Integrations). */
	let feeds = $state<WidgetFeed[]>([]);
	let error = $state('');
	let busy = $state(false);
	let savedAt = $state<string | null>(null);
	/** The server's newer copy, when saving found the profile changed meanwhile. */
	let conflict = $state<LayoutDoc | null>(null);

	const dirty = $derived(doc !== null && JSON.stringify(doc) !== saved);
	const issues = $derived(doc ? problems(doc) : []);

	/** Fills in the lists the editor binds to, so every row and folder can be edited the same way. */
	function prepare(d: LayoutDoc): LayoutDoc {
		d.folders ??= [];
		d.hiddenApps ??= [];
		for (const r of d.rows) {
			if (r.type === 'apps') {
				r.items ??= [];
				r.tile ??= {};
				// Defaults are left out, so the pickers' "default" choice matches them.
				if (r.tile.size === 'medium') delete r.tile.size;
				if (r.tile.style === 'banner') delete r.tile.style;
				if (r.tile.shape === 'rounded') delete r.tile.shape;
			}
			if (r.size === 'medium') delete r.size;
		}
		for (const f of d.folders) f.items ??= [];
		return d;
	}

	function show(d: LayoutDoc) {
		doc = prepare(d);
		saved = JSON.stringify(doc);
		conflict = null;
	}

	async function load() {
		error = '';
		try {
			const [layout, known, fd] = await Promise.all([
				api.layout(id),
				api.apps().catch(() => []),
				api.feeds().catch(() => ({ version: 0, feeds: [] }))
			]);
			show(layout as LayoutDoc);
			apps = known;
			feeds = fd.feeds;
		} catch (err) {
			error = (err as Error).message;
		}
	}

	onMount(() => {
		load();
		const warn = (e: BeforeUnloadEvent) => {
			if (dirty) e.preventDefault();
		};
		addEventListener('beforeunload', warn);
		return () => removeEventListener('beforeunload', warn);
	});

	beforeNavigate((nav) => {
		if (dirty && !confirm('Leave without saving your changes?')) nav.cancel();
	});

	async function save(over: LayoutDoc | null = null) {
		if (!doc) return;
		busy = true;
		error = '';
		const out = $state.snapshot(doc) as LayoutDoc;
		if (over) out.version = over.version;
		for (const r of out.rows) if (r.tile && Object.keys(r.tile).length === 0) delete r.tile;
		try {
			show((await api.saveLayout(out)) as LayoutDoc);
			savedAt = new Date().toISOString();
		} catch (err) {
			if (err instanceof ApiError && err.status === 409 && err.body.current) {
				conflict = err.body.current as LayoutDoc;
			} else {
				error = (err as Error).message;
			}
		} finally {
			busy = false;
		}
	}

	function addRow(type: 'apps' | 'watchnext' | 'widget') {
		if (!doc) return;
		const rid = newId(
			'r',
			doc.rows.map((r) => r.id)
		);
		let row: Row;
		if (type === 'apps') row = { id: rid, type, title: 'New row', items: [], tile: {} };
		else if (type === 'widget') {
			const f = rowFeeds[0];
			row = { id: rid, type, title: f?.name ?? 'Widget', feed: f?.id ?? '' };
		} else row = { id: rid, type, title: 'Continue watching' };
		doc.rows.push(row);
	}

	/** Feeds that fit a home row, and weather feeds for the top bar. */
	const rowFeeds = $derived(feeds.filter((f) => f.template !== 'weather'));
	const weatherFeeds = $derived(feeds.filter((f) => f.template === 'weather'));
	const FEED_ID = /^[a-z0-9_-]+(\.[a-z0-9_-]+)+$/;

	/** The weather feed shown in the top bar, if any. */
	const topWeather = $derived(
		(doc?.topBar ?? []).find((it) => FEED_ID.test(it) && !rowFeedIds.has(it)) ?? ''
	);
	const rowFeedIds = $derived(new Set(rowFeeds.map((f) => f.id)));

	function setTopWeather(feed: string) {
		if (!doc) return;
		const bar = (doc.topBar ?? []).filter((it) => it !== topWeather);
		if (feed) {
			const at = bar.indexOf('clock');
			bar.splice(at + 1, 0, feed);
		}
		doc.topBar = bar;
	}

	function feedLabel(fid: unknown) {
		const f = feeds.find((x) => x.id === fid);
		return f ? `${f.name} (${f.id})` : String(fid);
	}

	function removeRow(i: number) {
		if (!doc) return;
		const r = doc.rows[i];
		if (confirm(`Remove the row "${r.title || rowKinds[r.type] || r.type}"?`))
			doc.rows.splice(i, 1);
	}

	function addFolder() {
		if (!doc) return;
		doc.folders!.push({
			id: newId(
				'f',
				doc.folders!.map((f) => f.id)
			),
			name: 'New folder',
			items: []
		});
	}

	function removeFolder(fid: string, name: string) {
		if (doc && confirm(`Remove the folder "${name}"? Its apps stay installed.`))
			deleteFolder(doc, fid);
	}

	function appLabel(pkg: string | undefined) {
		return apps.find((a) => a.packageName === pkg)?.label ?? pkg ?? '';
	}
</script>

<p class="small"><a href="/profiles">← Profiles</a></p>

{#if !id}
	<p class="error">No profile chosen.</p>
{:else if !doc}
	{#if error}<p class="error" role="alert">{error}</p>{:else}<p class="muted">Loading…</p>{/if}
{:else}
	<div class="bar">
		<div>
			<h1>{doc.profile.name || 'Profile'}</h1>
			<p class="muted small">
				{#if dirty}Unsaved changes{:else if savedAt}Saved {ago(savedAt)}. TVs pick it up within
					seconds.{:else}Version
					{doc.version}{/if}
			</p>
		</div>
		<div class="row">
			<button disabled={!dirty || busy} onclick={() => doc && show(JSON.parse(saved))}
				>Undo changes</button
			>
			<button class="primary" disabled={!dirty || busy || issues.length > 0} onclick={() => save()}
				>{busy ? 'Saving…' : 'Save'}</button
			>
		</div>
	</div>

	{#if error}<p class="error" role="alert">{error}</p>{/if}
	{#each issues as issue (issue)}<p class="error">{issue}</p>{/each}
	{#if conflict}
		<section class="panel warn" role="alert">
			<p>This profile changed on a TV or in another tab since you opened it.</p>
			<div class="row">
				<button onclick={() => conflict && show(conflict)}>Load the latest (drop my changes)</button
				>
				<button class="danger" onclick={() => conflict && save(conflict)}>Save mine over it</button>
			</div>
		</section>
	{/if}
	{#if apps.length === 0}
		<p class="muted small">
			App names show once a paired TV has synced. Until then apps show by package name.
		</p>
	{/if}

	<section class="panel">
		<h2>Profile</h2>
		<label><span>Name</span><input bind:value={doc.profile.name} maxlength="40" /></label>
		<label class="check">
			<input type="checkbox" bind:checked={doc.profile.autoLogin} />
			Open this profile when the TV starts
		</label>
		{#if doc.profile.kids}
			<p class="muted small">This is a kids profile. Its rules and PIN are set on the TV.</p>
		{/if}
	</section>

	<h2>Rows</h2>
	{#each doc.rows as row, i (row.id)}
		<section class="panel">
			<div class="row spread head">
				<span class="muted small">{rowKinds[row.type] ?? row.type}</span>
				<span class="row tools">
					<button
						aria-label="Move row up"
						disabled={i === 0}
						onclick={() => doc && move(doc.rows, i, -1)}>↑</button
					>
					<button
						aria-label="Move row down"
						disabled={i === doc.rows.length - 1}
						onclick={() => doc && move(doc.rows, i, 1)}>↓</button
					>
					<button class="danger" onclick={() => removeRow(i)}>Remove</button>
				</span>
			</div>
			<label
				><span>Title</span><input
					value={row.title ?? ''}
					oninput={(e) => (row.title = e.currentTarget.value || null)}
					maxlength="60"
					placeholder="No title"
				/></label
			>
			{#if row.type === 'apps' && row.items && row.tile}
				<div class="grid">
					<label
						><span>Tile size</span>
						<select bind:value={row.tile.size}>
							<option value={undefined}>Medium</option>
							<option value="small">Small</option>
							<option value="large">Large</option>
						</select></label
					>
					<label
						><span>Tile style</span>
						<select bind:value={row.tile.style}>
							<option value={undefined}>Banner</option>
							<option value="icon">Icon</option>
						</select></label
					>
					<label
						><span>Corners</span>
						<select bind:value={row.tile.shape}>
							<option value={undefined}>Rounded</option>
							<option value="square">Square</option>
							<option value="circle">Circle</option>
						</select></label
					>
				</div>
				<AppList
					bind:items={row.items}
					{apps}
					folders={doc.folders}
					empty="No apps in this row yet."
				/>
			{:else if row.type === 'widget'}
				<label
					><span>Feed</span>
					<select bind:value={row.feed}>
						{#if row.feed && !feeds.some((f) => f.id === row.feed)}
							<option value={row.feed}>{row.feed} (not on this server)</option>
						{/if}
						{#each rowFeeds as f (f.id)}
							<option value={f.id}>{feedLabel(f.id)}</option>
						{/each}
					</select></label
				>
				{#if feeds.find((f) => f.id === row.feed && !f.kidsSafe) && doc.profile.kids}
					<p class="error small">This feed isn't kids-safe, so a kids profile hides it.</p>
				{/if}
			{:else if row.type === 'watchnext' || row.type === 'channel'}
				{#if row.type === 'channel'}
					<p class="small">From <strong>{appLabel(row.package)}</strong></p>
				{/if}
				<div class="grid">
					<label
						><span>Card size</span>
						<select bind:value={row.size}>
							<option value={undefined}>Medium</option>
							<option value="small">Small</option>
							<option value="large">Large</option>
						</select></label
					>
					<label
						><span>Most cards</span>
						<input
							type="number"
							min="1"
							max="50"
							value={row.limit ?? 20}
							oninput={(e) => {
								const n = Math.round(Number(e.currentTarget.value));
								if (n >= 1 && n <= 50) row.limit = n;
							}}
						/></label
					>
				</div>
			{/if}
		</section>
	{/each}
	<div class="row adds">
		<button onclick={() => addRow('apps')}>Add an apps row</button>
		<button onclick={() => addRow('watchnext')}>Add a Continue watching row</button>
		<button
			onclick={() => addRow('widget')}
			disabled={rowFeeds.length === 0}
			title={rowFeeds.length === 0 ? 'Add a connector under Integrations first' : ''}
			>Add a widget row</button
		>
	</div>

	<h2>Top bar</h2>
	<section class="panel">
		<label
			><span>Weather next to the clock</span>
			<select value={topWeather} onchange={(e) => setTopWeather(e.currentTarget.value)}>
				<option value="">None</option>
				{#if topWeather && !weatherFeeds.some((f) => f.id === topWeather)}
					<option value={topWeather}>{topWeather} (not on this server)</option>
				{/if}
				{#each weatherFeeds as f (f.id)}
					<option value={f.id}>{feedLabel(f.id)}</option>
				{/each}
			</select></label
		>
		{#if weatherFeeds.length === 0}
			<p class="muted small">Add Weather Scout under <a href="/integrations">Integrations</a>.</p>
		{/if}
	</section>

	<h2>Folders</h2>
	{#each doc.folders ?? [] as folder (folder.id)}
		<section class="panel">
			<div class="row spread head">
				<label class="grow"
					><span>Folder name</span><input bind:value={folder.name} maxlength="40" /></label
				>
				<button class="danger" onclick={() => removeFolder(folder.id, folder.name)}>Remove</button>
			</div>
			{#if folder.items}
				<AppList bind:items={folder.items} {apps} empty="This folder is empty." />
			{/if}
		</section>
	{:else}
		<p class="muted small">No folders. Add one, then put it in a row.</p>
	{/each}
	<div class="row adds"><button onclick={addFolder}>Add a folder</button></div>

	<h2>Hidden apps</h2>
	<section class="panel">
		<p class="muted small">Hidden apps don't show in All apps on this profile.</p>
		{#if doc.hiddenApps}
			<AppList bind:items={doc.hiddenApps} {apps} addLabel="Hide an app" empty="No hidden apps." />
		{/if}
	</section>
{/if}

<style>
	.bar {
		position: sticky;
		top: 0;
		z-index: 1;
		background: var(--bg);
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
		flex-wrap: wrap;
		gap: 0.75rem;
		padding: 0.5rem 0;
		margin-bottom: 0.75rem;
	}
	.bar h1 {
		margin: 0;
	}
	.bar p {
		margin: 0;
	}
	.head {
		margin-bottom: 0.5rem;
		align-items: flex-end;
	}
	.tools button {
		padding: 0.3rem 0.7rem;
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
		gap: 0 0.75rem;
	}
	.grow {
		flex: 1;
		margin: 0;
	}
	.check {
		display: flex;
		gap: 0.5rem;
		align-items: center;
	}
	.adds {
		margin: -0.5rem 0 1.5rem;
	}
	.warn {
		border-color: color-mix(in srgb, var(--danger) 50%, var(--line));
	}
	input[type='number'] {
		width: 100%;
		background: var(--panel-2);
		color: var(--text);
		border: 1px solid var(--line);
		border-radius: 8px;
		padding: 0.6rem 0.75rem;
		font: inherit;
	}
</style>
