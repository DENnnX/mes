import { formatCellValue, formatM2oCell } from '@/utils/formatters';

const progressStates = ['PENDING', 'RUNNING', 'DONE'];

export function findProgressColumn(tab) {
  if (!tab.body?.readonly) return undefined;
  return tab.body.columns?.find((column) =>
    Array.isArray(column.values) && progressStates.every((state) => column.values.some((option) => option.value === state))
  );
}

export function formatRelatedValue(value) {
  if (value == null || value === '') return '—';
  return typeof value === 'object' ? formatM2oCell(value) : formatCellValue(value);
}

export function getRuntimeProgress(tab, record, parentState) {
  const stateColumn = findProgressColumn(tab);
  const rows = record?.[tab.body?.field];
  if (!stateColumn || !Array.isArray(rows) || !rows.length) return null;

  const columns = tab.body.columns;
  const firstColumn = columns[0].name;
  const labels = columns.slice(0, columns.indexOf(stateColumn));
  const ordered = [...rows].sort((left, right) => Number(left[firstColumn]) - Number(right[firstColumn]));
  const steps = ordered.map((row, index) => {
    const state = row[stateColumn.name];
    const label = stateColumn.values.find((option) => option.value === state)?.label || String(state ?? '—');
    return {
      id: row.id ?? index,
      name: labels.map((column) => formatRelatedValue(row[column.name])).join(' ') || String(index + 1),
      state,
      label
    };
  });
  const nextStep = !parentState || parentState === 'RUNNING'
    ? steps.find((step) => step.state === 'RUNNING') || steps.find((step) => step.state === 'PENDING')
    : null;

  return {
    completed: steps.filter((step) => step.state === 'DONE').length,
    completedLabel: stateColumn.values.find((option) => option.value === 'DONE').label,
    total: steps.length,
    next: nextStep ? nextStep.label + ' · ' + nextStep.name : '',
    steps
  };
}
