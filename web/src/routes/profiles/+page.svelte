<script lang="ts">
	import { onMount } from 'svelte';
	import { api, ago, profileIdFrom, type ProfileSummary } from '$lib/api';

	let profiles = $state<ProfileSummary[]>([]);
	let error = $state('');
	let name = $state('');
	let copyFrom = $state('');
	let busy = $state(false);

	async function load() {
		try {
			profiles = await api.profiles();
		} catch (err) {
			error = (err as Error).message;
		}
	}

	onMount(load);

	async function create(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		error = '';
		try {
			let id = profileIdFrom(name);
			if (profiles.some((p) => p.id === id)) id += '_' + Date.now().toString(36).slice(-4);
			await api.createProfile(id, name.trim(), copyFrom || undefined);
			name = '';
			await load();
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}

	let pushed = $state('');

	async function push(p: ProfileSummary) {
		if (
			!confirm(
				`Make every TV use the server's copy of ${p.name}? Changes made on a TV that haven't reached the server yet are dropped.`
			)
		)
			return;
		try {
			await api.pushProfile(p.id);
			pushed = `${p.name}: TVs will use the server's copy.`;
		} catch (err) {
			error = (err as Error).message;
		}
	}

	async function remove(p: ProfileSummary) {
		if (!confirm(`Delete the ${p.name} profile on every TV? This can't be undone.`)) return;
		await api.deleteProfile(p.id).catch((err) => (error = err.message));
		await load();
	}
</script>

<h1>Profiles</h1>
{#if error}<p class="error" role="alert">{error}</p>{/if}
{#if pushed}<p role="status">{pushed}</p>{/if}

<section class="panel">
	<table class="stack">
		<thead><tr><th>Name</th><th>Id</th><th>Version</th><th>Changed</th><th></th></tr></thead>
		<tbody>
			{#each profiles as p (p.id)}
				<tr>
					<td><a href="/profiles/edit?id={encodeURIComponent(p.id)}">{p.name}</a></td>
					<td class="muted code small">{p.id}</td>
					<td class="muted">{p.version}</td>
					<td class="muted">{ago(p.updated)}</td>
					<td class="row">
						<a class="button" href="/profiles/edit?id={encodeURIComponent(p.id)}">Edit</a>
						<button onclick={() => push(p)}>Push to all TVs</button>
						<button class="danger" disabled={profiles.length < 2} onclick={() => remove(p)}
							>Delete</button
						>
					</td>
				</tr>
			{/each}
		</tbody>
	</table>
</section>

<form class="panel" onsubmit={create}>
	<h2>New profile</h2>
	<label
		><span>Name</span><input bind:value={name} maxlength="40" required placeholder="Kids" /></label
	>
	<label
		><span>Start from</span>
		<select bind:value={copyFrom}>
			<option value="">An empty layout</option>
			{#each profiles as p (p.id)}<option value={p.id}>A copy of {p.name}</option>{/each}
		</select>
	</label>
	<button class="primary" disabled={busy}>Create</button>
</form>
