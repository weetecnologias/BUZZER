import React from 'react';
import { Text, View } from 'react-native';
import { colors, space } from './theme';
import { Chip, Notice, TextField, Toggle } from './ui';

export type FormField = {
  key: string;
  label: string;
  type: 'text' | 'textarea' | 'number' | 'select' | 'multiselect' | 'date' | 'datetime' | 'boolean' | 'photo';
  required?: boolean;
  options?: string[];
  min?: number;
  max?: number;
  minLength?: number;
  maxLength?: number;
  maxItems?: number;
};

export type FormSchema = { fields: FormField[] } | null | undefined;

/** Client-side mirror of the server validator, for immediate feedback. The server re-validates everything. */
export function validateForm(schema: FormSchema, values: Record<string, any>): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const f of schema?.fields ?? []) {
    const v = values[f.key];
    const empty = v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0);
    if (empty) {
      if (f.required && f.type !== 'photo') errors[f.key] = `${f.label} is required`;
      continue;
    }
    if ((f.type === 'text' || f.type === 'textarea') && f.minLength && String(v).trim().length < f.minLength) {
      errors[f.key] = `Must be at least ${f.minLength} characters`;
    }
    if (f.type === 'number') {
      const n = Number(v);
      if (Number.isNaN(n)) errors[f.key] = 'Must be a number';
      else if (f.min !== undefined && n < f.min) errors[f.key] = `Must be at least ${f.min}`;
      else if (f.max !== undefined && n > f.max) errors[f.key] = `Must be at most ${f.max}`;
    }
    if (f.type === 'date' && !/^\d{4}-\d{2}-\d{2}$/.test(String(v))) errors[f.key] = 'Use YYYY-MM-DD';
  }
  return errors;
}

/** Converts raw input to the types the server expects (numbers as numbers, etc.). */
export function normaliseAnswers(schema: FormSchema, values: Record<string, any>): Record<string, any> {
  const out: Record<string, any> = {};
  for (const f of schema?.fields ?? []) {
    const v = values[f.key];
    if (v === undefined || v === '' || v === null) continue;
    if (f.type === 'photo') continue; // uploads pending object-storage integration
    out[f.key] = f.type === 'number' ? Number(v) : v;
  }
  return out;
}

export function DynamicForm({ schema, values, onChange, errors = {} }: {
  schema: FormSchema;
  values: Record<string, any>;
  onChange: (key: string, value: any) => void;
  errors?: Record<string, string>;
}) {
  if (!schema?.fields?.length) return null;
  return (
    <View>
      {schema.fields.map((f) => {
        const label = f.label + (f.required ? ' *' : '');
        switch (f.type) {
          case 'text':
          case 'textarea':
          case 'number':
          case 'date':
          case 'datetime':
            return (
              <TextField key={f.key} label={label} value={values[f.key] == null ? '' : String(values[f.key])}
                onChangeText={(t) => onChange(f.key, t)} multiline={f.type === 'textarea'}
                keyboardType={f.type === 'number' ? 'numeric' : 'default'}
                placeholder={f.type === 'date' ? 'YYYY-MM-DD' : f.type === 'datetime' ? 'YYYY-MM-DDTHH:mm:ss+05:30' : undefined}
                maxLength={f.maxLength} error={errors[f.key]} />
            );
          case 'select':
          case 'multiselect': {
            const multi = f.type === 'multiselect';
            const current: string[] = multi ? (values[f.key] ?? []) : values[f.key] ? [values[f.key]] : [];
            return (
              <View key={f.key} style={{ marginBottom: space.md }}>
                <Text style={{ fontWeight: '600', color: colors.text, marginBottom: 6 }}>{label}</Text>
                <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
                  {(f.options ?? []).map((o) => (
                    <Chip key={o} label={o} selected={current.includes(o)} onPress={() => {
                      if (multi) onChange(f.key, current.includes(o) ? current.filter((x) => x !== o) : [...current, o]);
                      else onChange(f.key, o);
                    }} />
                  ))}
                </View>
                {errors[f.key] ? <Text style={{ color: colors.danger, fontSize: 12, marginTop: 4 }}>{errors[f.key]}</Text> : null}
              </View>
            );
          }
          case 'boolean':
            return <Toggle key={f.key} label={label} value={!!values[f.key]} onChange={(v) => onChange(f.key, v)} />;
          case 'photo':
            return (
              <View key={f.key} style={{ marginBottom: space.md }}>
                <Notice tone="neutral" message={`${f.label}: photo upload will be enabled once secure document storage is configured. Describe the issue in text for now.`} />
              </View>
            );
          default:
            return null;
        }
      })}
    </View>
  );
}
