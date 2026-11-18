<script lang="ts">
	import { goto } from '$app/navigation';
	import { session } from '$lib/session.svelte';

	$effect(() => {
		if (session.admin) goto('/devices', { replaceState: true });
	});
</script>
