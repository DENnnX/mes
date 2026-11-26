import { createRouter, createWebHashHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { STUDIO_TITLES, useAppStore } from '@/stores/app';

import LoginView from '@/views/LoginView.vue';
import MainLayout from '@/layouts/MainLayout.vue';
import OverviewView from '@/views/studio/OverviewView.vue';
import AppsView from '@/views/studio/AppsView.vue';
import ModelsView from '@/views/studio/ModelsView.vue';
import ViewsMenusView from '@/views/studio/ViewsMenusView.vue';
import DictView from '@/views/studio/DictView.vue';
import CodeRuleView from '@/views/studio/CodeRuleView.vue';
import GovernanceView from '@/views/studio/GovernanceView.vue';
import ApiDocView from '@/views/studio/ApiDocView.vue';
import RpcConsoleView from '@/views/studio/RpcConsoleView.vue';
import IamManagementView from '@/views/iam/IamManagementView.vue';
import RuntimePortalView from '@/views/runtime/RuntimePortalView.vue';
import DynamicModelView from '@/views/runtime/DynamicModelView.vue';

const routes = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { guest: true }
  },
  {
    path: '/',
    component: MainLayout,
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'root-redirect', component: RuntimePortalView },
      { path: 'studio/overview', name: 'studio-overview', component: OverviewView, meta: { mode: 'studio', tab: 'overview', requiresStudio: true } },
      { path: 'studio/apps', name: 'studio-apps', component: AppsView, meta: { mode: 'studio', tab: 'apps', requiresStudio: true } },
      { path: 'studio/models', name: 'studio-models', component: ModelsView, meta: { mode: 'studio', tab: 'models', requiresStudio: true } },
      { path: 'studio/views_menus', name: 'studio-views_menus', component: ViewsMenusView, meta: { mode: 'studio', tab: 'views_menus', requiresStudio: true } },
      { path: 'studio/iam', name: 'studio-iam', component: IamManagementView, meta: { mode: 'studio', tab: 'iam', requiresStudio: true } },
      { path: 'studio/dict', name: 'studio-dict', component: DictView, meta: { mode: 'studio', tab: 'dict', requiresStudio: true } },
      { path: 'studio/code_rule', name: 'studio-code_rule', component: CodeRuleView, meta: { mode: 'studio', tab: 'code_rule', requiresStudio: true } },
      { path: 'studio/governance', name: 'studio-governance', component: GovernanceView, meta: { mode: 'studio', tab: 'governance', requiresStudio: true } },
      { path: 'studio/api_doc', name: 'studio-api_doc', component: ApiDocView, meta: { mode: 'studio', tab: 'api_doc', requiresStudio: true } },
      { path: 'studio/rpc_console', name: 'studio-rpc_console', component: RpcConsoleView, meta: { mode: 'studio', tab: 'rpc_console', requiresStudio: true } },
      { path: 'tenant-admin', name: 'tenant-admin', component: IamManagementView, meta: { mode: 'tenant_admin', tab: 'iam', requiresTenantAdmin: true } },
      { path: 'runtime', name: 'runtime-home', component: RuntimePortalView, meta: { mode: 'runtime' } },
      { path: 'm/:model', name: 'runtime-model', component: DynamicModelView, meta: { mode: 'runtime' } }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
];

const router = createRouter({
  history: createWebHashHistory(),
  routes
});

router.beforeEach(async (to) => {
  const authStore = useAuthStore();
  const appStore = useAppStore();

  await authStore.ensureRestored();

  if (!authStore.currentUser) {
    if (to.path !== '/login') {
      return '/login';
    }
    return true;
  }

  if (to.path === '/login' || to.path === '/') {
    return authStore.defaultHomePath();
  }

  if (to.meta.requiresStudio && !authStore.canAccessStudio) {
    return authStore.defaultHomePath();
  }

  if (to.meta.requiresTenantAdmin && !authStore.canManageTenants) {
    return authStore.defaultHomePath();
  }

  if (to.meta.mode === 'studio') {
    appStore.workspaceMode = 'studio';
    const tab = to.meta.tab || 'overview';
    appStore.studioTab = tab;
    appStore.ensureTab('studio:' + tab, STUDIO_TITLES[tab] || tab, 'studio', tab);
  } else if (to.meta.mode === 'tenant_admin') {
    appStore.workspaceMode = 'tenant_admin';
    appStore.studioTab = 'iam';
    appStore.openTabs = [];
  } else if (to.meta.mode === 'runtime') {
    appStore.workspaceMode = 'runtime';
  }

  return true;
});

export default router;
