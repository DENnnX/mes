export const DATA_TYPE_OPTIONS = [
  { value: 'String', label: '单行文本 (String)' },
  { value: 'Text', label: '多行文本 (Text)' },
  { value: 'Integer', label: '整数 (Integer)' },
  { value: 'Long', label: '长整数 (Long)' },
  { value: 'Double', label: '小数/金额 (Double)' },
  { value: 'Decimal', label: '高精度小数 (Decimal)' },
  { value: 'Boolean', label: '是/否开关 (Boolean)' },
  { value: 'Date', label: '日期 (Date)' },
  { value: 'DateTime', label: '日期时间 (DateTime)' },
  { value: 'Selection', label: '固定下拉选项 (Selection)' },
  { value: 'Dict', label: '数据字典下拉 (Dict)' },
  { value: 'ManyToOne', label: '关联单选 (ManyToOne)' },
  { value: 'OneToMany', label: '关联明细子表 (OneToMany)' },
  { value: 'ManyToMany', label: '关联多选 (ManyToMany)' },
  { value: 'File', label: '文件附件 (File)' },
  { value: 'Json', label: 'JSON文本 (Json)' }
];

export const BUILTIN_ROLE_CODES = [
  'ROLE_ADMIN',
  'ROLE_DEVELOPER',
  'ROLE_PUBLISHER',
  'ROLE_APP_USER',
  'ROLE_TENANT_ADMIN'
];

export function isBuiltInRole(code) {
  return BUILTIN_ROLE_CODES.includes(code);
}

export function lifecycleTagType(state) {
  if (state === 'ONLINE_ACTIVE') return 'success';
  if (state === 'OFFLINE') return 'warning';
  if (state === 'PURGE_PENDING') return 'danger';
  return 'info';
}

export function friendlyLifecycleLabel(state) {
  const m = {
    ONLINE_ACTIVE: '已上线启用',
    OFFLINE: '已停用',
    PURGE_PENDING: '待审核清理',
    PURGED: '已清理'
  };
  return m[state] || state;
}

export function friendlyVersionStatus(st) {
  const m = { DRAFT: '草稿', VALIDATED: '检查通过', ACTIVE: '当前生效', RETIRED: '历史版本' };
  return m[st] || st;
}

export function friendlyViewType(t) {
  const m = { grid: '数据表格', search: '搜索栏', form: '录入表单' };
  return m[t] || t;
}

export function friendlyRoleLabel(rc) {
  const m = {
    ROLE_ADMIN: '系统管理员',
    ROLE_TENANT_ADMIN: '租户主管',
    ROLE_DEVELOPER: '应用设计员',
    ROLE_PUBLISHER: '上线管理员',
    ROLE_APP_USER: '业务用户'
  };
  return m[rc] || rc;
}

export function formatM2oCell(val) {
  return val && typeof val === 'object' ? (val.displayName || val.id || '') : (val ?? '');
}

export function parseFileCell(val) {
  if (!val) return null;
  let obj = val;
  if (typeof val === 'string' && val.startsWith('{')) {
    try {
      obj = JSON.parse(val);
    } catch {
      return null;
    }
  }
  if (obj && typeof obj === 'object' && obj.fileId) {
    return {
      ...obj,
      fileName: obj.fileName || obj.name || obj.fileId,
      downloadUrl: obj.downloadUrl || obj.url
    };
  }
  return null;
}

export function formatCellValue(val) {
  if (val === null || val === undefined || val === '') return '-';
  return typeof val === 'object' ? JSON.stringify(val) : String(val);
}

export function mapButtonType(t) {
  return ['primary', 'success', 'warning', 'danger', 'info'].includes(t) ? t : undefined;
}
