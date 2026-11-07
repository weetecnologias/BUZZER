/** BUZZER design tokens (mirrors /design/tokens.json). Green identity only - no blue/purple/orange primaries. */
export const colors = {
  forest: '#064E32',
  emerald: '#087D48',
  secondary: '#119B50',
  lightGreen: '#E8F6EC',
  paleMint: '#F2FAF4',
  white: '#FFFFFF',
  text: '#14251B',
  textSecondary: '#66756B',
  border: '#DCE9DF',
  success: '#119B50',
  warning: '#B7791F',
  warningBg: '#FDF5E6',
  danger: '#C53030',
  dangerBg: '#FDECEC',
  overlay: 'rgba(6, 78, 50, 0.45)',
} as const;

export const gradient = {
  primary: ['#064E32', '#087D48', '#119B50'] as const,
  header: ['#064E32', '#087D48'] as const,
};

export const radius = { sm: 8, md: 12, lg: 16, xl: 24, pill: 999 } as const;
export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 } as const;
export const font = {
  xs: 12,
  sm: 13,
  md: 15,
  lg: 17,
  xl: 20,
  xxl: 26,
  display: 32,
} as const;

export const shadow = {
  card: {
    shadowColor: '#064E32',
    shadowOpacity: 0.08,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 2 },
    elevation: 2,
  },
  raised: {
    shadowColor: '#064E32',
    shadowOpacity: 0.16,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 6 },
    elevation: 6,
  },
} as const;
