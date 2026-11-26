<template>
  <div class="lc-card">
    <div class="lc-card-header">
      <h2 class="lc-card-title">单据自动编号规则</h2>
      <div style="display:flex;gap:8px;">
        <el-button size="small" type="primary" @click="openCodeRuleDialog(null)">+ 新增编号规则</el-button>
        <el-button size="small" @click="appStore.loadCodeRules()">刷新</el-button>
      </div>
    </div>
    <el-table :data="appStore.codeRules" border size="small">
      <el-table-column prop="name" label="规则名称" width="160" />
      <el-table-column prop="code" label="规则代号" width="150" />
      <el-table-column prop="prefix" label="固定前缀" width="95" />
      <el-table-column prop="dateFormat" label="日期格式" width="115" />
      <el-table-column prop="seqLength" label="流水位数" width="85" align="center" />
      <el-table-column label="测试与操作" min-width="230">
        <template #default="{ row }">
          <el-button size="small" type="success" plain @click="testGenerateCode(row.code)">试生成一次</el-button>
          <el-button link type="primary" size="small" @click="openCodeRuleDialog(row)">改</el-button>
          <el-button link type="danger" size="small" @click="deleteCodeRule(row.code)">删</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="codeRuleDialog.visible" title="自动编号规则设置" width="450px" destroy-on-close>
      <el-form label-width="95px" size="small">
        <el-form-item label="规则代号" required><el-input v-model="codeRuleDialog.form.code" placeholder="如 ORDER_NO" /></el-form-item>
        <el-form-item label="规则名称" required><el-input v-model="codeRuleDialog.form.name" /></el-form-item>
        <el-form-item label="固定前缀"><el-input v-model="codeRuleDialog.form.prefix" placeholder="如 ORD-" /></el-form-item>
        <el-form-item label="日期格式">
          <el-select v-model="codeRuleDialog.form.dateFormat" style="width:100%;">
            <el-option value="yyyyMMdd" label="年月日 (yyyyMMdd)" />
            <el-option value="yyyyMM" label="年月 (yyyyMM)" />
            <el-option value="yyyy" label="年 (yyyy)" />
            <el-option value="NONE" label="不带日期" />
          </el-select>
        </el-form-item>
        <el-form-item label="流水位数"><el-input-number v-model="codeRuleDialog.form.seqLength" :min="1" :max="10" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="codeRuleDialog.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="saveCodeRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive } from 'vue';
import { ElMessage } from 'element-plus';
import { rpcCall } from '@/api/rpc';
import { useAppStore } from '@/stores/app';

const appStore = useAppStore();
const codeRuleDialog = reactive({
  visible: false,
  form: { id: '', code: '', name: '', prefix: 'NO-', dateFormat: 'yyyyMMdd', seqLength: 4, status: 'ACTIVE' }
});

function openCodeRuleDialog(row) {
  codeRuleDialog.form = row ? { ...row } : { id: '', code: '', name: '', prefix: 'NO-', dateFormat: 'yyyyMMdd', seqLength: 4, status: 'ACTIVE' };
  codeRuleDialog.visible = true;
}

async function saveCodeRule() {
  try {
    await rpcCall('meta_code_rule', 'saveRule', { values: codeRuleDialog.form });
    ElMessage.success('保存成功');
    codeRuleDialog.visible = false;
    await appStore.loadCodeRules();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function deleteCodeRule(code) {
  try {
    await rpcCall('meta_code_rule', 'deleteRule', { code });
    await appStore.loadCodeRules();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function testGenerateCode(ruleCode) {
  try {
    const res = await rpcCall('meta_code_rule', 'generateCode', { ruleCode });
    ElMessage.success('试生成编号：' + res.generatedCode);
  } catch (e) {
    ElMessage.error(e.message);
  }
}

onMounted(() => {
  appStore.loadCodeRules();
});
</script>
