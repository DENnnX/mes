<template>
  <el-drawer
    class="record-detail-drawer"
    :model-value="visible"
    :title="viewData.modelMeta?.displayName || modelName"
    size="min(620px, 100vw)"
    destroy-on-close
    @update:model-value="(val) => emit('update:visible', val)"
  >
    <div v-if="record" class="record-detail">
      <div v-if="recordIdentity || recordState" class="record-identity">
        <strong v-if="recordIdentity">{{ recordIdentity }}</strong>
        <el-tag v-if="recordState" :type="recordState.color">{{ recordState.label }}</el-tag>
      </div>
      <el-tabs v-if="formTabs.length > 0" class="record-relations">
        <el-tab-pane v-for="tab in formTabs" :key="tab.header" :label="tab.header">
          <template v-if="tab.body?.type === 'manytomany'">
            <el-tag v-for="it in (record[tab.body.field] || [])" :key="it.id || it" size="small" style="margin:0 6px 6px 0;">
              {{ it.displayName || it.id || it }}
            </el-tag>
            <span v-if="!(record[tab.body.field] || []).length" style="color:var(--text-muted);font-size:12px;">暂无关联记录</span>
          </template>
          <template v-else>
            <RuntimeProgress v-if="relationProgress[tab.header]" class="record-progress" :progress="relationProgress[tab.header]" :label="tab.header" />
            <el-table :data="record[tab.body.field] || []" class="record-related-table" size="small" empty-text="暂无关联记录">
              <el-table-column
                v-for="cc in (tab.body.columns || [])"
                :key="cc.name"
                :label="cc.displayName || cc.name"
                :min-width="cc.width || 88"
              >
                <template #default="{ row }">
                  <el-tag v-if="relationOption(cc, row)" :type="relationOption(cc, row).color || 'info'" size="small">
                    {{ relationOption(cc, row).label }}
                  </el-tag>
                  <template v-else>{{ formatRelatedValue(row[cc.name]) }}</template>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-tab-pane>
      </el-tabs>
      <details
        v-for="group in runtimeFormGroups"
        :key="group.key"
        class="lc-form-group"
        :open="group.columns.some(({ name }) => record[name] != null && record[name] !== '')"
      >
        <summary>{{ group.title || '基础信息' }}</summary>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item
            v-for="col in group.columns"
            :key="col.name"
            :label="col.displayName || getFieldMeta(col.name)?.displayName || col.name"
            :span="isWideField(col.name) ? 2 : 1"
          >
            <template v-if="isFieldSelectionOrDict(col.name)">
              <template v-if="Array.isArray(record[col.name])">
                <el-tag
                  v-for="(it, idx) in record[col.name]"
                  :key="idx"
                  size="small"
                  :type="getOptionTagColor(col.name, it)"
                  style="margin-right:3px;"
                >
                  {{ getOptionTagLabel(col.name, it) }}
                </el-tag>
              </template>
              <el-tag v-else size="small" :type="getOptionTagColor(col.name, record[col.name])">
                {{ getOptionTagLabel(col.name, record[col.name]) }}
              </el-tag>
            </template>
            <template v-else-if="isFieldBoolean(col.name)">
              <el-tag size="small" :type="record[col.name] ? 'success' : 'info'">{{ record[col.name] ? '是' : '否' }}</el-tag>
            </template>
            <template v-else-if="isFieldManyToOne(col.name)">{{ formatM2oCell(record[col.name]) || '-' }}</template>
            <template v-else-if="isFieldFile(col.name)">
              <el-button
                v-if="parseFileCell(record[col.name])"
                link
                type="primary"
                @click="handleDownloadFile(record[col.name])"
              >
                下载：{{ parseFileCell(record[col.name]).fileName }}
              </el-button>
              <span v-else>-</span>
            </template>
            <template v-else>{{ formatCellValue(record[col.name]) }}</template>
          </el-descriptions-item>
        </el-descriptions>
      </details>

      <details class="lc-form-group record-audit">
        <summary>系统留痕</summary>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="创建人">{{ record.create_user || '—' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ record.create_date || '—' }}</el-descriptions-item>
          <el-descriptions-item label="最后更新">{{ record.update_date || '—' }}</el-descriptions-item>
          <el-descriptions-item label="数据版本">v{{ record.version || 1 }}</el-descriptions-item>
        </el-descriptions>
      </details>
    </div>
    <template #footer>
      <el-button size="small" @click="emit('update:visible', false)">关闭</el-button>
      <el-button v-if="canEditRecord" type="primary" size="small" @click="emit('edit', record)">编辑此记录</el-button>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue';
import { ElMessage } from 'element-plus';
import { downloadFile } from '@/api/rpc';
import { useRuntimeFieldMeta } from '@/composables/useRuntimeFieldMeta';
import { formatM2oCell, parseFileCell, formatCellValue, evalEnableCondition } from '@/utils/formatters';
import { formatRelatedValue, getRuntimeProgress } from '@/utils/runtimeProgress';
import RuntimeProgress from '@/components/runtime/RuntimeProgress.vue';

const props = defineProps({
  visible: { type: Boolean, default: false },
  modelName: { type: String, default: '' },
  record: { type: Object, default: null },
  viewData: { type: Object, required: true }
});

const emit = defineEmits(['update:visible', 'edit']);

const {
  formTabs,
  identityField,
  workflowField,
  runtimeFormGroups,
  getFieldMeta,
  isFieldSelectionOrDict,
  isFieldBoolean,
  isFieldManyToOne,
  isFieldFile,
  isWideField,
  getOptionTagLabel,
  getOptionTagColor
} = useRuntimeFieldMeta(() => props.viewData);

const recordIdentity = computed(() => {
  const value = props.record?.[identityField.value?.name];
  return value == null || value === '' ? '' : formatRelatedValue(value);
});

const recordState = computed(() => {
  const field = workflowField.value;
  const value = field && props.record?.[field.name];
  return value ? { value, label: getOptionTagLabel(field.name, value), color: getOptionTagColor(field.name, value) } : null;
});

function relationOption(column, row) {
  return column.values?.find((option) => option.value === row[column.name]);
}

const relationProgress = computed(() => Object.fromEntries(
  formTabs.value.map((tab) => [tab.header, getRuntimeProgress(tab, props.record, recordState.value?.value)])
));

const canEditRecord = computed(() => {
  const body = props.viewData.views?.form?.body;
  return (
    !!props.record
    && props.viewData.auth?.update !== false
    && body?.editable !== false
    && evalEnableCondition(body?.editCondition, props.record)
  );
});

async function handleDownloadFile(val) {
  try {
    await downloadFile(val);
  } catch (e) {
    ElMessage.error(e.message);
  }
}
</script>

<style scoped>
:global(.record-detail-drawer .el-drawer__header) { margin-bottom: 0; }
.record-identity {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 14px;
}
.record-identity strong { font-size: 21px; font-weight: 650; overflow-wrap: anywhere; }
.record-relations { margin-bottom: 20px; }
.record-relations :deep(.el-tabs__header) { margin-bottom: 10px; }
.record-progress { margin-bottom: 12px; }
.record-related-table { --el-table-header-bg-color: var(--bg-muted); }
.record-audit { margin-top: 18px; }
</style>
