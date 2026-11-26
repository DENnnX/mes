<template>
  <div v-if="authStore.authChecking" style="height:100vh;display:flex;align-items:center;justify-content:center;color:#64748b;font-size:14px;">
    正在验证会话状态...
  </div>

  <div v-else class="lc-login-page">
    <div class="lc-login-card">
      <div class="lc-login-hero">
        <div>
          <div style="display:flex;align-items:center;gap:10px;">
            <div style="width:36px;height:36px;border-radius:10px;background:rgba(255,255,255,0.2);display:flex;align-items:center;justify-content:center;">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
                <rect x="3" y="3" width="7" height="7" rx="1.5" />
                <rect x="14" y="3" width="7" height="7" rx="1.5" />
                <rect x="3" y="14" width="7" height="7" rx="1.5" />
                <path d="M14 17.5h7M17.5 14v7" />
              </svg>
            </div>
            <span style="font-size:15px;font-weight:600;letter-spacing:0.3px;">云枢低代码平台</span>
          </div>
          <h1>模型驱动 · 按需构建</h1>
          <p>在线设计数据表与表单页面，按租户自动隔离并统一分配应用权限。</p>
        </div>
        <div style="font-size:12px;opacity:0.75;">CloudForge · Vue 3 SFC v2.1</div>
      </div>

      <div class="lc-login-form-box">
        <h2 style="margin:0 0 6px;font-size:22px;color:#0f172a;">账号登录</h2>
        <p style="margin:0 0 22px;font-size:13px;color:#64748b;">请输入账号密码，登录后自动进入已授权应用空间</p>

        <el-alert v-if="loginError" :title="loginError" type="error" show-icon :closable="true" @close="loginError = ''" style="margin-bottom:16px;" />

        <el-form label-position="top" size="default" @submit.prevent="submitLoginPage">
          <el-form-item label="账号" required>
            <el-input v-model="loginForm.username" placeholder="请输入用户名或租户账号" prefix-icon="User" @keyup.enter="submitLoginPage" />
          </el-form-item>
          <el-form-item label="密码" required>
            <el-input v-model="loginForm.password" type="password" show-password placeholder="请输入登录密码" prefix-icon="Lock" @keyup.enter="submitLoginPage" />
          </el-form-item>
          <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:18px;">
            <el-checkbox v-model="rememberUsername" size="small">记住账号</el-checkbox>
            <span style="font-size:12px;color:#94a3b8;">忘记密码请联系系统管理员重置</span>
          </div>
          <el-button type="primary" style="width:100%;height:42px;font-size:15px;font-weight:600;" :loading="loginLoading" @click="submitLoginPage">
            登 录
          </el-button>
        </el-form>

        <div class="lc-login-footer-hint">
          <el-popover placement="top-end" :width="320" trigger="click">
            <template #reference>
              <el-button link size="small" style="color:#64748b;">系统初始账号说明</el-button>
            </template>
            <div style="font-size:12.5px;line-height:1.7;">
              <div style="font-weight:600;margin-bottom:6px;color:#0f172a;">系统初始化默认账号（初始密码 123456）：</div>
              <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;">
                <span><code>admin</code> — 系统管理员（全部应用与管理）</span>
                <el-button link type="primary" size="small" @click="fillPresetAccount('admin')">填入</el-button>
              </div>
              <div style="display:flex;justify-content:space-between;align-items:center;">
                <span><code>user</code> — 示例业务用户（应用使用端）</span>
                <el-button link type="primary" size="small" @click="fillPresetAccount('user')">填入</el-button>
              </div>
            </div>
          </el-popover>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';

const router = useRouter();
const authStore = useAuthStore();
const appStore = useAppStore();

const rememberUsername = ref(true);
const loginForm = reactive({
  username: localStorage.getItem('lc_saved_user') || '',
  password: ''
});
const loginLoading = ref(false);
const loginError = ref('');

function fillPresetAccount(username) {
  loginForm.username = username;
  loginForm.password = '123456';
}

async function submitLoginPage() {
  if (!loginForm.username.trim() || !loginForm.password) {
    loginError.value = '请输入账号和密码';
    return;
  }
  loginError.value = '';
  loginLoading.value = true;
  try {
    if (rememberUsername.value) {
      localStorage.setItem('lc_saved_user', loginForm.username.trim());
    } else {
      localStorage.removeItem('lc_saved_user');
    }
    const res = await authStore.login(loginForm.username, loginForm.password);
    appStore.openTabs = [];
    ElMessage.success('登录成功，欢迎 ' + res.user.displayName);
    await appStore.loadMenuTree();
    await router.push(authStore.defaultHomePath());
  } catch (e) {
    loginError.value = e.message;
  } finally {
    loginLoading.value = false;
  }
}
</script>
