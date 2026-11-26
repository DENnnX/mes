<template>
  <div>
    <div class="lc-welcome-banner">
      <div>
        <h2 style="margin:0 0 6px;font-size:19px;color:#0f172a;">{{ appStore.greetingText }}，{{ authStore.currentUser?.displayName }}</h2>
        <div style="font-size:13px;color:#475569;">
          当前租户空间：<strong>{{ authStore.currentUser?.tenantName || authStore.currentUser?.tenantId }}</strong> · 以下为已为您授权开通的全部应用系统
        </div>
      </div>
      <el-button v-if="authStore.canAccessStudio" type="primary" size="small" @click="router.push('/studio/overview')">返回管理中心</el-button>
    </div>

    <div v-if="(authStore.currentUser?.authorizedApps || []).length === 0" class="lc-card" style="text-align:center;padding:42px 24px;">
      <h3 style="margin:0 0 8px;color:#0f172a;">暂无已开通的业务应用</h3>
      <p style="color:#64748b;font-size:13.5px;">
        {{ authStore.canAccessStudio ? '您还没有创建业务应用或配置菜单，前往管理中心即可几分钟搭出一个完整应用。' : '当前租户尚未被授权使用业务应用，请联系系统管理员配置应用授权。' }}
      </p>
      <div v-if="authStore.canAccessStudio" style="margin-top:16px;">
        <el-button type="primary" @click="router.push('/studio/apps')">前往新建应用</el-button>
      </div>
    </div>

    <div v-else class="lc-grid-cards">
      <div v-for="ap in authStore.currentUser.authorizedApps" :key="ap.name" class="lc-grid-card-item">
        <div style="display:flex;justify-content:space-between;align-items:center;">
          <strong style="font-size:16px;color:#0f172a;">{{ ap.displayName }}</strong>
          <el-tag size="small" type="success">已授权</el-tag>
        </div>
        <div style="font-size:12px;color:#64748b;margin:6px 0;">版本 v{{ ap.version }} · 分类 {{ ap.category || '通用' }}</div>
        <div style="font-size:13px;color:#475569;min-height:40px;line-height:1.5;">{{ ap.description || '点击下方按钮进入该应用处理日常业务数据。' }}</div>
        <div style="margin-top:12px;">
          <el-button type="primary" size="small" style="width:100%;" @click="jumpToAppFirstMenu(ap.name)">进入使用 →</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';

const router = useRouter();
const authStore = useAuthStore();
const appStore = useAppStore();

async function jumpToAppFirstMenu(appName) {
  await appStore.loadMenuTree();
  const leaf = appStore.findFirstLeafModel(appStore.menuTree, appName);
  if (leaf) {
    await router.push('/m/' + leaf.model);
  } else {
    ElMessage.info('应用 [' + appStore.resolveAppDisplayName(appName) + '] 暂未生成菜单页面，可先在管理中心创建数据表');
  }
}
</script>
