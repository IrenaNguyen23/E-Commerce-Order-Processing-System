import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'node_modules'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],

      // Unused args prefixed with _ are intentional (event handlers, destructuring).
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],

      // `any` defeats the point of typing the DTOs from the OpenAPI contract.
      '@typescript-eslint/no-explicit-any': 'error',

      // A floating promise in an event handler swallows its rejection silently.
      '@typescript-eslint/no-floating-promises': 'off',
    },
  },
  {
    // The shadcn/ui primitives deliberately export their CVA variants alongside the component —
    // `buttonVariants()` is how a Link gets button styling without nesting interactive elements.
    // That trips react-refresh's single-export rule, and the trade is worth it: the alternative
    // is a second file per primitive purely to satisfy HMR.
    files: ['src/components/ui/**/*.tsx'],
    rules: {
      'react-refresh/only-export-components': 'off',
    },
  },
);
