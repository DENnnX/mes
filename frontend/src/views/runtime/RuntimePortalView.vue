<template>
  <div class="portal">
    <header class="lc-section-heading">
      <h1 class="lc-page-title">应用工作区</h1>
      <el-button v-if="authStore.canAccessStudio" @click="router.push('/studio/overview')">管理中心</el-button>
    </header>

    <div v-if="apps.length" class="portal-overview">
      <section class="portal-feature lc-dotted" aria-label="应用入口">
        <div class="portal-feature-heading">
          <span class="portal-icon"><el-icon><Grid /></el-icon></span>
          <el-tag v-if="featured.version" effect="plain">v{{ featured.version }}</el-tag>
        </div>
        <h2>{{ featured.displayName }}</h2>
        <p v-if="featured.description">{{ featured.description }}</p>
        <div v-if="featuredMenus.length" class="lc-page-actions portal-shortcuts" aria-label="应用页面">
          <el-button v-for="menu in featuredMenus.slice(0, 4)" :key="menu.name" plain @click="router.push(menu.routePath)">
            {{ menu.displayName }}
          </el-button>
          <el-button type="primary" :icon="Right" @click="jumpToFirstMenu(featured.name)">打开应用</el-button>
        </div>
        <p v-else class="portal-shortcuts">该应用暂无可访问页面。</p>
      </section>

      <section class="lc-card portal-scope" aria-label="当前工作区">
        <h2 class="lc-card-title">工作区概况</h2>
        <dl>
          <div><dt>当前租户</dt><dd>{{ authStore.currentUser?.tenantName || authStore.currentUser?.tenantId }}</dd></div>
          <div><dt>已授权应用</dt><dd>{{ apps.length }}</dd></div>
          <div><dt>可访问页面</dt><dd>{{ menuLinks.length }}</dd></div>
        </dl>
      </section>
    </div>

    <section v-if="apps.length > 1">
      <header class="lc-section-heading"><h2>全部授权应用</h2><span>{{ apps.length }} 个应用</span></header>
      <div class="lc-grid-cards">
        <article v-for="app in apps" :key="app.name" class="lc-grid-card-item portal-app">
          <h3>{{ app.displayName }}</h3>
          <p v-if="app.description">{{ app.description }}</p>
          <div class="portal-app-actions">
            <span v-if="app.version">v{{ app.version }}</span>
            <el-button text type="primary" :icon="Right" @click="jumpToFirstMenu(app.name)">打开应用</el-button>
          </div>
        </article>
      </div>
    </section>

    <el-empty v-else-if="!apps.length" class="lc-card portal-empty" description="暂无已授权的应用">
      <el-button v-if="authStore.canAccessStudio" type="primary" @click="router.push('/studio/apps')">进入应用管理</el-button>
      <p v-else>请联系管理员授权应用。</p>
    </el-empty>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Right } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';

const router = useRouter();
const authStore = useAuthStore();
const appStore = useAppStore();
const apps = computed(() => authStore.currentUser?.authorizedApps || []);
const menuLinks = computed(() => appStore.runtimeNavigation.map(({ node }) => node).filter((node) => node.routePath));
const featured = computed(() => apps.value.find((app) => menuLinks.value.some((menu) => menu.appName === app.name)) || apps.value[0]);
const featuredMenus = computed(() => menuLinks.value.filter((menu) => menu.appName === featured.value?.name));

async function jumpToFirstMenu(appName) {
  if (!appStore.menuLoaded) await appStore.loadMenuTree();
  const menu = appStore.findFirstLeafMenu(appName);
  if (menu) await router.push(menu.routePath);
  else ElMessage.info('该应用暂无可打开的页面');
}
</script>

<style scoped>
.portal-overview { display: grid; grid-template-columns: minmax(0,2.35fr) minmax(200px,1fr); gap: 14px; margin-bottom: 24px; }
.portal-feature { padding: 22px; border: 1px solid var(--border-light); border-radius: var(--radius-panel); }
.portal-feature-heading, .portal-app-actions { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.portal-icon { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 9px; color: var(--primary); background: var(--bg-card); }
.portal-feature h2 { margin: 11px 0 5px; font-size: clamp(21px, 2.1vw, 28px); }
.portal-feature p { margin: 0; color: var(--text-muted); }
.portal-feature .portal-shortcuts { margin-top: 18px; }
.portal-shortcuts :deep(.el-button:not(.el-button--primary)) { background: var(--bg-card); }
.portal-scope { margin-bottom: 0; }
.portal-scope dl { margin: 9px 0 0; }
.portal-scope dl > div { display: flex; justify-content: space-between; gap: 10px; padding: 10px 0; border-top: 1px solid var(--border-light); }
.portal-scope dt, .portal-app p, .portal-app-actions span, .portal-empty p { color: var(--text-muted); }
.portal-scope dd { margin: 0; text-align: right; font-variant-numeric: tabular-nums; font-weight: 640; }
.portal-app h3 { margin: 0 0 6px; font-size: 16px; }
.portal-app p { margin: 0 0 8px; font-size: 13px; }
.portal-app-actions { margin-top: 10px; font-size: 12px; }
.portal-app-actions :deep(button) { margin-left: auto; }
@media (max-width: 700px) { .portal-overview { grid-template-columns: minmax(0,1fr); } .portal-feature { padding: 16px; } }
</style>
