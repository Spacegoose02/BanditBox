<!-- An ordered list of apps (and folders) with buttons to move and remove each, and a picker to add more. -->
<script lang="ts">
	import { FOLDER_PREFIX, move, type Folder, type KnownApp } from '$lib/layout';

	let {
		items = $bindable(),
		apps,
		folders = [],
		addLabel = 'Add an app',
		empty = 'Nothing here yet.'
	}: {
		items: string[];
		apps: KnownApp[];
		/** Folders that may be added; none means apps only. */
		folders?: Folder[];
		addLabel?: string;
		empty?: string;
	} = $props();

	let choice = $state('');
	let typed = $state('');
	const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;

	function label(item: string): string {
		if (item.startsWith(FOLDER_PREFIX)) {
			const f = folders.find((x) => FOLDER_PREFIX + x.id === item);
			return '📁 ' + (f?.name || 'Folder');
		}
		return apps.find((a) => a.packageName === item)?.label ?? item;
	}

	const tvApps = $derived(apps.filter((a) => a.tv && !items.includes(a.packageName)));
	const otherApps = $derived(apps.filter((a) => !a.tv && !items.includes(a.packageName)));
	const freeFolders = $derived(folders.filter((f) => !items.includes(FOLDER_PREFIX + f.id)));

	function add(e: SubmitEvent) {
		e.preventDefault();
		const value = choice === '*' ? typed.trim() : choice;
		if (!value || items.includes(value)) return;
		if (choice === '*' && !PACKAGE.test(value)) return;
		items.push(value);
		choice = '';
		typed = '';
	}
</script>

{#if items.length === 0}
	<p class="muted small">{empty}</p>
{:else}
	<ol class="apps">
		{#each items as item, i (item)}
			<li>
				<span class="name" title={item}>{label(item)}</span>
				<span class="tools">
					<button
						type="button"
						aria-label="Move {label(item)} earlier"
						disabled={i === 0}
						onclick={() => move(items, i, -1)}>←</button
					>
					<button
						type="button"
						aria-label="Move {label(item)} later"
						disabled={i === items.length - 1}
						onclick={() => move(items, i, 1)}>→</button
					>
					<button type="button" aria-label="Remove {label(item)}" onclick={() => items.splice(i, 1)}
						>✕</button
					>
				</span>
			</li>
		{/each}
	</ol>
{/if}

<form class="row add" onsubmit={add}>
	<select bind:value={choice} aria-label={addLabel}>
		<option value="">{addLabel}…</option>
		{#if freeFolders.length}
			<optgroup label="Folders">
				{#each freeFolders as f (f.id)}<option value={FOLDER_PREFIX + f.id}>📁 {f.name}</option
					>{/each}
			</optgroup>
		{/if}
		{#if tvApps.length}
			<optgroup label="TV apps">
				{#each tvApps as a (a.packageName)}<option value={a.packageName}>{a.label}</option>{/each}
			</optgroup>
		{/if}
		{#if otherApps.length}
			<optgroup label="Other apps">
				{#each otherApps as a (a.packageName)}<option value={a.packageName}>{a.label}</option
					>{/each}
			</optgroup>
		{/if}
		<option value="*">Type a package name…</option>
	</select>
	{#if choice === '*'}
		<input
			bind:value={typed}
			placeholder="com.example.app"
			aria-label="Package name"
			pattern={PACKAGE.source}
			required
		/>
	{/if}
	<button disabled={!choice || (choice === '*' && !PACKAGE.test(typed.trim()))}>Add</button>
</form>

<style>
	.apps {
		list-style: none;
		margin: 0 0 0.75rem;
		padding: 0;
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}
	.apps li {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		background: var(--panel-2);
		border: 1px solid var(--line);
		border-radius: 8px;
		padding: 0.3rem 0.3rem 0.3rem 0.7rem;
		max-width: 100%;
	}
	.name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 14rem;
	}
	.tools {
		display: flex;
		gap: 0.15rem;
	}
	.tools button {
		padding: 0.1rem 0.45rem;
		font-size: 0.85rem;
		background: transparent;
		border-color: transparent;
	}
	.tools button:hover:not(:disabled) {
		border-color: var(--line);
	}
	.add select,
	.add input {
		width: auto;
		flex: 1 1 12rem;
	}
</style>
