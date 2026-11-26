<template>
  <div>
    <header class="lc-section-heading">
      <h1 class="lc-page-title">管理中心</h1>
      <el-button @click="enterRuntime">进入应用工作区</el-button>
    </header>
    <div class="lc-welcome-banner lc-dotted studio-feature">
      <div>
        <h2>应用设计与发布</h2>
        <p>{{ appStore.businessAppsOnly.length }} 个业务应用 · {{ appStore.businessModels.length }} 个业务模型</p>
      </div>
      <div class="lc-page-actions">
        <el-button plain @click="openNewDesigner">新建数据表</el-button>
        <el-button type="primary" @click="goToNewApp">新建业务应用</el-button>
      </div>
    </div>

    <div class="lc-stat-row">
      <div class="lc-stat-box">
        <div class="lc-stat-label">已搭建业务应用</div>
        <div class="lc-stat-value">{{ appStore.businessAppsOnly.length }}</div>
      </div>
      <div class="lc-stat-box">
        <div class="lc-stat-label">业务数据表（含系统表）</div>
        <div class="lc-stat-value">
          {{ appStore.businessModels.length }}
          <span style="font-size:13px;color:var(--text-muted);font-weight:400;">/ {{ appStore.modelsSummary.length }}</span>
        </div>
      </div>
      <div class="lc-stat-box">
        <div class="lc-stat-label">入驻企业租户 / 字典项</div>
        <div class="lc-stat-value">
          {{ appStore.tenantsList.length }}
          <span style="font-size:13px;color:var(--text-muted);font-weight:400;">/ {{ appStore.dictTypes.length }}</span>
        </div>
      </div>
      <div class="lc-stat-box">
        <div class="lc-stat-label">可用菜单页 / 待审清理</div>
        <div class="lc-stat-value">
          {{ appStore.runtimeLeafMenusCount }}
          <span style="font-size:13px;color:var(--text-muted);font-weight:400;">/ {{ appStore.pendingPurgeCount }}</span>
        </div>
      </div>
    </div>

    <div class="lc-card">
      <div class="lc-card-header" style="margin-bottom:8px;">
        <h3 class="lc-card-title">应用上线流程</h3>
      </div>
      <div class="lc-wizard-steps">
        <button type="button" class="lc-wizard-step" @click="router.push('/studio/apps')">
          <div><span class="lc-wizard-num">1</span><strong>创建应用</strong></div>
          <div style="font-size:12px;color:var(--text-muted);margin-top:4px;">新建业务应用或导入配置包</div>
        </button>
        <button type="button" class="lc-wizard-step" @click="router.push('/studio/models')">
          <div><span class="lc-wizard-num">2</span><strong>设计数据表</strong></div>
          <div style="font-size:12px;color:var(--text-muted);margin-top:4px;">配置字段、校验、字典与关联</div>
        </button>
        <button type="button" class="lc-wizard-step" @click="router.push('/studio/views_menus')">
          <div><span class="lc-wizard-num">3</span><strong>编排页面菜单</strong></div>
          <div style="font-size:12px;color:var(--text-muted);margin-top:4px;">可视化微调列表搜索表单布局</div>
        </button>
        <button type="button" class="lc-wizard-step" @click="router.push('/studio/iam')">
          <div><span class="lc-wizard-num">4</span><strong>租户授权管控</strong></div>
          <div style="font-size:12px;color:var(--text-muted);margin-top:4px;">创建租户并勾选可登录应用</div>
        </button>
        <button type="button" class="lc-wizard-step" @click="enterRuntime">
          <div><span class="lc-wizard-num">5</span><strong>打开运行使用</strong></div>
          <div style="font-size:12px;color:var(--text-muted);margin-top:4px;">切换工作区体验实际业务操作</div>
        </button>
      </div>
    </div>

    <el-row :gutter="14">
      <el-col :xs="24" :sm="14">
        <div class="lc-card" style="min-height:280px;">
          <div class="lc-card-header">
            <h3 class="lc-card-title">我的业务应用</h3>
            <el-button link type="primary" size="small" @click="router.push('/studio/apps')">全部应用 →</el-button>
          </div>
          <div v-if="appStore.businessAppsOnly.length === 0" style="text-align:center;padding:34px 16px;color:var(--text-muted);">
            <p style="margin:0 0 12px;">当前平台还没有搭建任何业务应用，立即开始创建您的第一个应用吧！</p>
            <el-button type="primary" size="small" @click="goToNewApp">+ 创建第一个应用</el-button>
            <el-button size="small" @click="router.push('/studio/apps')">导入配置包</el-button>
          </div>
          <div v-else class="lc-grid-cards">
            <div v-for="ap in appStore.businessAppsOnly" :key="ap.name" class="lc-grid-card-item">
              <div style="display:flex;justify-content:space-between;align-items:center;">
                <strong style="font-size:14.5px;">{{ ap.displayName }}</strong>
                <el-tag size="small">v{{ ap.version }}</el-tag>
              </div>
              <div style="font-size:12px;color:var(--text-muted);margin:6px 0;">
                包含 {{ appStore.businessModels.filter(m => m.appName === ap.name).length }} 张数据表 · 授权 {{ appStore.getTenantsForApp(ap.name).length }} 个租户
              </div>
              <div style="font-size:12px;color:var(--text-muted);min-height:32px;">{{ ap.description || '暂无应用说明' }}</div>
              <div style="margin-top:8px;display:flex;gap:8px;">
                <el-button size="small" type="primary" plain @click="router.push('/studio/models')">设计数据表</el-button>
                <el-button size="small" @click="jumpToAppAndSwitch(ap.name)">进入应用</el-button>
              </div>
            </div>
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :sm="10">
        <div class="lc-card" style="min-height:280px;">
          <div class="lc-card-header">
            <h3 class="lc-card-title">近期版本发布动态</h3>
            <el-button size="small" @click="appStore.loadPublishTimeline()">刷新</el-button>
          </div>
          <div v-if="appStore.publishTimeline.length === 0" style="padding:24px;text-align:center;color:var(--text-muted);font-size:12.5px;">暂无发布记录</div>
          <el-timeline v-else style="padding-left:6px;max-height:260px;overflow-y:auto;">
            <el-timeline-item
              v-for="pr in appStore.publishTimeline.slice(0, 8)"
              :key="pr.id"
              :timestamp="pr.operatedAt"
              :type="pr.actionType === 'ROLLBACK' ? 'warning' : 'success'"
              placement="top"
            >
              <div style="font-size:12.5px;color:var(--text-main);">
                <strong>{{ pr.modelName }}</strong> · v{{ pr.toVersionNo }}
                <el-tag size="small" style="margin-left:4px;">{{ pr.actionType === 'ROLLBACK' ? '版本回滚' : '发布上线' }}</el-tag>
              </div>
              <div style="font-size:11.5px;color:var(--text-muted);">操作人：{{ pr.operatorId }}</div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAppStore } from '@/stores/app';

const router = useRouter();
const appStore = useAppStore();

function goToNewApp() {
  appStore.appCreateDialogTrigger++;
  router.push('/studio/apps');
}

function openNewDesigner() {
  appStore.designerDialogState.isEdit = false;
  appStore.designerDialogState.modelToOpen = '';
  appStore.designerDialogState.visible = true;
}

async function enterRuntime() {
  await appStore.loadMenuTree();
  await router.push('/runtime');
}

async function jumpToAppAndSwitch(appName) {
  await appStore.loadMenuTree();
  const leaf = appStore.findFirstLeafMenu(appName);
  if (leaf?.routePath) {
    await router.push(leaf.routePath);
  } else {
    ElMessage.info('应用 [' + appStore.resolveAppDisplayName(appName) + '] 暂未生成菜单页面，可先在管理中心创建数据表');
    await router.push('/runtime');
  }
}

onMounted(() => {
  appStore.loadStudioOverview();
  appStore.loadPublishTimeline();
});
</script>

<style scoped>
.studio-feature { min-height: 132px; padding: 20px 24px; }
.studio-feature h2 { margin: 0 0 5px; color: var(--text-main); font-size: clamp(21px, 2.1vw, 27px); }
.studio-feature p { margin: 0; color: var(--text-muted); }
.studio-feature :deep(.el-button:not(.el-button--primary)) { background: var(--bg-card); }
.lc-wizard-step { font: inherit; color: var(--text-main); text-align: left; }
.lc-wizard-step:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
</style>
