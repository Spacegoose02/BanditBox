<script lang="ts">
	import type { ProfileSummary } from '$lib/api';

	// An empty selection means "every profile", matching the server.
	let { profiles, selected = $bindable([]) }: { profiles: ProfileSummary[]; selected: string[] } =
		$props();

	let all = $state(selected.length === 0);

	function setAll(v: boolean) {
		all = v;
		selected = v ? [] : profiles.slice(0, 1).map((p) => p.id);
	}

	function toggle(id: string, on: boolean) {
		selected = on ? [...selected, id] : selected.filter((s) => s !== id);
	}
</script>

<fieldset class="checks">
	<legend class="muted small">Profiles this TV can use</legend>
	<label><input type="radio" checked={all} onchange={() => setAll(true)} /> All profiles</label>
	<label><input type="radio" checked={!all} onchange={() => setAll(false)} /> Only these:</label>
	{#if !all}
		<div class="list">
			{#each profiles as p (p.id)}
				<label
					><input
						type="checkbox"
						checked={selected.includes(p.id)}
						onchange={(e) => toggle(p.id, e.currentTarget.checked)}
					/>
					{p.name}</label
				>
			{/each}
		</div>
	{/if}
</fieldset>

<style>
	fieldset {
		border: 0;
		padding: 0;
		margin: 0 0 1rem;
	}
	legend {
		margin-bottom: 0.4rem;
	}
	.list {
		padding-left: 1.6rem;
	}
</style>
