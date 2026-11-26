<template>
  <div v-if="authStore.authChecking" style="height:100vh;display:flex;align-items:center;justify-content:center;color:var(--text-muted);font-size:14px;">
    正在验证会话状态...
  </div>

  <main v-else class="lc-login-page">
    <div class="lc-login-card">
      <div class="lc-login-form-box">
        <div class="lc-brand lc-login-brand">
          <div class="lc-logo" aria-hidden="true"><el-icon :size="17"><Grid /></el-icon></div>
          <span class="lc-title">云枢低代码应用平台</span>
        </div>
        <h1>账号登录</h1>

        <el-alert v-if="loginError" :title="loginError" type="error" show-icon :closable="true" @close="loginError = ''" style="margin-bottom:16px;" />

        <el-form label-position="top" size="default" @submit.prevent="submitLoginPage">
          <el-form-item label="账号" required>
            <el-input v-model="loginForm.username" placeholder="请输入用户名或租户账号" prefix-icon="User" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码" required>
            <el-input v-model="loginForm.password" type="password" show-password placeholder="请输入登录密码" prefix-icon="Lock" autocomplete="current-password" />
          </el-form-item>
          <div class="lc-login-options">
            <el-checkbox v-model="rememberUsername" size="small">记住账号</el-checkbox>
            <span>忘记密码请联系管理员</span>
          </div>
          <el-button class="lc-login-submit" type="primary" native-type="submit" :loading="loginLoading">
            登录
          </el-button>
        </el-form>

        <div class="lc-login-footer-hint">
          <el-popover placement="top-end" :width="320" trigger="click">
            <template #reference>
              <el-button link size="small" style="color:var(--text-muted);">系统初始账号说明</el-button>
            </template>
            <div style="font-size:12.5px;line-height:1.7;">
              <div style="font-weight:600;margin-bottom:6px;color:var(--text-main);">系统初始化默认账号（初始密码 123456）：</div>
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
      <div class="lc-login-art lc-dotted" aria-hidden="true">
        <div class="lc-login-orbit">
          <span></span><span></span><span></span>
          <div class="lc-logo"><el-icon :size="25"><Grid /></el-icon></div>
        </div>
        <p class="lc-login-art-title">云枢工作区</p>
      </div>
    </div>
  </main>
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
  if (loginLoading.value) return;
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
