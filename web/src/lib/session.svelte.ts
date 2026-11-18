import type { Admin } from './api';

/** Who is signed in. Set by the root layout; null while signed out. */
export const session = $state<{ admin: Admin | null; checked: boolean; setupNeeded: boolean }>({
	admin: null,
	checked: false,
	setupNeeded: false
});
