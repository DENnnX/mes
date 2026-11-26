<template>
  <div v-if="authStore.currentUser" class="lc-shell">
    <header class="lc-header">
      <div class="lc-brand">
        <div class="lc-logo">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
            <rect x="3" y="3" width="7" height="7" rx="1.5" />
            <rect x="14" y="3" width="7" height="7" rx="1.5" />
            <rect x="3" y="14" width="7" height="7" rx="1.5" />
            <path d="M14 17.5h7M17.5 14v7" />
          </svg>
        </div>
        <div>
          <span class="lc-title">云枢低代码应用平台</span>
          <span class="lc-sub">{{ authStore.currentUser.tenantName || '默认空间' }}</span>
        </div>
      </div>

      <div class="lc-header-actions">
        <el-radio-group v-if="authStore.canAccessStudio" v-model="workspaceModel" size="small" @change="onWorkspaceChange">
          <el-radio-button value="studio">管理中心</el-radio-button>
          <el-radio-button value="runtime">应用工作区</el-radio-button>
        </el-radio-group>
        <el-radio-group v-else-if="authStore.canManageTenants" v-model="workspaceModel" size="small" @change="onWorkspaceChange">
          <el-radio-button value="tenant_admin">分站与授权</el-radio-button>
          <el-radio-button value="runtime">应用工作区</el-radio-button>
        </el-radio-group>
        <el-tag v-else type="primary" effect="light" size="small">
          当前租户：{{ authStore.currentUser.tenantName || authStore.currentUser.tenantId }}
        </el-tag>

        <el-select v-model="appStore.themeName" size="small" style="width:86px;" @change="appStore.applyTheme">
          <el-option value="indigo" label="科技蓝" />
          <el-option value="crimson" label="绛红色" />
          <el-option value="slate" label="青黛色" />
        </el-select>

        <el-dropdown trigger="click" @command="handleUserMenuCommand">
          <el-button size="small" type="primary" plain>
            <el-icon style="margin-right:4px;"><User /></el-icon>
            <span>{{ authStore.currentUser.displayName }}</span>
            <el-tag v-if="authStore.isSystemAdmin" size="small" type="danger" style="margin-left:5px;">系统管理员</el-tag>
            <el-tag v-else-if="authStore.canManageTenants" size="small" type="warning" style="margin-left:5px;">租户主管</el-tag>
            <el-icon style="margin-left:4px;"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>所属租户：{{ authStore.currentUser.tenantName || authStore.currentUser.tenantId }}</el-dropdown-item>
              <el-dropdown-item disabled>可用应用：{{ authStore.authorizedAppLabelsText }}</el-dropdown-item>
              <el-dropdown-item divided command="openChangePwd">修改个人密码</el-dropdown-item>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <!-- 顶部多页签条 -->
    <div class="lc-tabsbar" v-if="appStore.openTabs.length > 0">
      <span style="font-size:11.5px;color:#94a3b8;margin-right:4px;">已开页面:</span>
      <div
        v-for="t in appStore.openTabs"
        :key="t.id"
        class="lc-tab-pill"
        :class="{ active: appStore.activeTabId === t.id }"
        @click="activateTab(t)"
      >
        <span>{{ t.title }}</span>
        <span v-if="appStore.openTabs.length > 1" class="lc-tab-close" @click.stop="handleCloseTab(t.id)">×</span>
      </div>
    </div>

    <div class="lc-body">
      <!-- 左侧导航栏 -->
      <aside class="lc-sidebar">
        <template v-if="appStore.workspaceMode === 'studio' && authStore.canAccessStudio">
          <div class="lc-side-section">工作台</div>
          <div class="lc-side-scroll">
            <div class="lc-nav-item" :class="{ active: appStore.studioTab === 'overview' }" @click="switchStudioTab('overview')">
              <el-icon><House /></el-icon><span>工作台概览</span>
            </div>

            <div class="lc-side-section">应用设计中心</div>
            <div class="lc-nav-item" :class="{ active: appStore.studioTab === 'apps' }" @click="switchStudioTab('apps')">
              <el-icon><FolderOpened /></el-icon><span>应用管理</span>
            </div>
            <div class="lc-nav-item" :class="{ active: appStore.studioTab === 'models' }" @click="switchStudioTab('models')">
              <el-icon><Box /></el-icon><span>表单与数据表</span>
            </div>
            <div class="lc-nav-item" :class="{ active: appStore.studioTab === 'views_menus' }" @click="switchStudioTab('views_menus')">
              <el-icon><Grid /></el-icon><span>页面与菜单编排</span>
            </div>

            <div class="lc-side-section">租户与基础数据</div>
            <div class="lc-nav-item" :class="{ active: appStore.studioTab === 'iam' }" @click="switchStudioTab('iam')">
              <el-icon><UserFilled /></el-icon><span>租户与应用授权</span>
            </div>
            <div class="lc-nav-item" :class="{ active: appStore.studioTab === 'dict' }" @click="switchStudioTab('dict')">
              <el-icon><Collection /></el-icon><span>常用选项字典</span>
            </div>
            <div class="lc-nav-item" :class="{ active: appStore.studioTab === 'code_rule' }" @click="switchStudioTab('code_rule')">
              <el-icon><Ticket /></el-icon><span>自动编号规则</span>
            </div>

            <div class="lc-side-section">安全治理与扩展工具</div>
            <div class="lc-nav-item" :class="{ active: appStore.studioTab === 'governance' }" @click="switchStudioTab('governance')">
              <el-icon><Lock /></el-icon><span>停用与安全清理</span>
            </div>
            <div class="lc-nav-item" :class="{ active: appStore.studioTab === 'api_doc' }" @click="switchStudioTab('api_doc')">
              <el-icon><Reading /></el-icon><span>开放接口文档</span>
            </div>
            <div class="lc-nav-item" :class="{ active: appStore.studioTab === 'rpc_console' }" @click="switchStudioTab('rpc_console')">
              <el-icon><Promotion /></el-icon><span>接口调试工具</span>
            </div>
          </div>
        </template>

        <template v-else-if="appStore.workspaceMode === 'tenant_admin' && authStore.canManageTenants">
          <div class="lc-side-section">本级与下属管理</div>
          <div class="lc-side-scroll">
            <div class="lc-nav-item active" @click="router.push('/tenant-admin')">
              <el-icon><UserFilled /></el-icon><span>分站与应用授权</span>
            </div>
            <div class="lc-nav-item" @click="onWorkspaceChange('runtime')">
              <el-icon><House /></el-icon><span>进入应用工作区</span>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="lc-side-section">应用功能导航</div>
          <div class="lc-side-scroll">
            <div class="lc-nav-item" :class="{ active: !currentRuntimeModel }" @click="router.push('/runtime')">
              <el-icon><House /></el-icon><span>全部授权应用</span>
            </div>
            <div v-if="appStore.menuTree.length === 0" style="padding: 14px 16px; color: #94a3b8; font-size: 12.5px; line-height: 1.6;">
              当前空间下暂无可用业务页面。请创建并启用应用页面，或联系管理员开通授权。
            </div>
            <template v-for="group in appStore.menuTree" :key="group.name">
              <div
                v-if="group.model"
                class="lc-nav-item"
                :class="{ active: currentRuntimeModel === group.model }"
                @click="openRuntimeModel(group.model)"
              >
                <el-icon><Document /></el-icon>
                <span>{{ group.displayName }}</span>
              </div>
              <template v-else>
                <div class="lc-nav-group-title">
                  <el-icon><FolderOpened /></el-icon>
                  <span>{{ group.displayName }}</span>
                </div>
                <div
                  v-for="child in (group.children || [])"
                  :key="child.name"
                  class="lc-nav-item"
                  :class="{ active: currentRuntimeModel === child.model }"
                  @click="openRuntimeModel(child.model)"
                >
                  <el-icon><Document /></el-icon>
                  <span>{{ child.displayName }}</span>
                </div>
              </template>
            </template>
          </div>
        </template>
      </aside>

      <!-- 主内容区 -->
      <main class="lc-main">
        <router-view />
      </main>
    </div>

    <!-- 全局模型设计器与版本中心弹窗 -->
    <ModelDesignerModal />

    <!-- 修改个人密码弹窗 -->
    <el-dialog v-model="changePwdDialog.visible" title="修改个人登录密码" width="420px" destroy-on-close>
      <el-form label-width="95px" size="small">
        <el-form-item label="当前密码" required>
          <el-input v-model="changePwdDialog.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" required>
          <el-input v-model="changePwdDialog.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认新密码" required>
          <el-input v-model="changePwdDialog.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="changePwdDialog.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="submitChangePassword">确认修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';
import ModelDesignerModal from '@/components/studio/ModelDesignerModal.vue';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const appStore = useAppStore();

const changePwdDialog = reactive({
  visible: false,
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
});

const workspaceModel = computed({
  get: () => appStore.workspaceMode,
  set: (val) => {
    appStore.workspaceMode = val;
  }
});

const currentRuntimeModel = computed(() =>
  route.name === 'runtime-model' ? String(route.params.model || '') : ''
);

function switchStudioTab(tab) {
  router.push('/studio/' + tab);
}

function openRuntimeModel(modelName) {
  if (!modelName) return;
  router.push('/m/' + modelName);
}

function activateTab(tab) {
  appStore.activeTabId = tab.id;
  if (tab.mode === 'studio') {
    router.push('/studio/' + tab.key);
  } else {
    router.push('/m/' + tab.key);
  }
}

function handleCloseTab(id) {
  const nextTab = appStore.closeTab(id);
  if (nextTab) {
    activateTab(nextTab);
  }
}

async function onWorkspaceChange(mode) {
  appStore.workspaceMode = mode;
  if (mode === 'runtime') {
    await appStore.loadMenuTree();
    try {
      await authStore.refreshProfile();
    } catch {
      // ignore
    }
    await router.push('/runtime');
  } else if (mode === 'tenant_admin') {
    appStore.openTabs = [];
    await router.push('/tenant-admin');
  } else {
    await router.push('/studio/' + (appStore.studioTab || 'overview'));
    await appStore.loadStudioOverview();
  }
}

async function handleUserMenuCommand(cmd) {
  if (cmd === 'openChangePwd') {
    changePwdDialog.oldPassword = '';
    changePwdDialog.newPassword = '';
    changePwdDialog.confirmPassword = '';
    changePwdDialog.visible = true;
  } else if (cmd === 'logout') {
    await authStore.logout();
    appStore.resetWorkspaceState();
    ElMessage.info('已安全退出登录');
    await router.push('/login');
  }
}

async function submitChangePassword() {
  if (!changePwdDialog.oldPassword || !changePwdDialog.newPassword) {
    ElMessage.warning('请填写当前密码和新密码');
    return;
  }
  if (changePwdDialog.newPassword !== changePwdDialog.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致');
    return;
  }
  try {
    await authStore.changePassword(changePwdDialog.oldPassword, changePwdDialog.newPassword);
    ElMessage.success('密码修改成功，请妥善保管新密码');
    changePwdDialog.visible = false;
  } catch (e) {
    ElMessage.error(e.message);
  }
}

onMounted(async () => {
  await appStore.loadMenuTree();
  if (authStore.canAccessStudio) {
    await appStore.loadStudioOverview();
  }
});
</script>
