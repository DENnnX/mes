<template>
  <div>
    <el-dialog :model-value="visible" :title="modalTitle" width="800px" destroy-on-close @update:model-value="(val) => emit('update:visible', val)">
      <el-form label-width="125px" size="small" @submit.prevent>
        <details v-for="group in runtimeEditableGroups" :key="group.key" class="lc-form-group" open>
          <summary v-show="group.title">{{ group.title }}</summary>
          <el-row :gutter="12">
            <template v-for="col in group.columns" :key="col.name">
              <el-col :span="isWideField(col.name) ? 24 : 12">
                <el-form-item :label="col.displayName || getFieldMeta(col.name)?.displayName || col.name" :required="isFieldRequired(col.name)">
                  <template v-if="isFieldSelectionOrDict(col.name)">
                    <el-select v-model="formData[col.name]" :multiple="!!getFieldMeta(col.name)?.multiple" :disabled="isFormFieldDisabled(col.name)" style="width:100%;" clearable>
                      <el-option v-for="opt in getFieldOptions(col.name)" :key="opt.value" :label="opt.label" :value="opt.value" />
                    </el-select>
                  </template>
                  <template v-else-if="isFieldBoolean(col.name)">
                    <el-switch v-model="formData[col.name]" :disabled="isFormFieldDisabled(col.name)" />
                  </template>
                  <template v-else-if="isFieldNumber(col.name)">
                    <el-input-number v-model="formData[col.name]" :disabled="isFormFieldDisabled(col.name)" :min="getFieldMeta(col.name)?.minValue ?? -999999999" :max="getFieldMeta(col.name)?.maxValue ?? 999999999" style="width:100%;" />
                  </template>
                  <template v-else-if="isFieldManyToOne(col.name)">
                    <el-select
                      v-model="formData[col.name]"
                      :disabled="isFormFieldDisabled(col.name)"
                      filterable
                      clearable
                      remote
                      :remote-method="(kw) => fetchLookup(col.name, kw)"
                      @visible-change="(vis) => { if (vis) fetchLookup(col.name, ''); }"
                      style="width:100%;"
                      placeholder="输入关键字搜索"
                    >
                      <el-option v-for="opt in (lookupOptionsMap[col.name] || [])" :key="opt.value" :label="opt.label" :value="opt.value" />
                    </el-select>
                  </template>
                  <template v-else-if="isFieldDate(col.name)">
                    <el-date-picker
                      v-model="formData[col.name]"
                      :type="getFieldDataType(col.name) === 'datetime' ? 'datetime' : 'date'"
                      :value-format="getFieldDataType(col.name) === 'datetime' ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'"
                      :disabled="isFormFieldDisabled(col.name)"
                      style="width:100%;"
                    />
                  </template>
                  <template v-else-if="isFieldFile(col.name)">
                    <div style="display:flex;align-items:center;gap:8px;width:100%;">
                      <input type="file" v-if="!isFormFieldDisabled(col.name)" :accept="getFieldMeta(col.name)?.contentType || '*/*'" @change="(ev) => onUploadFile(col.name, ev)" />
                      <el-button
                        v-if="parseFileCell(formData[col.name])"
                        link
                        type="primary"
                        @click="handleDownloadFile(formData[col.name])"
                      >
                        {{ parseFileCell(formData[col.name]).fileName }}
                      </el-button>
                      <el-button v-if="!isFormFieldDisabled(col.name) && formData[col.name]" link type="danger" size="small" @click="formData[col.name] = null">清除</el-button>
                    </div>
                  </template>
                  <template v-else-if="getFieldDataType(col.name) === 'text'">
                    <el-input v-model="formData[col.name]" type="textarea" :rows="3" :disabled="isFormFieldDisabled(col.name)" />
                  </template>
                  <template v-else>
                    <el-input
                      v-model="formData[col.name]"
                      :disabled="isFormFieldDisabled(col.name)"
                      :placeholder="getFieldMeta(col.name)?.codeRule ? '留空将按编号规则自动生成' : (getFieldMeta(col.name)?.tooltips || '')"
                    />
                  </template>
                </el-form-item>
              </el-col>
            </template>
          </el-row>
        </details>

        <el-tabs v-if="runtimeEditableTabs.length > 0" style="margin-top:8px;">
          <el-tab-pane v-for="tab in runtimeEditableTabs" :key="tab.header" :label="tab.header">
            <template v-if="tab.body?.type === 'manytomany'">
              <div style="margin-bottom:8px;"><el-button size="small" type="primary" plain @click="openAddErDialog(tab)">+ 选择关联项</el-button></div>
              <el-table :data="(m2mRows[tab.body.field] || []).filter(r => r._op !== 'unlink')" border size="small">
                <el-table-column prop="id" label="编号" width="140" />
                <el-table-column prop="displayName" label="关联名称" min-width="220" />
                <el-table-column label="操作" width="90">
                  <template #default="{ row, $index }"><el-button link type="danger" size="small" @click="removeM2mRow(tab, row, $index)">移除</el-button></template>
                </el-table-column>
              </el-table>
            </template>
            <template v-else>
              <div style="margin-bottom:8px;"><el-button size="small" type="primary" plain @click="addChildRow(tab)">+ 添加明细行</el-button></div>
              <el-table :data="(childRows[tab.body.field] || []).filter(r => r._op !== 'delete')" border size="small">
                <el-table-column v-for="cc in (tab.body.columns || [])" :key="cc.name" :label="cc.displayName || cc.name" min-width="120">
                  <template #default="{ row }">
                    <el-input-number v-if="cc.dataType === 'Integer' || cc.dataType === 'Double' || cc.dataType === 'Decimal'" v-model="row[cc.name]" size="small" controls-position="right" style="width:100%;" @change="markChildDirty(row)" />
                    <el-switch v-else-if="cc.dataType === 'Boolean'" v-model="row[cc.name]" @change="markChildDirty(row)" />
                    <el-select
                      v-else-if="cc.dataType === 'ManyToOne'"
                      v-model="row[cc.name]"
                      filterable
                      clearable
                      remote
                      :remote-method="(kw) => fetchChildLookup(tab, cc.name, kw)"
                      @visible-change="(vis) => { if (vis) fetchChildLookup(tab, cc.name, ''); }"
                      @change="markChildDirty(row)"
                      style="width:100%;"
                    >
                      <el-option v-for="opt in childLookupOptions(tab, cc.name)" :key="opt.value" :label="opt.label" :value="opt.value" />
                    </el-select>
                    <el-input v-else v-model="row[cc.name]" size="small" @change="markChildDirty(row)" />
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="120">
                  <template #default="{ row }">
                    <el-button link type="primary" size="small" @click="openChildEditModal(tab, row)">详情编辑</el-button>
                    <el-button link type="danger" size="small" @click="removeChildRow(tab, row)">删</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </template>
          </el-tab-pane>
        </el-tabs>
      </el-form>
      <template #footer>
        <el-button size="small" @click="emit('update:visible', false)">关闭</el-button>
        <el-button v-if="mode === 'create'" size="small" type="success" plain :loading="saving" @click="submitForm(true)">保存并继续新增</el-button>
        <el-button type="primary" size="small" :loading="saving" @click="submitForm(false)">保存</el-button>
      </template>
    </el-dialog>

    <!-- 多对多关联挑选弹窗 -->
    <el-dialog v-model="addErModal.visible" :title="'选择关联记录：' + addErModal.field" width="540px" destroy-on-close>
      <div style="margin-bottom:10px;display:flex;gap:8px;">
        <el-input v-model="addErModal.keyword" size="small" placeholder="搜索..." @keyup.enter="searchAddErCandidates" />
        <el-button size="small" type="primary" @click="searchAddErCandidates">搜索</el-button>
      </div>
      <el-table :data="addErModal.candidates" border size="small" max-height="290" @selection-change="(rows) => addErModal.selected = rows">
        <el-table-column type="selection" width="42" />
        <el-table-column prop="id" label="编号" width="140" />
        <el-table-column prop="label" label="名称" min-width="220" />
      </el-table>
      <template #footer>
        <el-button size="small" @click="addErModal.visible = false">取消</el-button>
        <el-button size="small" type="primary" @click="confirmAddErSelection">确认添加</el-button>
      </template>
    </el-dialog>

    <!-- 子表行详情编辑弹窗 -->
    <el-dialog v-model="childEditModal.visible" title="编辑明细行" width="460px" destroy-on-close>
      <el-form label-width="100px" size="small" @submit.prevent>
        <el-form-item v-for="cc in childEditModal.columns" :key="cc.name" :label="cc.displayName || cc.name">
          <el-input-number v-if="cc.dataType === 'Integer' || cc.dataType === 'Double' || cc.dataType === 'Decimal'" v-model="childEditModal.draft[cc.name]" style="width:100%;" />
          <el-switch v-else-if="cc.dataType === 'Boolean'" v-model="childEditModal.draft[cc.name]" />
          <el-select
            v-else-if="cc.dataType === 'ManyToOne'"
            v-model="childEditModal.draft[cc.name]"
            filterable
            clearable
            remote
            :remote-method="(kw) => fetchChildLookup(childEditModal.tab, cc.name, kw)"
            @visible-change="(vis) => { if (vis) fetchChildLookup(childEditModal.tab, cc.name, ''); }"
            style="width:100%;"
          >
            <el-option v-for="opt in childLookupOptions(childEditModal.tab, cc.name)" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
          <el-input v-else v-model="childEditModal.draft[cc.name]" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="childEditModal.visible = false">取消</el-button>
        <el-button size="small" type="primary" @click="confirmChildEditModal">完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { rpcCall, uploadFile, downloadFile } from '@/api/rpc';
import { useRuntimeFieldMeta } from '@/composables/useRuntimeFieldMeta';
import { parseFileCell } from '@/utils/formatters';

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'create' },
  modelName: { type: String, default: '' },
  recordId: { type: [String, Number], default: null },
  viewData: { type: Object, required: true },
  lookupOptionsMap: { type: Object, required: true }
});

const emit = defineEmits(['update:visible', 'saved', 'lookup']);

const saving = ref(false);
const formData = ref({});
const childRows = ref({});
const m2mRows = ref({});

const addErModal = reactive({ visible: false, field: '', relateModel: '', keyword: '', candidates: [], selected: [] });
const childEditModal = reactive({ visible: false, tab: null, row: null, draft: {}, columns: [] });

const modalTitle = computed(
  () => (props.mode === 'create' ? '新增' : '编辑') + ' - ' + (props.viewData.modelMeta?.displayName || props.modelName)
);

const {
  formColumns,
  formTabs,
  runtimeEditableGroups,
  runtimeEditableTabs,
  getFieldMeta,
  getFieldDataType,
  isFieldSelectionOrDict,
  isFieldBoolean,
  isFieldNumber,
  isFieldManyToOne,
  isFieldDate,
  isFieldFile,
  isFieldRequired,
  isWideField,
  getFieldOptions
} = useRuntimeFieldMeta(() => props.viewData);

function isFormFieldDisabled(name) {
  const m = getFieldMeta(name);
  if (!m) return false;
  if (m.related || m.computeMethod || m.computeScript) return true;
  return props.mode === 'edit' && !!m.readonly;
}

function fetchLookup(fieldName, kw) {
  emit('lookup', fieldName, kw);
}

function childLookupKey(tab, fieldName) {
  return (tab?.body?.relateModel || '') + ':' + fieldName;
}

function childLookupOptions(tab, fieldName) {
  return props.lookupOptionsMap[childLookupKey(tab, fieldName)] || [];
}

function fetchChildLookup(tab, fieldName, keyword) {
  const relateModel = tab?.body?.relateModel;
  if (!relateModel) return;
  emit('lookup', fieldName, keyword, relateModel, childLookupKey(tab, fieldName));
}

async function handleDownloadFile(val) {
  try {
    await downloadFile(val);
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function initCreateForm() {
  let init = {};
  try {
    init = await rpcCall(props.modelName, 'addMissingDefaultValues', { values: {} });
  } catch {
    // ignore
  }
  for (const [fn, f] of Object.entries(props.viewData.fields || {})) {
    if (init[fn] === undefined && (f.dataType || '').toLowerCase() === 'boolean') init[fn] = false;
    if (init[fn] === undefined && f.multiple) init[fn] = [];
  }
  const cMap = {};
  const mMap = {};
  for (const tab of runtimeEditableTabs.value) {
    if (tab.body?.type === 'manytomany') mMap[tab.body.field] = [];
    else cMap[tab.body.field] = [];
  }
  childRows.value = cMap;
  m2mRows.value = mMap;
  formData.value = init;
}

async function initEditForm() {
  try {
    const full = await rpcCall(props.modelName, 'find', { id: props.recordId });
    const data = { ...full };
    for (const [fn, f] of Object.entries(props.viewData.fields || {})) {
      const dt = (f.dataType || '').toLowerCase();
      if (dt === 'manytoone' && data[fn] && typeof data[fn] === 'object') {
        props.lookupOptionsMap[fn] = [{ id: data[fn].id, value: data[fn].id, label: data[fn].displayName }];
        data[fn] = data[fn].id;
      } else if (dt === 'dict') {
        if (Array.isArray(data[fn])) data[fn] = data[fn].map((it) => (it && typeof it === 'object' ? it.value : it));
        else if (data[fn] && typeof data[fn] === 'object') data[fn] = data[fn].value;
      }
    }
    const cMap = {};
    const mMap = {};
    for (const tab of runtimeEditableTabs.value) {
      const fk = tab.body.field;
      const list = Array.isArray(full[fk]) ? full[fk] : [];
      if (tab.body?.type === 'manytomany') {
        mMap[fk] = list.map((c) => ({ id: c.id || c, displayName: c.displayName || String(c), _op: 'keep' }));
      } else {
        cMap[fk] = list.map((child) => {
          const savedRow = { ...child, _op: 'keep' };
          for (const col of tab.body.columns || []) {
            const value = savedRow[col.name];
            if (col.dataType !== 'ManyToOne' || !value) continue;
            const id = typeof value === 'object' ? value.id : value;
            const key = childLookupKey(tab, col.name);
            const existing = props.lookupOptionsMap[key] || [];
            if (!existing.some((opt) => opt.value === id)) {
              props.lookupOptionsMap[key] = [...existing, { value: id, label: value.displayName || id }];
            }
            savedRow[col.name] = id;
          }
          return savedRow;
        });
      }
    }
    childRows.value = cMap;
    m2mRows.value = mMap;
    formData.value = data;
  } catch (e) {
    ElMessage.error(e.message);
    emit('update:visible', false);
  }
}

watch(
  () => [props.visible, props.mode, props.recordId],
  async ([vis]) => {
    if (!vis) return;
    if (props.mode === 'create') await initCreateForm();
    else if (props.recordId) await initEditForm();
  }
);

async function onUploadFile(fieldName, ev) {
  const file = ev.target?.files?.[0];
  if (!file) return;
  try {
    const json = await uploadFile(file);
    formData.value[fieldName] = json;
    ElMessage.success('上传成功: ' + json.fileName);
  } catch (e) {
    ElMessage.error('文件上传失败: ' + e.message);
  }
}

function addChildRow(tab) {
  const fk = tab.body.field;
  if (!childRows.value[fk]) childRows.value[fk] = [];
  const empty = { _op: 'create' };
  for (const cc of tab.body.columns || []) empty[cc.name] = cc.dataType === 'Boolean' ? false : '';
  childRows.value[fk].push(empty);
}

function markChildDirty(row) {
  if (!row._op || row._op === 'keep') row._op = 'update';
}

function removeChildRow(tab, row) {
  if (row.id) {
    row._op = 'delete';
  } else {
    const rows = childRows.value[tab.body.field];
    const index = rows ? rows.indexOf(row) : -1;
    if (index !== -1) rows.splice(index, 1);
  }
}

function openChildEditModal(tab, row) {
  childEditModal.tab = tab;
  childEditModal.columns = tab.body?.columns || [];
  childEditModal.row = row;
  childEditModal.draft = { ...row };
  childEditModal.visible = true;
}

function confirmChildEditModal() {
  Object.assign(childEditModal.row, childEditModal.draft);
  markChildDirty(childEditModal.row);
  childEditModal.visible = false;
}

async function openAddErDialog(tab) {
  addErModal.field = tab.body.field;
  addErModal.relateModel = tab.body.relateModel || getFieldMeta(tab.body.field)?.relateModel;
  addErModal.keyword = '';
  addErModal.selected = [];
  await searchAddErCandidates();
  addErModal.visible = true;
}

async function searchAddErCandidates() {
  try {
    addErModal.candidates =
      (await rpcCall(props.modelName, 'lookup', { field: addErModal.field, keyword: addErModal.keyword || '', limit: 50 })) || [];
  } catch {
    addErModal.candidates = [];
  }
}

function confirmAddErSelection() {
  const fk = addErModal.field;
  if (!m2mRows.value[fk]) m2mRows.value[fk] = [];
  for (const sel of addErModal.selected || []) {
    const ex = m2mRows.value[fk].find((x) => x.id === sel.id);
    if (ex) {
      if (ex._op === 'unlink') ex._op = 'keep';
    } else {
      m2mRows.value[fk].push({ id: sel.id, displayName: sel.label, _op: 'link' });
    }
  }
  addErModal.visible = false;
}

function removeM2mRow(tab, row, idx) {
  if (row._op === 'link') m2mRows.value[tab.body.field].splice(idx, 1);
  else row._op = 'unlink';
}

async function submitForm(keepOpenAndReset = false) {
  saving.value = true;
  try {
    const values = { ...formData.value };
    for (const fKey of ['id', 'version', 'bound_version_id', 'create_user', 'create_date', 'update_user', 'update_date', 'tenant_id']) {
      delete values[fKey];
    }
    for (const col of formColumns.value) {
      if (col.detailOnly) delete values[col.name];
    }
    for (const tab of formTabs.value) {
      if (tab.body?.readonly) delete values[tab.body.field];
    }
    for (const tab of runtimeEditableTabs.value) {
      const fk = tab.body.field;
      if (tab.body?.type === 'manytomany') {
        const cmds = [];
        for (const r of m2mRows.value[fk] || []) {
          if (r._op === 'link') cmds.push([4, r.id]);
          else if (r._op === 'unlink') cmds.push([3, r.id]);
        }
        if (cmds.length > 0) values[fk] = cmds;
      } else {
        const cmds = [];
        for (const r of childRows.value[fk] || []) {
          const clean = { ...r };
          const op = clean._op;
          delete clean._op;
          if (op === 'create') cmds.push([0, 0, clean]);
          else if (op === 'update' && clean.id) cmds.push([1, clean.id, clean]);
          else if (op === 'delete' && clean.id) cmds.push([2, clean.id]);
        }
        values[fk] = cmds;
      }
    }
    if (props.mode === 'create') {
      await rpcCall(props.modelName, 'create', { values });
      ElMessage.success('保存成功');
      if (keepOpenAndReset) {
        emit('saved', { resetPage: true, keepOpen: true });
        await initCreateForm();
        return;
      }
    } else {
      await rpcCall(props.modelName, 'update', { id: formData.value.id, version: formData.value.version, values });
      ElMessage.success('更新成功');
    }
    emit('update:visible', false);
    emit('saved', { resetPage: false, keepOpen: false });
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    saving.value = false;
  }
}
</script>
