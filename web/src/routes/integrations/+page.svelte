<script lang="ts">
	import { onMount } from 'svelte';
	import { api, ago, type Connector, type ConnectorKind, type WidgetFeed } from '$lib/api';
	import FeedPreview from '$lib/components/FeedPreview.svelte';

	let kinds = $state<ConnectorKind[]>([]);
	let connectors = $state<Connector[]>([]);
	let feeds = $state<WidgetFeed[]>([]);
	let error = $state('');
	let message = $state('');

	/** The form being filled in: a new connector (id empty) or an existing one. */
	let form = $state<{
		id: string;
		kind: string;
		name: string;
		settings: Record<string, string>;
		hasKey: boolean;
	} | null>(null);
	let formError = $state('');
	let tested = $state<WidgetFeed[] | null>(null);
	let busy = $state(false);

	const formKind = $derived(kinds.find((k) => k.id === form?.kind));

	async function load() {
		try {
			const [k, c, f] = await Promise.all([api.connectorKinds(), api.connectors(), api.feeds()]);
			kinds = k;
			connectors = c;
			feeds = f.feeds;
			error = '';
		} catch (err) {
			error = (err as Error).message;
		}
	}

	onMount(() => {
		load();
		const t = setInterval(load, 15000);
		return () => clearInterval(t);
	});

	function kindName(id: string) {
		return kinds.find((k) => k.id === id)?.name ?? id;
	}

	function startNew(kind: ConnectorKind) {
		form = { id: '', kind: kind.id, name: kind.name, settings: {}, hasKey: false };
		formError = '';
		tested = null;
	}

	function startEdit(c: Connector) {
		form = { id: c.id, kind: c.kind, name: c.name, settings: { ...c.settings }, hasKey: c.hasKey };
		formError = '';
		tested = null;
	}

	function input() {
		if (!form) return {};
		return {
			...(form.id ? { id: form.id } : { kind: form.kind }),
			name: form.name,
			settings: $state.snapshot(form.settings)
		};
	}

	async function test() {
		if (!form) return;
		busy = true;
		formError = '';
		tested = null;
		try {
			tested = (await api.testConnector({ ...input(), kind: form.kind })).feeds;
		} catch (err) {
			formError = (err as Error).message;
		} finally {
			busy = false;
		}
	}

	async function save(e: SubmitEvent) {
		e.preventDefault();
		if (!form) return;
		busy = true;
		formError = '';
		try {
			const c = form.id
				? await api.updateConnector(form.id, input())
				: await api.createConnector(input());
			message = `Saved ${c.name}. Its feeds: ${feedNames(c)}. TVs get them within a minute.`;
			form = null;
			tested = null;
			await load();
			// The first fetch runs right after saving.
			setTimeout(load, 3000);
		} catch (err) {
			formError = (err as Error).message;
		} finally {
			busy = false;
		}
	}

	function feedNames(c: Connector) {
		const k = kinds.find((x) => x.id === c.kind);
		if (!k) return c.id;
		return k.feeds.map((f) => (f.suffix === '*' ? `${c.id}.…` : `${c.id}.${f.suffix}`)).join(', ');
	}

	async function toggle(c: Connector) {
		await api.updateConnector(c.id, { enabled: !c.enabled }).catch((err) => (error = err.message));
		await load();
	}

	async function refresh(c: Connector) {
		await api.refreshConnector(c.id).catch((err) => (error = err.message));
		setTimeout(load, 2500);
	}

	async function remove(c: Connector) {
		if (!confirm(`Remove ${c.name}? Rows that show its feeds go empty on the TVs.`)) return;
		await api.deleteConnector(c.id).catch((err) => (error = err.message));
		await load();
	}

	function feedsOf(c: Connector) {
		return feeds.filter((f) => f.id.startsWith(c.id + '.'));
	}
</script>

<h1>Integrations</h1>
<p class="muted">
	Connectors fetch cards from your other apps and send them to every TV: the weather next to the
	clock, your teams' scores, BingeBandit's Continue Watching. Keys stay on this server. Show a feed
	on a TV by adding a widget row (or the weather in the top bar) in a profile's layout.
</p>

{#if error}<p class="error" role="alert">{error}</p>{/if}
{#if message}<p role="status">{message}</p>{/if}

{#each connectors as c (c.id)}
	<section class="panel">
		<div class="row spread">
			<div>
				<h2>{c.name}</h2>
				<p class="muted small">
					{kindName(c.kind)}{c.settings.url ? ' · ' + c.settings.url : ''}
					{#if !c.enabled}· <strong>off</strong>{/if}
				</p>
			</div>
			<div class="row">
				<button onclick={() => startEdit(c)}>Edit</button>
				<button onclick={() => refresh(c)} disabled={!c.enabled}>Fetch now</button>
				<button onclick={() => toggle(c)}>{c.enabled ? 'Turn off' : 'Turn on'}</button>
				<button class="danger" onclick={() => remove(c)}>Remove</button>
			</div>
		</div>
		{#if c.enabled && c.status}
			<p class="small">
				{#if c.status.lastError}
					<span class="error">Failed {ago(c.status.lastRun ?? '')}: {c.status.lastError}</span>
				{:else if c.status.lastOk}
					<span class="muted">Fetched {ago(c.status.lastOk)}</span>
				{/if}
			</p>
		{:else if c.enabled}
			<p class="muted small">Fetching…</p>
		{/if}
		{#each feedsOf(c) as f (f.id)}<FeedPreview feed={f} />{/each}
	</section>
{:else}
	<p class="muted">No connectors yet.</p>
{/each}

{#if form && formKind}
	<form class="panel" onsubmit={save}>
		<h2>{form.id ? 'Edit ' + form.name : 'Add ' + formKind.name}</h2>
		<p class="muted small">{formKind.description}</p>
		<label><span>Name</span><input bind:value={form.name} maxlength="60" required /></label>
		{#each formKind.fields as field (field.key)}
			<label>
				<span>{field.label}{field.required ? '' : ' (optional)'}</span>
				{#if field.secret}
					<input
						type="password"
						autocomplete="off"
						bind:value={form.settings[field.key]}
						placeholder={form.hasKey ? 'Saved. Leave empty to keep it' : field.placeholder}
						required={field.required && !form.hasKey}
					/>
				{:else}
					<input
						bind:value={form.settings[field.key]}
						placeholder={field.placeholder}
						required={field.required}
					/>
				{/if}
				{#if field.help}<small class="muted">{field.help}</small>{/if}
			</label>
		{/each}
		{#if formError}<p class="error" role="alert">{formError}</p>{/if}
		{#if tested}
			<p role="status">It works. This is what the TVs would get:</p>
			{#each tested as f (f.id)}<FeedPreview feed={f} />{/each}
		{/if}
		<div class="row">
			<button type="button" onclick={test} disabled={busy}>{busy ? 'Trying…' : 'Test'}</button>
			<button class="primary" disabled={busy}>Save</button>
			<button type="button" onclick={() => (form = null)}>Cancel</button>
		</div>
	</form>
{:else}
	<section class="panel">
		<h2>Add a connector</h2>
		<div class="kinds">
			{#each kinds as k (k.id)}
				<div class="kind">
					<strong>{k.name}</strong>
					<p class="muted small">{k.description}</p>
					<button onclick={() => startNew(k)}>Add {k.name}</button>
				</div>
			{/each}
		</div>
	</section>
{/if}

<style>
	h2 {
		margin: 0;
	}
	.kinds {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr));
		gap: 1rem;
	}
	.kind p {
		margin: 0.25rem 0 0.5rem;
	}
	label small {
		display: block;
		margin-top: 0.25rem;
	}
</style>
