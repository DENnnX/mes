import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { rpcCall } from '@/api/rpc';

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('lc_token') || '');
  const currentUser = ref(null);
  const authChecking = ref(true);
  const publicAppsList = ref([]);

  let restorePromise = null;

  const isAuthenticated = computed(() => !!currentUser.value);
  const isSystemAdmin = computed(() => !!currentUser.value?.isSystemAdmin);
  const canAccessStudio = computed(() => !!currentUser.value?.canAccessStudio);
  const canManageTenants = computed(() => !!currentUser.value?.canManageTenants);

  const authorizedAppLabelsText = computed(() => {
    const arr = currentUser.value?.authorizedApps || [];
    if (arr.length === 0) return canAccessStudio.value ? '管理底座 / 暂无业务应用' : '暂无授权应用';
    return arr.map((a) => a.displayName).join('、');
  });

  const callerHasMenuRestriction = computed(() => {
    if (isSystemAdmin.value) return false;
    const am = currentUser.value?.tenant?.authorizedMenus;
    return Array.isArray(am) && am.length > 0 && !am.includes('*');
  });

  function defaultHomePath() {
    if (canAccessStudio.value) return '/studio/overview';
    if (canManageTenants.value) return '/tenant-admin';
    return '/runtime';
  }

  async function loadPublicApps() {
    try {
      publicAppsList.value = (await rpcCall('sys_portal', 'publicApps', {}, '/rpc/open/service', '')) || [];
    } catch {
      publicAppsList.value = [];
    }
  }

  async function login(username, password) {
    const res = await rpcCall('rbac_user', 'login', { username: username.trim(), password }, '/rbac_tenant_root/rpc/service', '');
    token.value = res.token;
    currentUser.value = res.user;
    localStorage.setItem('lc_token', res.token);
    return res;
  }

  async function refreshProfile() {
    const prof = await rpcCall('rbac_user', 'profile', {});
    if (prof && prof.username) {
      currentUser.value = prof;
    }
    return prof;
  }

  async function logout() {
    try {
      if (token.value) {
        await rpcCall('rbac_user', 'logout', {});
      }
    } catch {
      // ignore logout RPC error
    }
    token.value = '';
    currentUser.value = null;
    localStorage.removeItem('lc_token');
    await loadPublicApps();
  }

  async function changePassword(oldPassword, newPassword) {
    return rpcCall('rbac_user', 'changePassword', { oldPassword, newPassword });
  }

  async function ensureRestored() {
    if (restorePromise) return restorePromise;
    restorePromise = (async () => {
      authChecking.value = true;
      await loadPublicApps();
      if (token.value) {
        try {
          const prof = await rpcCall('rbac_user', 'profile', {});
          if (prof && prof.username) {
            currentUser.value = prof;
            authChecking.value = false;
            return;
          }
        } catch {
          token.value = '';
          localStorage.removeItem('lc_token');
        }
      }
      currentUser.value = null;
      authChecking.value = false;
    })();
    return restorePromise;
  }

  return {
    token,
    currentUser,
    authChecking,
    publicAppsList,
    isAuthenticated,
    isSystemAdmin,
    canAccessStudio,
    canManageTenants,
    authorizedAppLabelsText,
    callerHasMenuRestriction,
    defaultHomePath,
    loadPublicApps,
    login,
    refreshProfile,
    logout,
    changePassword,
    ensureRestored
  };
});
