<template>
  <div>
    <el-alert
      type="info"
      :closable="false"
      style="margin-bottom:12px;"
      title="用法：先在左侧选中或新建字典分类、右侧维护下拉选项；建数据表时给字段选择“数据字典下拉 (Dict)”即可直接选用。"
    />
    <el-row :gutter="14">
      <el-col :span="10">
        <div class="lc-card">
          <div class="lc-card-header">
            <h2 class="lc-card-title">字典分类列表</h2>
            <el-button size="small" type="primary" @click="openDictTypeDialog(null)">+ 新增分类</el-button>
          </div>
          <el-table :data="appStore.dictTypes" border size="small" highlight-current-row @row-click="(r) => selectDictType(r.typeCode)">
            <el-table-column prop="name" label="字典名称" min-width="115" />
            <el-table-column prop="typeCode" label="调用编码" min-width="115" />
            <el-table-column label="操作" width="105">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click.stop="openDictTypeDialog(row)">改</el-button>
                <el-button link type="danger" size="small" @click.stop="deleteDictType(row.typeCode)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
      <el-col :span="14">
        <div class="lc-card">
          <div class="lc-card-header">
            <h2 class="lc-card-title">选项内容明细 {{ activeDictTypeCode ? '(' + activeDictTypeCode + ')' : '' }}</h2>
            <el-button size="small" type="primary" :disabled="!activeDictTypeCode" @click="openDictItemDialog(null)">+ 添加选项</el-button>
          </div>
          <el-table :data="dictItems" border size="small">
            <el-table-column prop="itemValue" label="存储值" width="115" />
            <el-table-column prop="itemLabel" label="展示名称" width="135" />
            <el-table-column label="标签颜色" width="125">
              <template #default="{ row }"><el-tag size="small" :type="row.color || 'info'">{{ row.itemLabel }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="sequence" label="排序" width="65" />
            <el-table-column label="操作" min-width="105">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDictItemDialog(row)">改</el-button>
                <el-button link type="danger" size="small" @click="deleteDictItem(row.id)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
    </el-row>

    <el-dialog v-model="dictTypeDialog.visible" title="字典分类信息" width="430px" destroy-on-close>
      <el-form label-width="95px" size="small">
        <el-form-item label="调用编码" required><el-input v-model="dictTypeDialog.form.typeCode" /></el-form-item>
        <el-form-item label="字典名称" required><el-input v-model="dictTypeDialog.form.name" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="dictTypeDialog.form.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dictTypeDialog.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="saveDictType">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dictItemDialog.visible" title="字典选项值配置" width="430px" destroy-on-close>
      <el-form label-width="95px" size="small">
        <el-form-item label="所属字典"><el-input v-model="dictItemDialog.form.typeCode" disabled /></el-form-item>
        <el-form-item label="存储值" required><el-input v-model="dictItemDialog.form.itemValue" /></el-form-item>
        <el-form-item label="展示文本" required><el-input v-model="dictItemDialog.form.itemLabel" /></el-form-item>
        <el-form-item label="标签颜色">
          <el-select v-model="dictItemDialog.form.color" style="width:100%;">
            <el-option value="info" label="灰色" />
            <el-option value="primary" label="蓝色" />
            <el-option value="success" label="绿色" />
            <el-option value="warning" label="橙色" />
            <el-option value="danger" label="红色" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序号"><el-input-number v-model="dictItemDialog.form.sequence" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dictItemDialog.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="saveDictItem">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { rpcCall } from '@/api/rpc';
import { useAppStore } from '@/stores/app';

const appStore = useAppStore();
const dictItems = ref([]);
const activeDictTypeCode = ref('');
const dictTypeDialog = reactive({ visible: false, form: { id: '', typeCode: '', name: '', remark: '', status: 'ACTIVE' } });
const dictItemDialog = reactive({ visible: false, form: { id: '', typeCode: '', itemValue: '', itemLabel: '', color: 'info', sequence: 10, status: 'ACTIVE' } });

async function selectDictType(tc) {
  activeDictTypeCode.value = tc;
  try {
    dictItems.value = (await rpcCall('meta_dict', 'listItems', { typeCode: tc })) || [];
  } catch {
    dictItems.value = [];
  }
}

async function loadAll() {
  await appStore.loadDictTypes();
  if (!activeDictTypeCode.value && appStore.dictTypes.length > 0) {
    await selectDictType(appStore.dictTypes[0].typeCode);
  } else if (activeDictTypeCode.value) {
    await selectDictType(activeDictTypeCode.value);
  }
}

function openDictTypeDialog(row) {
  dictTypeDialog.form = row ? { ...row } : { id: '', typeCode: '', name: '', remark: '', status: 'ACTIVE' };
  dictTypeDialog.visible = true;
}

async function saveDictType() {
  try {
    await rpcCall('meta_dict', 'saveType', { values: dictTypeDialog.form });
    ElMessage.success('保存成功');
    dictTypeDialog.visible = false;
    await loadAll();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function deleteDictType(typeCode) {
  try {
    await ElMessageBox.confirm('确认删除该字典分类及选项？', '确认', { type: 'warning' });
    await rpcCall('meta_dict', 'deleteType', { typeCode });
    if (activeDictTypeCode.value === typeCode) {
      activeDictTypeCode.value = '';
      dictItems.value = [];
    }
    await loadAll();
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message);
  }
}

function openDictItemDialog(row) {
  dictItemDialog.form = row ? { ...row } : { id: '', typeCode: activeDictTypeCode.value, itemValue: '', itemLabel: '', color: 'info', sequence: 10, status: 'ACTIVE' };
  dictItemDialog.visible = true;
}

async function saveDictItem() {
  try {
    await rpcCall('meta_dict', 'saveItem', { values: dictItemDialog.form });
    ElMessage.success('保存成功');
    dictItemDialog.visible = false;
    await selectDictType(activeDictTypeCode.value);
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function deleteDictItem(id) {
  try {
    await rpcCall('meta_dict', 'deleteItem', { id });
    await selectDictType(activeDictTypeCode.value);
  } catch (e) {
    ElMessage.error(e.message);
  }
}

onMounted(loadAll);
</script>
